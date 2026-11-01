package com.agrosphere.operations.alerts.infrastructure;

import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import static com.agrosphere.operations.crophealth.infrastructure.CropHealthState.*;
import com.agrosphere.operations.alerts.domain.AlertRules.Actor;
import com.agrosphere.operations.alerts.domain.CropHealthAlert;
import com.agrosphere.operations.alerts.domain.CropHealthAlert.*;
import com.agrosphere.operations.alerts.application.AlertPorts.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Repository
public class JdbcAlertStore {
  private final NamedParameterJdbcTemplate db;
  public JdbcAlertStore(NamedParameterJdbcTemplate db){this.db=db;}
  private void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Alert transaction required");}
  public void lockTimeout(){transaction();db.getJdbcTemplate().execute("SET LOCAL lock_timeout='3s'");}
  private String scope(Actor a,MapSqlParameterSource p) {
    if(a.roles().contains("PLATFORM_ADMIN"))return "TRUE";
    if(a.estates().isEmpty())return "FALSE";p.addValue("estates",a.estates());return "estate_id IN (:estates)";
  }
  public CropHealthAlert get(UUID id,Actor a,boolean lock) {
    if(lock)transaction();var p=new MapSqlParameterSource("id",id);
    var rows=db.query("SELECT snapshot::text FROM crop_health_alert WHERE id=:id AND "+scope(a,p)+(lock?" FOR UPDATE":""),p,(r,n)->AlertState.read(r.getString(1)));
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Alert not found");
    var alert=rows.getFirst();a.authorize(alert.source().estateId(),Permission.READ);return alert;
  }
  public void current(CropHealthAlert a) {
    Long version=db.queryForObject("SELECT version FROM crop_health_incident WHERE id=:id",Map.of("id",a.source().incidentId()),Long.class);
    if(version!=a.source().version())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Alert source requires reconciliation; no automatic backfill");
  }
  public void currentEstate(UUID estate){
    Long stale=db.queryForObject("SELECT count(*) FROM crop_health_alert a JOIN crop_health_incident i ON i.id=a.incident_id WHERE a.estate_id=:estate AND a.source_version<>i.version",Map.of("estate",estate),Long.class);
    if(stale>0)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Alert source requires reconciliation; no automatic backfill");
  }
  public CropHealthAlert forIncident(UUID id){
    transaction();var rows=db.query("SELECT snapshot::text FROM crop_health_alert WHERE incident_id=:id AND rule_version=:rule FOR UPDATE",Map.of("id",id,"rule",RULE),(r,n)->AlertState.read(r.getString(1)));
    return rows.isEmpty()?null:rows.getFirst();
  }
  public ReadMarker ownRead(UUID id,Identity who){
    var rows=db.query("SELECT attention_version,read_at FROM alert_read WHERE alert_id=:id AND issuer=:issuer AND subject=:subject",who(id,who),(r,n)->new ReadMarker(id,who,r.getLong(1),r.getTimestamp(2).toInstant()));return rows.isEmpty()?null:rows.getFirst();
  }
  private MapSqlParameterSource who(UUID id,Identity who){return new MapSqlParameterSource("id",id).addValue("issuer",who.issuer()).addValue("subject",who.subject());}
  public void saveRead(ReadMarker marker){
    transaction();db.update("INSERT INTO alert_read(alert_id,issuer,subject,attention_version,read_at) VALUES(:id,:issuer,:subject,:attention,:at) ON CONFLICT(alert_id,issuer,subject) DO UPDATE SET attention_version=EXCLUDED.attention_version,read_at=EXCLUDED.read_at WHERE alert_read.attention_version<EXCLUDED.attention_version",who(marker.alertId(),marker.actor()).addValue("attention",marker.attentionVersion()).addValue("at",java.sql.Timestamp.from(marker.at())));
  }
  public void save(CropHealthAlert a,CropHealthAlert previous,Transition transition){
    transaction();var s=a.source();var p=new MapSqlParameterSource("id",a.id()).addValue("incident",s.incidentId()).addValue("estate",s.estateId()).addValue("block",s.blockId()).addValue("rule",RULE)
        .addValue("source",s.version()).addValue("version",a.alertVersion()).addValue("attention",a.attentionVersion()).addValue("state",a.state().name())
        .addValue("reason",a.inactiveReason()==null?null:a.inactiveReason().name()).addValue("raised",java.sql.Timestamp.from(a.raisedAt())).addValue("at",java.sql.Timestamp.from(a.attentionAt()))
        .addValue("updated",java.sql.Timestamp.from(a.updatedAt())).addValue("snapshot",stringify(AlertState.privateView(a)));
    if(previous==null)db.update("INSERT INTO crop_health_alert(id,incident_id,rule_version,estate_id,block_id,source_version,alert_version,attention_version,state,inactive_reason,raised_at,attention_at,updated_at,snapshot) VALUES(:id,:incident,:rule,:estate,:block,:source,:version,:attention,:state,:reason,:raised,:at,:updated,CAST(:snapshot AS jsonb))",p);
    else {
      p.addValue("expected",previous.alertVersion());int n=db.update("UPDATE crop_health_alert SET source_version=:source,alert_version=:version,attention_version=:attention,state=:state,inactive_reason=:reason,attention_at=:at,updated_at=:updated,snapshot=CAST(:snapshot AS jsonb) WHERE id=:id AND alert_version=:expected",p);
      conflict(n==1,"Concurrent alert revision");
    }
    p.addValue("kind",transition.kind().name()).addValue("evidence",stringify(AlertState.transition(transition)));
    db.update("INSERT INTO alert_transition(alert_id,alert_version,source_version,attention_version,kind,recorded_at,evidence,snapshot) VALUES(:id,:version,:source,:attention,:kind,:updated,CAST(:evidence AS jsonb),CAST(:snapshot AS jsonb))",p);
    if(transition.kind()==TransitionKind.ACKNOWLEDGED){
      var ack=a.acknowledgement();db.update("INSERT INTO alert_acknowledgement(alert_id,attention_version,issuer,subject,acknowledged_at) VALUES(:id,:attention,:issuer,:subject,:at)",who(a.id(),ack.actor()).addValue("attention",ack.attentionVersion()).addValue("at",java.sql.Timestamp.from(ack.at())));
    }
  }
  public Optional<String> reserve(Actor a,UUID key,CropHealthAlert alert,Permission permission,String path,String hash){
    lockTimeout();var p=who(alert.id(),a.identity()).addValue("key",key).addValue("estate",alert.source().estateId()).addValue("permission",permission.name()).addValue("path",path).addValue("hash",hash);
    int added=db.update("INSERT INTO alert_command(issuer,subject,idempotency_key,estate_id,alert_id,permission,resource_path,request_hash) VALUES(:issuer,:subject,:key,:estate,:id,:permission,:path,:hash) ON CONFLICT DO NOTHING",p);
    if(added==1)return Optional.empty();
    var row=db.queryForMap("SELECT * FROM alert_command WHERE issuer=:issuer AND subject=:subject AND idempotency_key=:key FOR UPDATE",p);
    var receipt=new Receipt(a.identity(),key,(UUID)row.get("estate_id"),Permission.valueOf((String)row.get("permission")),(String)row.get("resource_path"),(String)row.get("request_hash"),(String)row.get("response_body"));
    receipt.authorizeReplay(a,key,path,hash);return Optional.of(receipt.originalBody());
  }
  public void receipt(Actor a,UUID key,String body){
    transaction();int n=db.update("UPDATE alert_command SET response_body=:body,completed_at=now() WHERE issuer=:issuer AND subject=:subject AND idempotency_key=:key AND completed_at IS NULL",new MapSqlParameterSource("issuer",a.identity().issuer()).addValue("subject",a.identity().subject()).addValue("key",key).addValue("body",body));conflict(n==1,"Alert receipt mismatch");
  }
  public Page<Map<String,Object>> list(Filter f,Actor actor,Instant now){
    actor.authorize(f.estateId(),Permission.READ);currentEstate(f.estateId());
    var p=new MapSqlParameterSource("estate",f.estateId()).addValue("issuer",actor.identity().issuer()).addValue("subject",actor.identity().subject()).addValue("state",f.state().name())
        .addValue("limit",f.size()).addValue("offset",(long)f.page()*f.size());
    String sql="a.estate_id=:estate AND a.state=:state";
    if(f.blockId()!=null){sql+=" AND a.block_id=:block";p.addValue("block",f.blockId());}
    if(f.severity()!=null){sql+=" AND a.snapshot->>'severity'=:severity";p.addValue("severity",f.severity().name());}
    if(f.unreadOnly())sql+=" AND COALESCE(r.attention_version,0)<a.attention_version";
    if(f.unacknowledgedOnly())sql+=" AND a.snapshot->>'acknowledged'='false'";
    String from=" FROM crop_health_alert a LEFT JOIN alert_read r ON r.alert_id=a.id AND r.issuer=:issuer AND r.subject=:subject WHERE "+sql;
    long count=db.queryForObject("SELECT count(*)"+from,p,Long.class);
    var rows=db.query("SELECT a.snapshot::text"+from+" ORDER BY a.attention_at DESC,a.id DESC LIMIT :limit OFFSET :offset",p,(r,n)->{var a=AlertState.read(r.getString(1));return AlertState.view(a,ownRead(a.id(),actor.identity()));});
    return new Page<>(rows,f.page(),f.size(),count,now);
  }
  public Counts counts(UUID estate,Actor actor,Instant now){
    actor.authorize(estate,Permission.READ);currentEstate(estate);
    var row=db.queryForMap("SELECT count(*) total,count(*) FILTER(WHERE COALESCE(r.attention_version,0)<a.attention_version) unread,count(*) FILTER(WHERE a.snapshot->>'acknowledged'='false') unacked FROM crop_health_alert a LEFT JOIN alert_read r ON r.alert_id=a.id AND r.issuer=:issuer AND r.subject=:subject WHERE a.estate_id=:estate AND a.state='ACTIVE'",new MapSqlParameterSource("estate",estate).addValue("issuer",actor.identity().issuer()).addValue("subject",actor.identity().subject()));
    return new Counts(((Number)row.get("total")).longValue(),((Number)row.get("unread")).longValue(),((Number)row.get("unacked")).longValue(),now);
  }
  public Page<Map<String,Object>> history(UUID id,Actor actor,int page,int size,Instant now){
    var a=get(id,actor,false);actor.authorize(a.source().estateId(),Permission.HISTORY);
    var p=new MapSqlParameterSource("id",id).addValue("limit",size).addValue("offset",(long)page*size);
    long count=db.queryForObject("SELECT count(*) FROM alert_transition WHERE alert_id=:id",p,Long.class);
    var rows=db.query("SELECT evidence::text FROM alert_transition WHERE alert_id=:id ORDER BY alert_version DESC LIMIT :limit OFFSET :offset",p,(r,n)->new tools.jackson.databind.ObjectMapper().readValue(r.getString(1),Map.class));
    @SuppressWarnings({"unchecked","rawtypes"}) Page<Map<String,Object>> result=new Page((List)rows,page,size,count,now);return result;
  }
}
