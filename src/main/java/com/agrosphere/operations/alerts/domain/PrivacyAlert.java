package com.agrosphere.operations.alerts.domain;

import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.alerts.domain.AlertRules.Actor;
import java.time.*;
import java.util.*;

/** Non-actor root for the privacy store. Personal attribution belongs in expiring separate rows. */
public record PrivacyAlert(UUID id, Source source, long alertVersion, long attentionVersion,
    Instant raisedAt, Instant attentionAt, Instant updatedAt, boolean acknowledged,
    Instant acknowledgedAt, Instant inactiveSince, Instant lastHistoryExpiredAt) {
  public PrivacyAlert {
    Objects.requireNonNull(id);Objects.requireNonNull(source);
    require(alertVersion>=1&&attentionVersion>=1&&attentionVersion<=alertVersion,"Invalid privacy versions");
    precise(raisedAt);precise(attentionAt);precise(updatedAt);
    require(!attentionAt.isBefore(raisedAt)&&!updatedAt.isBefore(attentionAt)&&!source.observedAt().isAfter(updatedAt),"Privacy chronology");
    require(source.qualifies()==(inactiveSince==null),"Inactive transition time required");
    if(inactiveSince!=null){precise(inactiveSince);require(!inactiveSince.isBefore(raisedAt)&&!inactiveSince.isAfter(updatedAt),"Inactive chronology");}
    require(acknowledged||acknowledgedAt==null,"No acknowledgement time without acknowledgement");
    if(acknowledgedAt!=null){precise(acknowledgedAt);require(!acknowledgedAt.isBefore(attentionAt)&&!acknowledgedAt.isAfter(updatedAt),"Ack chronology");}
    if(source.qualifies()&&acknowledged)require(acknowledgedAt!=null,"Active acknowledgement attribution missing");
    if(lastHistoryExpiredAt!=null){precise(lastHistoryExpiredAt);require(!lastHistoryExpiredAt.isBefore(raisedAt),"History expiry chronology");}
  }
  public State state(){return source.qualifies()?State.ACTIVE:State.INACTIVE;}
  public Instant deadline(AlertPrivacyPolicy.Data data){return inactiveSince==null?null:inactiveSince.plus(Duration.ofDays(data==AlertPrivacyPolicy.Data.PERSONAL_READ?30:365));}
  public boolean readExpired(Instant now){Instant end=deadline(AlertPrivacyPolicy.Data.PERSONAL_READ);return end!=null&&!now.isBefore(end);}
  public boolean historyExpired(Instant now){Instant end=deadline(AlertPrivacyPolicy.Data.AUDIT_HISTORY);return end!=null&&!now.isBefore(end);}
  public PrivacyAlert expire(Instant now){
    if(!historyExpired(now))return this;
    Instant end=deadline(AlertPrivacyPolicy.Data.AUDIT_HISTORY);
    return new PrivacyAlert(id,source,alertVersion,attentionVersion,raisedAt,attentionAt,updatedAt,acknowledged,null,inactiveSince,
        lastHistoryExpiredAt==null||lastHistoryExpiredAt.isBefore(end)?end:lastHistoryExpiredAt);
  }
  public record Change(PrivacyAlert alert,CropHealthAlert.Transition transition,boolean expireReads,boolean expireHistory) {}
  private static CropHealthAlert.Transition transition(PrivacyAlert a,TransitionKind kind,State before,Instant now,UUID correlation,Identity actor){
    return new CropHealthAlert.Transition(a.id,a.alertVersion,a.source.version(),a.attentionVersion,kind,before,a.state(),now,correlation,actor);
  }
  public static Change evaluate(UUID id,PrivacyAlert previous,Source before,Source after,Instant now,UUID correlation){
    Objects.requireNonNull(after);Objects.requireNonNull(correlation);precise(now);
    require(!after.observedAt().isAfter(now),"Future source");
    if(previous!=null&&after.version()==previous.source.version()){
      conflict(after.equals(previous.source),"Equal-version source conflict");
      return new Change(previous.expire(now),null,previous.readExpired(now),previous.historyExpired(now));
    }
    if(before==null)conflict(after.version()==0,"Missing previous source");
    else {before.sameIdentity(after);conflict(before.version()<Long.MAX_VALUE&&after.version()==before.version()+1,"Source gap/reorder");}
    if(previous==null){
      if(!after.qualifies())return new Change(null,null,false,false);
      var a=new PrivacyAlert(id,after,1,1,now,now,now,false,null,null,null);
      return new Change(a,transition(a,TransitionKind.RAISED,null,now,correlation,null),false,false);
    }
    conflict(before!=null&&previous.source.equals(before),"Alert/source mismatch");
    conflict(!now.isBefore(previous.updatedAt),"Reversed command time");
    boolean expireRead=previous.readExpired(now),expireHistory=previous.historyExpired(now);
    var old=previous.expire(now);
    boolean reactivated=old.state()==State.INACTIVE&&after.qualifies();
    boolean escalated=old.state()==State.ACTIVE&&after.qualifies()&&old.source.severity()==Severity.HIGH&&after.severity()==Severity.CRITICAL;
    boolean attention=reactivated||escalated;
    Instant inactive=after.qualifies()?null:old.inactiveSince==null?now:old.inactiveSince;
    var a=new PrivacyAlert(old.id,after,next(old.alertVersion),attention?next(old.attentionVersion):old.attentionVersion,
        old.raisedAt,attention?now:old.attentionAt,now,attention?false:old.acknowledged,attention?null:old.acknowledgedAt,inactive,old.lastHistoryExpiredAt);
    TransitionKind kind=reactivated?TransitionKind.REACTIVATED:escalated?TransitionKind.ESCALATED:
        old.state()==State.ACTIVE&&!after.qualifies()?TransitionKind.DEACTIVATED:TransitionKind.SOURCE_UPDATED;
    return new Change(a,transition(a,kind,old.state(),now,correlation,null),expireRead,expireHistory);
  }
  public Change acknowledge(Actor actor,long expectedAlert,long expectedAttention,Instant now,UUID correlation){
    actor.authorize(source.estateId(),Permission.MANAGER);precise(now);
    conflict(state()==State.ACTIVE&&!acknowledged,"Inactive or already acknowledged");
    conflict(alertVersion==expectedAlert&&attentionVersion==expectedAttention,"Stale acknowledgement");
    conflict(!now.isBefore(updatedAt),"Reversed command time");
    var a=new PrivacyAlert(id,source,next(alertVersion),attentionVersion,raisedAt,attentionAt,now,true,now,null,lastHistoryExpiredAt);
    return new Change(a,transition(a,TransitionKind.ACKNOWLEDGED,state(),now,correlation,actor.identity()),false,false);
  }
}
