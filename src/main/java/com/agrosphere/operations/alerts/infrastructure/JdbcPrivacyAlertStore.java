package com.agrosphere.operations.alerts.infrastructure;

import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import static com.agrosphere.operations.crophealth.infrastructure.CropHealthState.*;
import com.agrosphere.operations.alerts.domain.AlertRules.Actor;
import com.agrosphere.operations.alerts.domain.*;
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
public class JdbcPrivacyAlertStore {
  private final NamedParameterJdbcTemplate db;
  public JdbcPrivacyAlertStore(NamedParameterJdbcTemplate db){this.db=db;}
  private void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Privacy transaction required");}
  public void safeMode(){
    Long count=db.queryForObject("SELECT (SELECT count(*) FROM crop_health_alert)+(SELECT count(*) FROM alert_read)+(SELECT count(*) FROM alert_acknowledgement)+(SELECT count(*) FROM alert_transition)+(SELECT count(*) FROM alert_command)",Map.of(),Long.class);
    if(count!=0)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Legacy alerts require separate reviewed migration");
  }
  private String scope(Actor a,MapSqlParameterSource p){if(a.roles().contains("PLATFORM_ADMIN"))return "TRUE";if(a.estates().isEmpty())return "FALSE";p.addValue("estates",a.estates());return "a.estate_id IN (:estates)";}
  private static java.sql.Timestamp stamp(Instant value){return value==null?null:java.sql.Timestamp.from(value);}
  private static Instant instant(java.sql.ResultSet r,String name)throws java.sql.SQLException{var value=r.getTimestamp(name);return value==null?null:value.toInstant();}
  private static final String SELECT="SELECT a.*, (SELECT acknowledged_at FROM privacy_ack k WHERE k.alert_id=a.id AND k.attention_version=a.attention_version) ack_at FROM privacy_alert a ";
  private PrivacyAlert row(java.sql.ResultSet r,int n)throws java.sql.SQLException{return PrivacyState.read(r.getString("body"),instant(r,"ack_at"),instant(r,"inactive_since"),instant(r,"last_history_expired_at"));}
  public PrivacyAlert get(UUID id,Actor actor,boolean lock){
    if(lock){transaction();db.getJdbcTemplate().execute("SET LOCAL lock_timeout='3s'");}
    var p=new MapSqlParameterSource("id",id);
    var rows=db.query(SELECT+"WHERE a.id=:id AND "+scope(actor,p)+(lock?" FOR UPDATE OF a":""),p,this::row);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Alert not found");
    var a=rows.getFirst();actor.authorize(a.source().estateId(),Permission.READ);return a;
  }
  public PrivacyAlert forIncident(UUID id){transaction();var rows=db.query(SELECT+"WHERE a.incident_id=:id FOR UPDATE OF a",Map.of("id",id),this::row);return rows.isEmpty()?null:rows.getFirst();}
  public void current(PrivacyAlert a){
    var v=db.queryForObject("SELECT version FROM crop_health_incident WHERE id=:id",Map.of("id",a.source().incidentId()),Long.class);
    if(v!=a.source().version())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Privacy source requires reviewed reconciliation");
  }
  private void currentEstate(UUID estate){Long n=db.queryForObject("SELECT count(*) FROM privacy_alert a JOIN crop_health_incident i ON i.id=a.incident_id WHERE a.estate_id=:estate AND a.source_version<>i.version",Map.of("estate",estate),Long.class);if(n!=0)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Privacy source requires reviewed reconciliation");}
  private MapSqlParameterSource who(UUID id,Identity who){return new MapSqlParameterSource("id",id).addValue("issuer",who.issuer()).addValue("subject",who.subject());}
  public void identityLock(Identity who){transaction();db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:identity,0))",Map.of("identity",who.issuer()+"\n"+who.subject()),Object.class);}
  public PrivacyCommandRules.Removal removal(Identity who,Instant now){
    var rows=db.query("SELECT key_cutoff,completed_at FROM privacy_removal WHERE issuer=:issuer AND subject=:subject AND (expires_at IS NULL OR expires_at>:now)",who(null,who).addValue("now",stamp(now)),(r,n)->new PrivacyCommandRules.Removal(instant(r,"key_cutoff"),r.getTimestamp("completed_at")==null));return rows.isEmpty()?null:rows.getFirst();
  }
  public ReadMarker ownRead(PrivacyAlert a,Identity who,Instant now){
    if(a.readExpired(now))return null;
    var rows=db.query("SELECT x.attention_version,x.read_at FROM privacy_read x JOIN privacy_alert a ON a.id=x.alert_id WHERE x.alert_id=:id AND x.issuer=:issuer AND x.subject=:subject AND x.attention_version>a.read_expired_through AND NOT EXISTS(SELECT 1 FROM privacy_removal m WHERE m.issuer=x.issuer AND m.subject=x.subject AND x.read_at<=m.key_cutoff)",who(a.id(),who),(r,n)->new ReadMarker(a.id(),who,r.getLong(1),r.getTimestamp(2).toInstant()));return rows.isEmpty()?null:rows.getFirst();
  }
  public void saveRead(PrivacyAlert a,Identity who,Instant now){transaction();db.update("INSERT INTO privacy_read(alert_id,issuer,subject,attention_version,read_at) VALUES(:id,:issuer,:subject,:attention,:at) ON CONFLICT(alert_id,issuer,subject) DO UPDATE SET attention_version=EXCLUDED.attention_version,read_at=EXCLUDED.read_at WHERE privacy_read.attention_version<EXCLUDED.attention_version OR privacy_read.read_at<=COALESCE((SELECT key_cutoff FROM privacy_removal WHERE issuer=:issuer AND subject=:subject),'-infinity'::timestamptz)",who(a.id(),who).addValue("attention",a.attentionVersion()).addValue("at",stamp(now)));}
  public void save(PrivacyAlert.Change change,PrivacyAlert previous){
    transaction();var a=change.alert();if(change.transition()==null)return;
    var s=a.source();var p=new MapSqlParameterSource("id",a.id()).addValue("incident",s.incidentId()).addValue("estate",s.estateId()).addValue("block",s.blockId())
      .addValue("source",s.version()).addValue("version",a.alertVersion()).addValue("attention",a.attentionVersion()).addValue("state",a.state().name()).addValue("ack",a.acknowledged())
      .addValue("at",stamp(a.attentionAt())).addValue("inactive",stamp(a.inactiveSince())).addValue("history",stamp(a.lastHistoryExpiredAt())).addValue("body",stringify(PrivacyState.root(a)));
    if(previous==null)db.update("INSERT INTO privacy_alert(id,incident_id,estate_id,block_id,source_version,alert_version,attention_version,state,acknowledged,attention_at,inactive_since,last_history_expired_at,body) VALUES(:id,:incident,:estate,:block,:source,:version,:attention,:state,:ack,:at,:inactive,:history,CAST(:body AS jsonb))",p);
    else {
      p.addValue("expected",previous.alertVersion()).addValue("readThrough",change.expireReads()?previous.attentionVersion():0).addValue("historyThrough",change.expireHistory()?previous.alertVersion():0);
      int n=db.update("UPDATE privacy_alert SET source_version=:source,alert_version=:version,attention_version=:attention,state=:state,acknowledged=:ack,attention_at=:at,inactive_since=:inactive,last_history_expired_at=:history,read_expired_through=GREATEST(read_expired_through,:readThrough),history_expired_through=GREATEST(history_expired_through,:historyThrough),body=CAST(:body AS jsonb) WHERE id=:id AND alert_version=:expected",p);conflict(n==1,"Concurrent privacy revision");
    }
    var t=change.transition();var evidence=AlertState.transition(t);evidence.remove("acknowledgedBy");
    p.addValue("kind",t.kind().name()).addValue("evidence",stringify(evidence));
    db.update("INSERT INTO privacy_evidence(alert_id,alert_version,attention_version,kind,evidence) VALUES(:id,:version,:attention,:kind,CAST(:evidence AS jsonb))",p);
    if(t.kind()==TransitionKind.ACKNOWLEDGED)db.update("INSERT INTO privacy_ack(alert_id,attention_version,issuer,subject,acknowledged_at) VALUES(:id,:attention,:issuer,:subject,:at)",who(a.id(),t.acknowledgedBy()).addValue("attention",a.attentionVersion()).addValue("at",stamp(t.at())));
  }
  public record SavedReceipt(UUID alertId,String path,String hash,String body,Instant expiresAt,long attentionVersion) {}
  public long expiredReadThrough(UUID id){return db.queryForObject("SELECT read_expired_through FROM privacy_alert WHERE id=:id",Map.of("id",id),Long.class);}
  public SavedReceipt receipt(Identity who,UUID key){
    var rows=db.query("SELECT alert_id,path,request_hash,response_body,expires_at,attention_version FROM privacy_command WHERE issuer=:issuer AND subject=:subject AND idempotency_key=:key",who(null,who).addValue("key",key),(r,n)->new SavedReceipt(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getTimestamp(5).toInstant(),r.getLong(6)));return rows.isEmpty()?null:rows.getFirst();
  }
  public void receipt(PrivacyAlert a,Identity who,UUID key,String path,String hash,String body,Instant now){transaction();var expires=PrivacyCommandRules.receiptDeadline(key,now,a.deadline(AlertPrivacyPolicy.Data.PERSONAL_READ));
    db.update("INSERT INTO privacy_command(issuer,subject,idempotency_key,alert_id,path,request_hash,attention_version,response_body,completed_at,expires_at) VALUES(:issuer,:subject,:key,:id,:path,:hash,:attention,:body,:now,:expires)",who(a.id(),who).addValue("key",key).addValue("path",path).addValue("hash",hash).addValue("attention",a.attentionVersion()).addValue("body",body).addValue("now",stamp(now)).addValue("expires",stamp(expires)));
  }
  public Page<Map<String,Object>> list(Filter f,Actor actor,Instant now){
    actor.authorize(f.estateId(),Permission.READ);currentEstate(f.estateId());
    var p=who(null,actor.identity()).addValue("estate",f.estateId()).addValue("state",f.state().name()).addValue("now",stamp(now)).addValue("limit",f.size()).addValue("offset",(long)f.page()*f.size());
    String where="a.estate_id=:estate AND a.state=:state";
    if(f.blockId()!=null){where+=" AND a.block_id=:block";p.addValue("block",f.blockId());}
    if(f.severity()!=null){where+=" AND a.body->>'severity'=:severity";p.addValue("severity",f.severity().name());}
    if(f.unacknowledgedOnly())where+=" AND NOT a.acknowledged";
    if(f.unreadOnly())where+=" AND (a.inactive_since IS NULL OR a.inactive_since+interval '30 days'>:now) AND NOT EXISTS(SELECT 1 FROM privacy_read r WHERE r.alert_id=a.id AND r.issuer=:issuer AND r.subject=:subject AND r.attention_version=a.attention_version AND r.attention_version>a.read_expired_through AND NOT EXISTS(SELECT 1 FROM privacy_removal m WHERE m.issuer=r.issuer AND m.subject=r.subject AND r.read_at<=m.key_cutoff))";
    long count=db.queryForObject("SELECT count(*) FROM privacy_alert a WHERE "+where,p,Long.class);
    var rows=db.query(SELECT+"WHERE "+where+" ORDER BY a.attention_at DESC,a.id DESC LIMIT :limit OFFSET :offset",p,this::row).stream().map(a->PrivacyState.view(a,ownRead(a,actor.identity(),now),now)).toList();return new Page<>(rows,f.page(),f.size(),count,now);
  }
  public Counts counts(UUID estate,Actor actor,Instant now){
    var all=list(new Filter(estate,null,State.ACTIVE,null,false,false,0,1),actor,now);
    var unread=list(new Filter(estate,null,State.ACTIVE,null,true,false,0,1),actor,now);
    var unack=list(new Filter(estate,null,State.ACTIVE,null,false,true,0,1),actor,now);
    return new Counts(all.totalElements(),unread.totalElements(),unack.totalElements(),now);
  }
  public Map<String,Object> history(PrivacyAlert raw,Actor actor,int page,int size,Instant now){
    actor.authorize(raw.source().estateId(),Permission.HISTORY);var a=raw.expire(now);boolean expired=a.historyExpired(now);
    var p=new MapSqlParameterSource("id",a.id()).addValue("limit",size).addValue("offset",(long)page*size);
    String from=" FROM privacy_evidence e JOIN privacy_alert a ON a.id=e.alert_id LEFT JOIN privacy_ack k ON k.alert_id=e.alert_id AND k.attention_version=e.attention_version WHERE e.alert_id=:id AND e.alert_version>a.history_expired_through";
    long count=expired?0:db.queryForObject("SELECT count(*)"+from,p,Long.class);
    List<Map<String,Object>> rows=expired?List.of():db.query("SELECT e.evidence::text,k.issuer,k.subject"+from+" ORDER BY e.alert_version DESC LIMIT :limit OFFSET :offset",p,(r,n)->{
      @SuppressWarnings("unchecked") Map<String,Object> value=new tools.jackson.databind.ObjectMapper().readValue(r.getString(1),Map.class);
      value.put("acknowledgedBy",value.get("kind").equals("ACKNOWLEDGED")?fields("issuer",r.getString(2),"subject",r.getString(3)):null);return value;});
    return fields("content",rows,"page",page,"size",size,"totalElements",count,"generatedAt",time(now),"retentionState",expired?"EXPIRED":a.lastHistoryExpiredAt()==null?"COMPLETE":"PARTIAL","lastExpiredAt",a.lastHistoryExpiredAt()==null?null:time(a.lastHistoryExpiredAt()));
  }
}
