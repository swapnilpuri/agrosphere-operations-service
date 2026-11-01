package com.agrosphere.operations.alerts.application;

import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import static com.agrosphere.operations.crophealth.infrastructure.CropHealthState.*;
import com.agrosphere.operations.alerts.domain.AlertRules.Actor;
import com.agrosphere.operations.alerts.domain.*;
import com.agrosphere.operations.alerts.infrastructure.*;
import com.agrosphere.operations.alerts.application.AlertPorts.*;
import com.agrosphere.operations.application.Access;
import com.agrosphere.operations.crophealth.infrastructure.JwtCropHealthIdentity;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class PrivacyAlertRuntime {
  private final JdbcPrivacyAlertStore store;private final Access access;private final JwtCropHealthIdentity identity;
  private final boolean enabled;private final Set<String> subjects;
  public PrivacyAlertRuntime(JdbcPrivacyAlertStore store,Access access,JwtCropHealthIdentity identity,
      @Value("${operations.alerts.privacy-generated-test-mode:false}") boolean enabled,
      @Value("${operations.alerts.generated-test-mode:false}") boolean legacy,
      @Value("${operations.alerts.generated-subjects:}") String subjects){
    this.store=store;this.access=access;this.identity=identity;this.enabled=enabled;
    this.subjects=subjects.isEmpty()?Set.of():Set.copyOf(Arrays.asList(subjects.split(",",-1)));
    if(enabled&&(legacy||this.subjects.isEmpty()||this.subjects.contains("")))throw new IllegalStateException("Privacy and legacy modes exclusive; exact generated subjects required");
  }
  public boolean enabled(){return enabled;}
  private Actor actor(Permission permission){
    var a=identity.current();var jwt=access.authentication().getToken();var who=new Actor(new Identity(jwt.getIssuer().toString(),a.subject()),a.roles(),a.estates());
    boolean manager=who.roles().stream().anyMatch(Set.of("PLATFORM_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER")::contains);
    boolean allowed=manager||permission!=Permission.MANAGER&&(who.roles().contains("AUDITOR")||permission==Permission.READ&&who.roles().contains("FIELD_SUPERVISOR"));
    if(!allowed)throw new Denied("Alert permission denied");
    if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Privacy alerts disabled");
    if(!subjects.contains(who.identity().subject()))throw new Denied("Generated privacy subject required");
    store.safeMode();return who;
  }
  public void retired(Permission p){actor(p);throw new PrivacyExpired("ALERT_API_RETIRED");}
  private Instant now(){return commandTime(Clock.systemUTC());}
  private UUID correlation(){String raw=org.slf4j.MDC.get("correlationId");return raw==null?UUID.randomUUID():UUID.fromString(raw);}
  private Map<String,Object> page(Page<?> p){return fields("content",p.content(),"page",p.page(),"size",p.size(),"totalElements",p.totalElements(),"generatedAt",time(p.generatedAt()));}
  public void acceptedIncidentRevision(Source before,Source after,Instant now,UUID correlation){
    if(!enabled)return;var who=actor(Permission.READ);who.authorize(after.estateId(),Permission.READ);
    var prior=store.forIncident(after.incidentId());var change=PrivacyAlert.evaluate(UUID.randomUUID(),prior,before,after,now,correlation);if(change.alert()!=null)store.save(change,prior);
  }
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object list(Filter f){var who=actor(Permission.READ);return page(store.list(f,who,now()));}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object counts(UUID estate){var who=actor(Permission.READ);var c=store.counts(estate,who,now());return fields("activeTotal",c.activeTotal(),"activeUnreadForMe",c.activeUnreadForMe(),"activeUnacknowledged",c.activeUnacknowledged(),"generatedAt",time(c.generatedAt()));}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object get(UUID id){var who=actor(Permission.READ);var a=store.get(id,who,false);store.current(a);var now=now();return PrivacyState.view(a,store.ownRead(a,who.identity(),now),now);}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object history(UUID id,int page,int size){AlertPorts.pageBounds(page,size);var who=actor(Permission.HISTORY);var a=store.get(id,who,false);store.current(a);return store.history(a,who,page,size,now());}
  private static String hash(String path,Map<String,Long> input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("POST\n"+path+"\n"+stringify(input)).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  @Transactional public String change(UUID id,boolean ack,String body,String rawKey){
    var permission=ack?Permission.MANAGER:Permission.READ;var who=actor(permission);
    var known=store.get(id,who,false);who.authorize(known.source().estateId(),permission);
    var input=AlertRuntime.command(body,ack);var decision=PrivacyCommandRules.decide(rawKey,now(),null,null);
    require(decision!=PrivacyCommandRules.Decision.INVALID,"Canonical UUIDv7 key/clock required");UUID key=UUID.fromString(rawKey);
    store.identityLock(who.identity());var current=store.get(id,who,true);store.current(current);Instant now=now();
    String path="/api/v2/alerts/"+id+(ack?"/acknowledgements":"/read"),hash=hash(path,input);
    var receipt=store.receipt(who.identity(),key);
    if(receipt!=null)conflict(receipt.alertId().equals(id)&&receipt.path().equals(path)&&receipt.hash().equals(hash),"Conflicting command key");
    if(receipt!=null&&receipt.attentionVersion()<=store.expiredReadThrough(id))throw new PrivacyExpired("ALERT_COMMAND_EXPIRED");
    Instant deadline=receipt==null?null:receipt.expiresAt();
    Instant readDeadline=current.deadline(AlertPrivacyPolicy.Data.PERSONAL_READ);
    if(deadline!=null&&readDeadline!=null&&readDeadline.isBefore(deadline))deadline=readDeadline;
    decision=PrivacyCommandRules.decide(rawKey,now,deadline,store.removal(who.identity(),now));
    if(decision==PrivacyCommandRules.Decision.EXPIRED)throw new PrivacyExpired("ALERT_COMMAND_EXPIRED");
    require(decision!=PrivacyCommandRules.Decision.INVALID,"Canonical UUIDv7 key/clock required");
    if(!ack&&current.readExpired(now))throw new PrivacyExpired("ALERT_READ_RETENTION_EXPIRED");
    if(decision==PrivacyCommandRules.Decision.REPLAY)return receipt.body();
    conflict(decision==PrivacyCommandRules.Decision.NEW,"Unknown command outcome; refresh before explicit action");
    conflict(input.get("attentionVersion")==current.attentionVersion(),"Stale attention");
    var own=store.ownRead(current,who.identity(),now);
    if(ack){var change=current.acknowledge(who,input.get("expectedAlertVersion"),input.get("attentionVersion"),now,correlation());store.save(change,current);current=change.alert();}
    if(own==null||own.attentionVersion()!=current.attentionVersion()){store.saveRead(current,who.identity(),now);own=new CropHealthAlert.ReadMarker(id,who.identity(),current.attentionVersion(),now);}
    String result=stringify(PrivacyState.view(current,own,now));store.receipt(current,who.identity(),key,path,hash,result,now);return result;
  }
}
