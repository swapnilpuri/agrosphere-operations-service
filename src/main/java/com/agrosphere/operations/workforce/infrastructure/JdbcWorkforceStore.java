package com.agrosphere.operations.workforce.infrastructure;

import com.agrosphere.operations.workforce.application.WorkforcePorts.*;
import com.agrosphere.operations.workforce.domain.*;
import com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Service-local JDBC adapter. Scope always precedes reads and root locking precedes day writes. */
@Repository
public class JdbcWorkforceStore implements Registrations,Attendance,Ownership,Evidence,Queries {
  private final NamedParameterJdbcTemplate db;
  public JdbcWorkforceStore(NamedParameterJdbcTemplate db) { this.db=db; }
  private void transaction() {
    if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Workforce writes require a transaction");
  }
  private String scope(ReadScope scope,MapSqlParameterSource p) {
    Objects.requireNonNull(scope);
    if(scope.estateIds().isEmpty()) return "FALSE";
    p.addValue("scopeEstates",scope.estateIds());return "estate_id IN (:scopeEstates)";
  }
  private UUID ownership(String table,UUID id) {
    return db.queryForObject("SELECT estate_id FROM "+table+" WHERE id=:id",new MapSqlParameterSource("id",id),UUID.class);
  }
  @Override public UUID workerEstate(UUID id) { return ownership("worker_registration",id); }
  @Override public UUID attendanceEstate(UUID id) { return ownership("attendance_day",id); }
  @Override public WorkerRegistration getWorker(UUID id,ReadScope scope) { return readWorker(id,scope,false); }
  @Override public WorkerRegistration lockWorker(UUID id,ReadScope scope) { transaction();return readWorker(id,scope,true); }
  private WorkerRegistration readWorker(UUID id,ReadScope scope,boolean lock) {
    var p=new MapSqlParameterSource("id",id);
    return db.queryForObject("SELECT snapshot::text FROM worker_registration WHERE id=:id AND "+scope(scope,p)+(lock?" FOR UPDATE":""),p,
        (r,n)->WorkforceState.worker(r.getString(1)));
  }
  @Override public AttendanceDay getDay(UUID id,ReadScope scope) {
    var p=new MapSqlParameterSource("id",id);
    return db.queryForObject("SELECT snapshot::text FROM attendance_day WHERE id=:id AND "+scope(scope,p),p,(r,n)->WorkforceState.day(r.getString(1)));
  }
  @Override public Optional<AttendanceDay> findDay(UUID worker,LocalDate date,ReadScope scope) {
    var p=new MapSqlParameterSource("worker",worker).addValue("date",date);
    return db.query("SELECT snapshot::text FROM attendance_day WHERE worker_id=:worker AND attendance_date=:date AND "+scope(scope,p),p,
        (r,n)->WorkforceState.day(r.getString(1))).stream().findFirst();
  }
  @Override public Set<LocalDate> nonvoidedAttendanceDates(UUID worker,ReadScope scope) {
    lockWorker(worker,scope);var p=new MapSqlParameterSource("worker",worker);
    return Set.copyOf(db.query("SELECT attendance_date FROM attendance_day WHERE worker_id=:worker AND status<>'VOIDED' AND "+scope(scope,p),p,
        (r,n)->r.getObject(1,LocalDate.class)));
  }
  private MapSqlParameterSource workerParameters(WorkerRegistration w) {
    return new MapSqlParameterSource("id",w.id()).addValue("estate",w.estateId()).addValue("code",w.workerCode())
        .addValue("name",w.displayName()).addValue("division",w.divisionId()).addValue("provenance",w.dataProvenance().name())
        .addValue("version",w.audit().version()).addValue("created",Timestamp.from(w.audit().createdAt()))
        .addValue("updated",Timestamp.from(w.audit().updatedAt())).addValue("creator",w.audit().createdBy())
        .addValue("actor",w.audit().updatedBy()).addValue("snapshot",WorkforceState.write(w));
  }
  @Override public void insertWorker(WorkerRegistration w) {
    transaction();
    db.update("""
        INSERT INTO worker_registration(id,estate_id,worker_code,display_name,division_id,data_provenance,version,created_at,updated_at,created_by,updated_by,snapshot)
        VALUES(:id,:estate,:code,:name,:division,:provenance,:version,:created,:updated,:creator,:actor,CAST(:snapshot AS jsonb))
        """,workerParameters(w));
    periods(w);
  }
  @Override public void updateWorker(WorkerRegistration w,long expected) {
    transaction();
    if(db.update("""
        UPDATE worker_registration SET display_name=:name,division_id=:division,version=:version,updated_at=:updated,updated_by=:actor,snapshot=CAST(:snapshot AS jsonb)
        WHERE id=:id AND estate_id=:estate AND version=:expected
        """,workerParameters(w).addValue("expected",expected))!=1) throw new Conflict("Stale worker version");
    periods(w);
  }
  private void periods(WorkerRegistration w) {
    for(var period:w.activePeriods()) {
      var p=new MapSqlParameterSource("id",period.id()).addValue("worker",w.id()).addValue("from",period.activeFrom())
          .addValue("to",period.activeTo()).addValue("introduced",period.introducedVersion()).addValue("closed",period.closedVersion());
      if(period.introducedVersion()==w.audit().version()) {
        db.update("INSERT INTO worker_active_period(id,worker_id,active_from,active_to,introduced_version,closed_version) VALUES(:id,:worker,:from,:to,:introduced,:closed)",p);
      } else if(Objects.equals(period.closedVersion(),w.audit().version())) {
        if(db.update("UPDATE worker_active_period SET active_to=:to,closed_version=:closed WHERE id=:id AND worker_id=:worker AND active_to IS NULL",p)!=1)
          throw new Conflict("Active period already changed");
      }
    }
  }
  private MapSqlParameterSource dayParameters(AttendanceDay d) {
    var a=d.attribution();
    return new MapSqlParameterSource("id",d.id()).addValue("estate",a.estateId()).addValue("worker",a.workerRegistrationId())
        .addValue("date",a.attendanceDate()).addValue("zone",a.estateTimezone()).addValue("code",a.workerCodeSnapshot())
        .addValue("division",a.divisionIdSnapshot()).addValue("workerVersion",a.workerVersionSnapshot()).addValue("period",a.activePeriodId())
        .addValue("provenance",a.dataProvenance().name()).addValue("status",d.status().name()).addValue("version",d.audit().version())
        .addValue("created",Timestamp.from(d.audit().createdAt())).addValue("updated",Timestamp.from(d.audit().updatedAt()))
        .addValue("creator",d.audit().createdBy()).addValue("actor",d.audit().updatedBy()).addValue("snapshot",WorkforceState.write(d));
  }
  private void lockDayWorker(AttendanceDay d) {
    lockWorker(d.attribution().workerRegistrationId(),new ReadScope(Set.of(d.attribution().estateId())));
  }
  @Override public void insertDay(AttendanceDay d) {
    transaction();lockDayWorker(d);
    db.update("""
        INSERT INTO attendance_day(id,estate_id,worker_id,attendance_date,estate_timezone,worker_code_snapshot,division_id_snapshot,
          worker_version_snapshot,active_period_id,data_provenance,status,version,created_at,updated_at,created_by,updated_by,snapshot)
        VALUES(:id,:estate,:worker,:date,:zone,:code,:division,:workerVersion,:period,:provenance,:status,:version,:created,:updated,:creator,:actor,CAST(:snapshot AS jsonb))
        """,dayParameters(d));
  }
  @Override public void updateDay(AttendanceDay d,long expected) {
    transaction();lockDayWorker(d);
    if(db.update("""
        UPDATE attendance_day SET status=:status,estate_timezone=:zone,worker_code_snapshot=:code,division_id_snapshot=:division,
          worker_version_snapshot=:workerVersion,active_period_id=:period,version=:version,updated_at=:updated,updated_by=:actor,snapshot=CAST(:snapshot AS jsonb)
        WHERE id=:id AND estate_id=:estate AND worker_id=:worker AND version=:expected
        """,dayParameters(d).addValue("expected",expected))!=1) throw new Conflict("Stale attendance version");
  }
  @Override public void append(Revision<?> revision) {
    transaction();
    boolean worker=revision.snapshot() instanceof WorkerRegistration;
    String root=worker?"worker_registration":"attendance_day", table=worker?"worker_revision":"attendance_revision", id=worker?"worker_id":"attendance_id";
    var p=new MapSqlParameterSource("id",revision.aggregateId()).addValue("version",revision.version()).addValue("kind",revision.commandKind().name())
        .addValue("reason",revision.reason()).addValue("at",Timestamp.from(revision.recordedAt())).addValue("actor",revision.recordedBy())
        .addValue("correlation",revision.correlationId()).addValue("snapshot",WorkforceState.write(revision.snapshot()));
    int count=db.update("INSERT INTO "+table+"("+id+",version,command_kind,reason,recorded_at,recorded_by,correlation_id,snapshot)"
        +" SELECT :id,:version,:kind,:reason,:at,:actor,:correlation,CAST(:snapshot AS jsonb) FROM "+root
        +" WHERE id=:id AND version=:version AND snapshot=CAST(:snapshot AS jsonb)",p);
    if(count!=1) throw new Conflict("Revision must match current root");
  }
  public List<Revision<?>> history(UUID id,boolean worker,ReadScope scope,int page,int size) {
    if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE) throw new Invalid("Invalid history page");
    // Root read is scoped before any private evidence lookup; policy authorization is an application concern.
    if(worker)getWorker(id,scope);else getDay(id,scope);
    String column=worker?"worker_id":"attendance_id",table=worker?"worker_revision":"attendance_revision";
    var p=new MapSqlParameterSource("id",id).addValue("limit",size).addValue("offset",(long)page*size);
    return db.query("SELECT version,command_kind,reason,recorded_at,recorded_by,correlation_id,snapshot::text FROM "+table
        +" WHERE "+column+"=:id ORDER BY version LIMIT :limit OFFSET :offset",p,(r,n)->new Revision<>(id,r.getLong("version"),CommandKind.valueOf(r.getString("command_kind")),
            r.getString("reason"),r.getTimestamp("recorded_at").toInstant(),r.getString("recorded_by"),r.getObject("correlation_id",UUID.class),
            worker?WorkforceState.worker(r.getString("snapshot")):WorkforceState.day(r.getString("snapshot"))));
  }
  @Override public long historyCount(UUID id,boolean worker,ReadScope scope) {
    if(worker)getWorker(id,scope);else getDay(id,scope);
    return db.queryForObject("SELECT count(*) FROM "+(worker?"worker_revision":"attendance_revision")+" WHERE "+(worker?"worker_id":"attendance_id")+"=:id",new MapSqlParameterSource("id",id),Long.class);
  }
  private void authorizedEstate(UUID estate,ReadScope scope) {
    if(estate==null||scope==null||!scope.estateIds().contains(estate))throw new Denied("Query estate denied");
  }
  private void page(int page,int size) {if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE)throw new Invalid("Invalid query page");}
  private String workerWhere(WorkerFilter f,ReadScope scope,MapSqlParameterSource p) {
    authorizedEstate(f.estateId(),scope);p.addValue("estate",f.estateId());String sql="w.estate_id=:estate";
    if(f.provenance()!=null){sql+=" AND w.data_provenance=:provenance";p.addValue("provenance",f.provenance().name());}
    if(f.divisionId()!=null){sql+=" AND w.division_id=:division";p.addValue("division",f.divisionId());}
    if(f.code()!=null){sql+=" AND w.worker_code=:code";p.addValue("code",f.code());}
    if(f.namePrefix()!=null){sql+=" AND lower(left(w.display_name,length(:prefix)))=lower(:prefix)";p.addValue("prefix",f.namePrefix());}
    if(f.activeOn()!=null){sql+=" AND "+eligible("w");p.addValue("date",f.activeOn());}
    return sql;
  }
  private String eligible(String alias){return "EXISTS(SELECT 1 FROM worker_active_period p WHERE p.worker_id="+alias+".id AND p.active_from<=:date AND (p.active_to IS NULL OR :date<p.active_to))";}
  @Override public List<WorkerRegistration> workers(WorkerFilter f,ReadScope scope) {
    page(f.page(),f.size());var p=new MapSqlParameterSource("limit",f.size()).addValue("offset",(long)f.page()*f.size());
    String where=workerWhere(f,scope,p);
    return db.query("SELECT w.snapshot::text FROM worker_registration w WHERE "+where+" ORDER BY w.worker_code,w.id LIMIT :limit OFFSET :offset",p,(r,n)->WorkforceState.worker(r.getString(1)));
  }
  @Override public long workerCount(WorkerFilter f,ReadScope scope) {
    var p=new MapSqlParameterSource();return db.queryForObject("SELECT count(*) FROM worker_registration w WHERE "+workerWhere(f,scope,p),p,Long.class);
  }
  private String dayWhere(DayFilter f,ReadScope scope,MapSqlParameterSource p) {
    authorizedEstate(f.estateId(),scope);p.addValue("estate",f.estateId()).addValue("from",f.from()).addValue("to",f.to());
    String sql="estate_id=:estate AND attendance_date BETWEEN :from AND :to";
    if(f.workerRegistrationId()!=null){sql+=" AND worker_id=:worker";p.addValue("worker",f.workerRegistrationId());}
    if(f.status()!=null){sql+=" AND status=:status";p.addValue("status",f.status().name());}
    if(f.provenance()!=null){sql+=" AND data_provenance=:provenance";p.addValue("provenance",f.provenance().name());}
    return sql;
  }
  @Override public List<AttendanceDay> days(DayFilter f,ReadScope scope) {
    page(f.page(),f.size());var p=new MapSqlParameterSource("limit",f.size()).addValue("offset",(long)f.page()*f.size());String where=dayWhere(f,scope,p);
    return db.query("SELECT snapshot::text FROM attendance_day WHERE "+where+" ORDER BY attendance_date DESC,id LIMIT :limit OFFSET :offset",p,(r,n)->WorkforceState.day(r.getString(1)));
  }
  @Override public long dayCount(DayFilter f,ReadScope scope) {
    var p=new MapSqlParameterSource();return db.queryForObject("SELECT count(*) FROM attendance_day WHERE "+dayWhere(f,scope,p),p,Long.class);
  }
  private String rosterWhere(UUID estate,LocalDate date,UUID division,Provenance provenance,ReadScope scope,MapSqlParameterSource p) {
    authorizedEstate(estate,scope);Objects.requireNonNull(provenance);
    p.addValue("estate",estate).addValue("date",date).addValue("provenance",provenance.name());
    String sql="w.estate_id=:estate AND w.data_provenance=:provenance";
    if(division!=null){sql+=" AND w.division_id=:division";p.addValue("division",division);}
    return sql;
  }
  private static final String ROSTER_JOIN=" FROM worker_registration w LEFT JOIN attendance_day a ON a.worker_id=w.id AND a.estate_id=w.estate_id AND a.attendance_date=:date AND a.data_provenance=w.data_provenance ";
  @Override public List<RosterRow> roster(UUID estate,LocalDate date,UUID division,Provenance provenance,int page,int size,ReadScope scope) {
    page(page,size);var p=new MapSqlParameterSource("limit",size).addValue("offset",(long)page*size);
    String where=rosterWhere(estate,date,division,provenance,scope,p)+" AND "+eligible("w");
    return db.query("SELECT w.snapshot::text AS worker_snapshot,a.snapshot::text AS day_snapshot"+ROSTER_JOIN+" WHERE "+where
        +" ORDER BY w.worker_code,w.id LIMIT :limit OFFSET :offset",p,(r,n)->new RosterRow(WorkforceState.worker(r.getString("worker_snapshot")),
            r.getString("day_snapshot")==null?null:WorkforceState.day(r.getString("day_snapshot"))));
  }
  @Override public Map<String,Long> coverage(UUID estate,LocalDate date,UUID division,Provenance provenance,ReadScope scope) {
    var p=new MapSqlParameterSource();String where=rosterWhere(estate,date,division,provenance,scope,p);
    var row=db.queryForMap("SELECT count(*) AS eligible,count(a.id) FILTER(WHERE a.status<>'VOIDED') AS marked,"
        +"count(a.id) FILTER(WHERE a.status='VOIDED') AS voided"+ROSTER_JOIN+" WHERE "+where+" AND "+eligible("w"),p);
    long eligible=((Number)row.get("eligible")).longValue(),marked=((Number)row.get("marked")).longValue();
    long outside=db.queryForObject("SELECT count(*) FROM attendance_day a JOIN worker_registration w ON w.id=a.worker_id WHERE "+where
        +" AND a.attendance_date=:date AND a.status<>'VOIDED' AND NOT "+eligible("w"),p,Long.class);
    return Map.of("eligibleCount",eligible,"markedCount",marked,"unmarkedCount",eligible-marked,
        "voidedCount",((Number)row.get("voided")).longValue(),"outsideRosterRecordedCount",outside);
  }
  @Override public void appendEvent(UUID eventId,String type,long aggregateVersion,String raw) {
    transaction();
    if(!Set.of("WorkerRegistrationCreated","WorkerRegistrationChanged","WorkerAttendanceRecorded","WorkerAttendanceChanged").contains(type))
      throw new Invalid("Unsupported workforce event");
    var n=WorkforceState.parse(raw);
    if(!eventId.toString().equals(n.path("eventId").asText())||!type.equals(n.path("eventType").asText())
        ||n.path("eventVersion").asInt()!=1||n.path("aggregateVersion").asLong()!=aggregateVersion)
      throw new Invalid("Workforce event envelope mismatch");
    db.update("INSERT INTO outbox_event(event_id,aggregate_id,event_type,event_version,correlation_id,occurred_at,payload)"
        +" VALUES(:id,:aggregate,:type,1,:correlation,:at,CAST(:body AS jsonb))",new MapSqlParameterSource("id",eventId)
        .addValue("aggregate",UUID.fromString(n.required("aggregateId").asText())).addValue("type",type)
        .addValue("correlation",UUID.fromString(n.required("correlationId").asText()))
        .addValue("at",Timestamp.from(Instant.parse(n.required("timestamp").asText()))).addValue("body",raw));
  }
  @Override public void saveReceipt(Receipt receipt) {
    transaction();
    int count=db.update("""
        UPDATE workforce_command SET completed_at=now(),response_status=:status,response_body=:body,response_location=:location
        WHERE actor=:actor AND idempotency_key=:key AND request_hash=:hash AND method=:method AND resource_path=:path AND completed_at IS NULL
        """,receiptParameters(receipt.actor(),receipt.key(),receipt.method(),receipt.path(),receipt.normalizedRequestHash())
        .addValue("status",receipt.status()).addValue("body",receipt.body()).addValue("location",receipt.location()));
    if(count!=1) throw new Conflict("Command receipt reservation mismatch");
  }
  private MapSqlParameterSource receiptParameters(String actor,UUID key,String method,String path,String hash) {
    return new MapSqlParameterSource("actor",actor).addValue("key",key).addValue("method",method).addValue("path",path).addValue("hash",hash);
  }
  public Optional<Receipt> reserve(String actor,UUID key,String method,String path,String hash) {
    transaction();
    db.getJdbcTemplate().execute("SET LOCAL lock_timeout='3s'");
    var p=receiptParameters(actor,key,method,path,hash);
    int inserted=db.update("""
        INSERT INTO workforce_command(actor,idempotency_key,method,resource_path,request_hash,created_at)
        VALUES(:actor,:key,:method,:path,:hash,now()) ON CONFLICT(actor,idempotency_key) DO NOTHING
        """,p);
    if(inserted==1)return Optional.empty();
    var row=db.queryForMap("SELECT method,resource_path,request_hash,completed_at,response_status,response_body,response_location"
        +" FROM workforce_command WHERE actor=:actor AND idempotency_key=:key FOR UPDATE",p);
    if(!method.equals(row.get("method"))||!path.equals(row.get("resource_path"))||!hash.equals(row.get("request_hash")))
      throw new Conflict("Idempotency key already identifies another command");
    if(row.get("completed_at")==null)throw new IllegalStateException("Incomplete durable command");
    return Optional.of(new Receipt(actor,key,method,path,hash,((Number)row.get("response_status")).intValue(),
        (String)row.get("response_body"),(String)row.get("response_location")));
  }
}
