package com.agrosphere.operations.crophealth.infrastructure;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.crophealth.domain.*;
import com.agrosphere.operations.crophealth.application.CropHealthPorts.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcCropHealthStore implements LockedRoots,Ownership,Queries,Evidence {
  private final NamedParameterJdbcTemplate db;
  public JdbcCropHealthStore(NamedParameterJdbcTemplate db){this.db=db;}
  private void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Crop writes require transaction");}
  private MapSqlParameterSource scoped(UUID id,ReadScope scope){return new MapSqlParameterSource("id",id).addValue("estate",Objects.requireNonNull(scope).estateId());}
  private String read(String table,UUID id,ReadScope scope,boolean lock) {
    if(lock)transaction();return db.queryForObject("SELECT snapshot::text FROM "+table+" WHERE id=:id AND estate_id=:estate"+(lock?" FOR UPDATE":""),scoped(id,scope),String.class);
  }
  @Override public FieldInspection inspection(UUID id,ReadScope scope){return CropHealthState.inspection(read("field_inspection",id,scope,false));}
  @Override public CropIncident incident(UUID id,ReadScope scope){return CropHealthState.incident(read("crop_health_incident",id,scope,false));}
  @Override public FieldInspection lockInspection(UUID id,ReadScope scope){return CropHealthState.inspection(read("field_inspection",id,scope,true));}
  @Override public CropIncident lockIncident(UUID id,ReadScope scope){return CropHealthState.incident(read("crop_health_incident",id,scope,true));}
  @Override public UUID inspectionEstate(UUID id){return db.queryForObject("SELECT estate_id FROM field_inspection WHERE id=:id",new MapSqlParameterSource("id",id),UUID.class);}
  @Override public UUID incidentEstate(UUID id){return db.queryForObject("SELECT estate_id FROM crop_health_incident WHERE id=:id",new MapSqlParameterSource("id",id),UUID.class);}
  private MapSqlParameterSource parameters(Root root) {
    var a=root.attribution();var audit=root.audit();var p=new MapSqlParameterSource("id",root.id()).addValue("estate",a.estateId()).addValue("block",a.blockId())
        .addValue("zone",a.estateTimezone().getId()).addValue("provenance",a.dataProvenance().name()).addValue("version",audit.version())
        .addValue("created",Timestamp.from(audit.createdAt())).addValue("updated",Timestamp.from(audit.updatedAt())).addValue("creator",audit.createdBy())
        .addValue("actor",audit.updatedBy()).addValue("snapshot",CropHealthState.write(root));
    if(root instanceof FieldInspection i)p.addValue("observed",Timestamp.from(i.observation().observedAt())).addValue("outcome",i.observation().outcome().name())
        .addValue("state",i.state().name()).addValue("link",i.linkedIncidentId());
    else {var c=(CropIncident)root;p.addValue("observed",Timestamp.from(c.report().observedAt())).addValue("category",c.report().category().name())
        .addValue("severity",c.report().severity().name()).addValue("status",c.status().name()).addValue("link",c.inspectionId())
        .addValue("resolved",c.resolvedAt()==null?null:Timestamp.from(c.resolvedAt()))
        .addValue("earliest",c.earliestRetainedResolutionAt()==null?null:Timestamp.from(c.earliestRetainedResolutionAt()));}
    return p;
  }
  @Override public void insert(Root root) {
    transaction();boolean i=root instanceof FieldInspection;String table=i?"field_inspection":"crop_health_incident";
    String columns="id,estate_id,block_id,estate_timezone,data_provenance,observed_at,version,created_at,updated_at,created_by,updated_by,snapshot,";
    String values=":id,:estate,:block,:zone,:provenance,:observed,:version,:created,:updated,:creator,:actor,CAST(:snapshot AS jsonb),";
    columns+=i?"outcome,state,linked_incident_id":"inspection_id,category,severity,status,resolved_at,earliest_resolution_at";
    values+=i?":outcome,:state,:link":":link,:category,:severity,:status,:resolved,:earliest";
    db.update("INSERT INTO "+table+"("+columns+") VALUES("+values+")",parameters(root));
  }
  @Override public void update(Root root,long expected) {
    transaction();boolean i=root instanceof FieldInspection;
    String changes=i?"outcome=:outcome,state=:state,linked_incident_id=:link":"category=:category,severity=:severity,status=:status,resolved_at=:resolved,earliest_resolution_at=:earliest";
    int count=db.update("UPDATE "+(i?"field_inspection":"crop_health_incident")+" SET "+changes+",observed_at=:observed,version=:version,updated_at=:updated,updated_by=:actor,snapshot=CAST(:snapshot AS jsonb)"
        +" WHERE id=:id AND estate_id=:estate AND version=:expected",parameters(root).addValue("expected",expected));
    if(count!=1)throw new Conflict("Stale crop evidence version");
  }
  /** Write a revision with its explicit event identity in one transaction. */
  public void append(Revision revision,UUID eventId) {
    transaction();var root=revision.snapshot();boolean i=root instanceof FieldInspection;
    int count=db.update("INSERT INTO "+(i?"inspection_revision":"incident_revision")+"(root_id,version,command_kind,reason,recorded_at,recorded_by,correlation_id,snapshot,event_id)"
        +" SELECT :id,:version,:kind,:reason,:updated,:actor,:correlation,CAST(:snapshot AS jsonb),:event FROM "+(i?"field_inspection":"crop_health_incident")
        +" WHERE id=:id AND version=:version AND snapshot=CAST(:snapshot AS jsonb)",parameters(root).addValue("kind",revision.commandKind().name())
        .addValue("reason",revision.reason()).addValue("correlation",revision.correlationId()).addValue("event",eventId));
    if(count!=1)throw new Conflict("Revision/root mismatch");
    appendMinimalEvent(revision,eventId);
  }
  @Override public void appendRevision(Revision revision){append(revision,UUID.randomUUID());}
  @Override public void appendMinimalEvent(Revision revision,UUID eventId) {
    transaction();var root=revision.snapshot();db.update("INSERT INTO outbox_event(event_id,aggregate_id,event_type,event_version,correlation_id,occurred_at,payload)"
        +" VALUES(:id,:root,:type,1,:correlation,:at,CAST(:body AS jsonb))",new MapSqlParameterSource("id",eventId).addValue("root",root.id())
        .addValue("type",CropHealthState.eventType(revision)).addValue("correlation",revision.correlationId()).addValue("at",Timestamp.from(revision.recordedAt()))
        .addValue("body",CropHealthState.event(revision,eventId)));
  }
  private MapSqlParameterSource query(Filter f,ReadScope scope) {
    if(!f.estateId().equals(scope.estateId()))throw new Denied("Crop query estate denied");
    return new MapSqlParameterSource("estate",scope.estateId()).addValue("provenance",f.provenance().name()).addValue("block",f.blockId())
        .addValue("limit",f.size()).addValue("offset",(long)f.page()*f.size());
  }
  private String where(Filter f,MapSqlParameterSource p) {
    String sql="estate_id=:estate AND data_provenance=:provenance";
    if(f.blockId()!=null)sql+=" AND block_id=:block";
    if(f.observedFrom()!=null){sql+=" AND observed_at>=:from AND observed_at<:to";p.addValue("from",Timestamp.from(f.observedFrom())).addValue("to",Timestamp.from(f.observedTo()));}return sql;
  }
  @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Page<FieldInspection> inspections(InspectionFilter f,ReadScope scope) {
    var p=query(f.common(),scope);String sql=where(f.common(),p);
    if(f.outcome()!=null){sql+=" AND outcome=:outcome";p.addValue("outcome",f.outcome().name());}
    if(f.state()!=null){sql+=" AND state=:state";p.addValue("state",f.state().name());}
    long total=db.queryForObject("SELECT count(*) FROM field_inspection WHERE "+sql,p,Long.class);
    var rows=db.query("SELECT snapshot::text FROM field_inspection WHERE "+sql+" ORDER BY observed_at DESC,id DESC LIMIT :limit OFFSET :offset",p,(r,n)->CropHealthState.inspection(r.getString(1)));
    return new Page<>(rows,f.common().page(),f.common().size(),total);
  }
  @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Page<CropIncident> incidents(IncidentFilter f,ReadScope scope) {
    var p=query(f.common(),scope);String sql=where(f.common(),p)+" AND status IN(:statuses)";p.addValue("statuses",f.statuses().stream().map(Enum::name).toList());
    if(f.category()!=null){sql+=" AND category=:category";p.addValue("category",f.category().name());}
    if(f.severity()!=null){sql+=" AND severity=:severity";p.addValue("severity",f.severity().name());}
    long total=db.queryForObject("SELECT count(*) FROM crop_health_incident WHERE "+sql,p,Long.class);
    var rows=db.query("SELECT snapshot::text FROM crop_health_incident WHERE "+sql+" ORDER BY observed_at DESC,id DESC LIMIT :limit OFFSET :offset",p,(r,n)->CropHealthState.incident(r.getString(1)));
    return new Page<>(rows,f.common().page(),f.common().size(),total);
  }
  @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Page<Revision> history(UUID id,boolean inspection,ReadScope scope,int page,int size) {
    if(inspection)inspection(id,scope);else incident(id,scope);
    require(page>=0 && size>=1 && size<=100 && (long)page*size<=Integer.MAX_VALUE,"Invalid history page");
    String table=inspection?"inspection_revision":"incident_revision";var p=new MapSqlParameterSource("id",id).addValue("limit",size).addValue("offset",(long)page*size);
    long total=db.queryForObject("SELECT count(*) FROM "+table+" WHERE root_id=:id",p,Long.class);
    var rows=db.query("SELECT * FROM "+table+" WHERE root_id=:id ORDER BY version LIMIT :limit OFFSET :offset",p,(r,n)->new Revision(
        inspection?CropHealthState.inspection(r.getString("snapshot")):CropHealthState.incident(r.getString("snapshot")),Kind.valueOf(r.getString("command_kind")),
        r.getString("reason"),r.getTimestamp("recorded_at").toInstant(),r.getString("recorded_by"),r.getObject("correlation_id",UUID.class)));
    return new Page<>(rows,page,size,total);
  }
  public Optional<Receipt> reserve(String actor,UUID key,String path,String hash) { return reserve(actor,key,path,hash,"MANAGER"); }
  public Optional<Receipt> reserve(String actor,UUID key,String path,String hash,String permissionClass) {
    transaction();db.getJdbcTemplate().execute("SET LOCAL lock_timeout='3s'");
    var p=new MapSqlParameterSource("actor",actor).addValue("key",key).addValue("path",path).addValue("hash",hash).addValue("permission",permissionClass);
    if(db.update("INSERT INTO crop_health_command(actor,idempotency_key,method,resource_path,request_hash,permission_class,created_at) VALUES(:actor,:key,'POST',:path,:hash,:permission,now()) ON CONFLICT DO NOTHING",p)==1)return Optional.empty();
    var row=db.queryForMap("SELECT * FROM crop_health_command WHERE actor=:actor AND idempotency_key=:key FOR UPDATE",p);
    if(!"POST".equals(row.get("method"))||!path.equals(row.get("resource_path"))||!hash.equals(row.get("request_hash")))throw new Conflict("Idempotency key identifies a different command");
    if(row.get("completed_at")==null)throw new IllegalStateException("Incomplete crop receipt");
    if("MANAGER".equals(row.get("permission_class"))&&!"MANAGER".equals(permissionClass))throw new Denied("Permissions changed; receipt replay denied");
    if("SUPERVISOR".equals(permissionClass)) {
      var result=CropHealthState.parse((String)row.get("response_body"));if(result.has("inspection"))result=result.required("inspection");
      var zone=java.time.ZoneId.of(result.required("estateTimezone").asText());
      if(!java.time.Instant.parse(result.required("observedAt").asText()).atZone(zone).toLocalDate().equals(java.time.LocalDate.now(zone)))
        throw new Denied("Supervisor receipt no longer authorizes today's evidence");
    }
    return Optional.of(new Receipt(actor,key,"POST",path,hash,1,((Number)row.get("response_status")).intValue(),(String)row.get("response_body"),(String)row.get("response_location")));
  }
  @Override public void saveReceipt(Receipt r) {
    transaction();int count=db.update("UPDATE crop_health_command SET completed_at=now(),response_status=:status,response_body=:body,response_location=:location"
        +" WHERE actor=:actor AND idempotency_key=:key AND request_hash=:hash AND resource_path=:path AND completed_at IS NULL",new MapSqlParameterSource("actor",r.actor())
        .addValue("key",r.key()).addValue("hash",r.normalizedRequestHash()).addValue("path",r.path()).addValue("status",r.status()).addValue("body",r.body()).addValue("location",r.location()));
    if(count!=1)throw new Conflict("Receipt reservation mismatch");
  }
}
