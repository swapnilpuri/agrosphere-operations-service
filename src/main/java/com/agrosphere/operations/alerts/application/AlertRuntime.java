package com.agrosphere.operations.alerts.application;

import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import static com.agrosphere.operations.crophealth.infrastructure.CropHealthState.*;
import com.agrosphere.operations.alerts.domain.AlertRules.Actor;
import com.agrosphere.operations.alerts.domain.CropHealthAlert;
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
import tools.jackson.databind.json.JsonMapper;

@Service
public class AlertRuntime implements RuleChanges {
  private final JdbcAlertStore store;private final Access access;private final JwtCropHealthIdentity identity;
  private final PrivacyAlertRuntime privacy;
  private final boolean generated;private final Set<String> subjects;
  private static final tools.jackson.databind.ObjectMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
  public AlertRuntime(JdbcAlertStore store,Access access,JwtCropHealthIdentity identity,PrivacyAlertRuntime privacy,
      @Value("${operations.alerts.generated-test-mode:false}") boolean generated,
      @Value("${operations.alerts.generated-subjects:}") String subjects){
    this.privacy=privacy;this.store=store;this.access=access;this.identity=identity;this.generated=generated;
    this.subjects=subjects.isEmpty()?Set.of():Set.copyOf(Arrays.asList(subjects.split(",",-1)));
    if(generated&&(this.subjects.isEmpty()||this.subjects.contains("")))throw new IllegalStateException("Generated alert test subjects required");
  }
  private Actor actor(Permission permission){
    var a=identity.current();var jwt=access.authentication().getToken();
    var actor=new Actor(new Identity(jwt.getIssuer().toString(),a.subject()),a.roles(),a.estates());
    Set<String> roles=switch(permission){case READ->Set.of("PLATFORM_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER","FIELD_SUPERVISOR","AUDITOR");
      case MANAGER->Set.of("PLATFORM_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER");case HISTORY->Set.of("PLATFORM_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER","AUDITOR");};
    if(Collections.disjoint(actor.roles(),roles))throw new Denied("Alert permission denied");
    if(privacy.enabled())privacy.retired(permission);
    if(!generated)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Alerts disabled pending subject-data policy");
    if(!subjects.contains(actor.identity().subject()))throw new Denied("Generated alert test identity required");
    return actor;
  }
  @Override public void acceptedIncidentRevision(Source before,Source after,Instant now,UUID correlationId){
    if(privacy.enabled()){privacy.acceptedIncidentRevision(before,after,now,correlationId);return;}
    if(!generated)return;
    var who=actor(Permission.READ);who.authorize(after.estateId(),Permission.READ);
    var previous=store.forIncident(after.incidentId());
    var change=CropHealthAlert.evaluate(UUID.randomUUID(),previous,before,after,now,correlationId);
    if(change.transition()!=null)store.save(change.alert(),previous,change.transition());
  }
  private Instant now(){return commandTime(Clock.systemUTC());}
  private UUID correlation(){String raw=org.slf4j.MDC.get("correlationId");return raw==null?UUID.randomUUID():UUID.fromString(raw);}
  private Map<String,Object> page(Page<?> p){return fields("content",p.content(),"page",p.page(),"size",p.size(),"totalElements",p.totalElements(),"generatedAt",time(p.generatedAt()));}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object list(Filter f){var a=actor(Permission.READ);return page(store.list(f,a,now()));}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object counts(UUID estate){var a=actor(Permission.READ);var c=store.counts(estate,a,now());return fields("activeTotal",c.activeTotal(),"activeUnreadForMe",c.activeUnreadForMe(),"activeUnacknowledged",c.activeUnacknowledged(),"generatedAt",time(c.generatedAt()));}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object get(UUID id){var who=actor(Permission.READ);var a=store.get(id,who,false);store.current(a);return AlertState.view(a,store.ownRead(id,who.identity()));}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object history(UUID id,int page,int size){AlertPorts.pageBounds(page,size);var a=actor(Permission.HISTORY);return page(store.history(id,a,page,size,now()));}
  public static SortedMap<String,Long> command(String body,boolean ack){
    require(body!=null&&body.length()<=4096,"Alert command too large");
    try{
      var n=JSON.readTree(body);require(n!=null&&n.isObject(),"Object command required");var keys=ack?Set.of("attentionVersion","expectedAlertVersion"):Set.of("attentionVersion");
      var out=new TreeMap<String,Long>();for(var e:n.properties()){
        require(keys.contains(e.getKey())&&e.getValue().isNumber(),"Unknown/noninteger alert field");
        long v=e.getValue().decimalValue().toBigIntegerExact().longValueExact();require(v>=1,"Positive alert version required");out.put(e.getKey(),v);
      }require(out.keySet().equals(keys),"Missing alert version");return out;
    }catch(tools.jackson.core.JacksonException|ArithmeticException e){throw new Invalid("Malformed alert command/version");}
  }
  private static String hash(String path,Map<String,Long> input){
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("POST\n"+path+"\n"+stringify(input)).getBytes(StandardCharsets.UTF_8)));}
    catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }
  @Transactional public String change(UUID id,boolean ack,String body,UUID key){
    var permission=ack?Permission.MANAGER:Permission.READ;var actor=actor(permission);var input=command(body,ack);
    String path="/api/v1/alerts/"+id+(ack?"/acknowledgements":"/read"),hash=hash(path,input);
    var known=store.get(id,actor,false);actor.authorize(known.source().estateId(),permission);
    var replay=store.reserve(actor,key,known,permission,path,hash);if(replay.isPresent())return replay.get();
    var current=store.get(id,actor,true);store.current(current);var own=store.ownRead(id,actor.identity());var now=now();
    if(ack){var change=current.acknowledge(actor,input.get("expectedAlertVersion"),input.get("attentionVersion"),own,now,correlation());
      store.save(change.alert(),current,change.transition());store.saveRead(change.ownRead());current=change.alert();own=change.ownRead();
    }else {own=current.markRead(actor,input.get("attentionVersion"),own,now);store.saveRead(own);}
    String result=stringify(AlertState.view(current,own));store.receipt(actor,key,result);return result;
  }
}
