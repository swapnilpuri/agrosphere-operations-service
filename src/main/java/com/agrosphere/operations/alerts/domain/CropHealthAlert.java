package com.agrosphere.operations.alerts.domain;

import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import java.time.*;
import java.util.*;

/** Immutable local alert state. No report text, contact address, account linkage or delivery flag. */
public record CropHealthAlert(UUID id, Source source, long alertVersion, long attentionVersion,
    Instant raisedAt, Instant attentionAt, Instant updatedAt, Acknowledgement acknowledgement) {
  public CropHealthAlert {
    Objects.requireNonNull(id); Objects.requireNonNull(source);
    require(alertVersion>=1 && attentionVersion>=1 && attentionVersion<=alertVersion,"Invalid alert versions");
    precise(raisedAt); precise(attentionAt); precise(updatedAt);
    require(!attentionAt.isBefore(raisedAt) && !updatedAt.isBefore(attentionAt) && !source.observedAt().isAfter(updatedAt),"Alert chronology");
    if(acknowledgement!=null) {
      require(acknowledgement.alertId().equals(id) && acknowledgement.attentionVersion()==attentionVersion,"Acknowledgement identity mismatch");
      require(!acknowledgement.at().isBefore(attentionAt) && !acknowledgement.at().isAfter(updatedAt),"Acknowledgement chronology");
    }
  }
  public State state() { return source.qualifies()?State.ACTIVE:State.INACTIVE; }
  public InactiveReason inactiveReason() { return source.qualifies()?null:source.inactiveReason(); }
  public record Acknowledgement(UUID alertId,long attentionVersion,Identity actor,Instant at) {
    public Acknowledgement { Objects.requireNonNull(alertId);Objects.requireNonNull(actor);precise(at);require(attentionVersion>=1,"Invalid acknowledgement attention"); }
  }
  public record ReadMarker(UUID alertId,Identity actor,long attentionVersion,Instant at) {
    public ReadMarker { Objects.requireNonNull(alertId);Objects.requireNonNull(actor);precise(at);require(attentionVersion>=1,"Invalid read attention"); }
  }
  public record Transition(UUID alertId,long alertVersion,long sourceVersion,long attentionVersion,
      TransitionKind kind,State beforeState,State afterState,Instant at,UUID correlationId,Identity acknowledgedBy) {
    public Transition {
      Objects.requireNonNull(alertId);Objects.requireNonNull(kind);Objects.requireNonNull(afterState);Objects.requireNonNull(correlationId);precise(at);
      require(alertVersion>=1 && sourceVersion>=0 && attentionVersion>=1 && attentionVersion<=alertVersion,"Invalid transition versions");
      require((kind==TransitionKind.RAISED)==(beforeState==null),"Raised transition needs no prior state");
      require((kind==TransitionKind.ACKNOWLEDGED)==(acknowledgedBy!=null),"Only acknowledgement carries actor");
    }
  }
  public record Evaluation(CropHealthAlert alert,Transition transition) {}
  public record Acknowledged(CropHealthAlert alert,ReadMarker ownRead,Transition transition) {}

  /** Called only for an accepted incident revision, never on bootstrap, event replay or GET. */
  public static Evaluation evaluate(UUID newId,CropHealthAlert previous,Source before,Source after,Instant now,UUID correlationId) {
    Objects.requireNonNull(after);Objects.requireNonNull(correlationId);precise(now);
    require(!after.observedAt().isAfter(now),"Future source observation");
    if(previous!=null && after.version()==previous.source().version()) {
      conflict(after.equals(previous.source()),"Equal-version source conflict");
      return new Evaluation(previous,null);
    }
    if(before==null) conflict(after.version()==0,"Missing previous source revision");
    else { before.sameIdentity(after);conflict(before.version()<Long.MAX_VALUE && after.version()==before.version()+1,"Source revision gap/reorder"); }
    if(previous!=null) {
      conflict(before!=null && previous.source().equals(before),"Alert/source revision mismatch");
      conflict(!now.isBefore(previous.updatedAt()),"Reversed command time");
    }
    if(previous==null) {
      if(!after.qualifies()) return new Evaluation(null,null);
      var raised=new CropHealthAlert(Objects.requireNonNull(newId),after,1,1,now,now,now,null);
      return new Evaluation(raised,transition(raised,TransitionKind.RAISED,null,now,correlationId,null));
    }
    boolean reactivated=previous.state()==State.INACTIVE && after.qualifies();
    boolean escalated=previous.state()==State.ACTIVE && after.qualifies()
        && previous.source().severity()==Severity.HIGH && after.severity()==Severity.CRITICAL;
    boolean attention=reactivated||escalated;
    var changed=new CropHealthAlert(previous.id(),after,next(previous.alertVersion()),
        attention?next(previous.attentionVersion()):previous.attentionVersion(),previous.raisedAt(),
        attention?now:previous.attentionAt(),now,attention?null:previous.acknowledgement());
    TransitionKind kind=reactivated?TransitionKind.REACTIVATED:escalated?TransitionKind.ESCALATED:
        previous.state()==State.ACTIVE && !after.qualifies()?TransitionKind.DEACTIVATED:TransitionKind.SOURCE_UPDATED;
    return new Evaluation(changed,transition(changed,kind,previous.state(),now,correlationId,null));
  }
  private static Transition transition(CropHealthAlert alert,TransitionKind kind,State before,Instant now,UUID correlation,Identity actor) {
    return new Transition(alert.id(),alert.alertVersion(),alert.source().version(),alert.attentionVersion(),kind,before,alert.state(),now,correlation,actor);
  }
  public ReadMarker markRead(AlertRules.Actor actor,long expectedAttention,ReadMarker prior,Instant now) {
    actor.authorize(source.estateId(),Permission.READ);precise(now);
    conflict(expectedAttention==attentionVersion,"Stale attention");conflict(!now.isBefore(updatedAt),"Reversed read time");
    if(prior!=null) {
      conflict(prior.alertId().equals(id) && prior.actor().equals(actor.identity()),"Read marker identity mismatch");
      conflict(prior.attentionVersion()<=attentionVersion && !now.isBefore(prior.at()),"Invalid retained read marker");
      if(prior.attentionVersion()==attentionVersion) return prior;
    }
    return new ReadMarker(id,actor.identity(),attentionVersion,now);
  }
  public Acknowledged acknowledge(AlertRules.Actor actor,long expectedVersion,long expectedAttention,ReadMarker priorRead,Instant now,UUID correlationId) {
    actor.authorize(source.estateId(),Permission.MANAGER);precise(now);Objects.requireNonNull(correlationId);
    conflict(expectedVersion==alertVersion && expectedAttention==attentionVersion,"Stale alert/attention");
    conflict(state()==State.ACTIVE && acknowledgement==null,"Inactive/already acknowledged");
    var ownRead=markRead(actor,expectedAttention,priorRead,now);
    var ack=new Acknowledgement(id,attentionVersion,actor.identity(),now);
    var changed=new CropHealthAlert(id,source,next(alertVersion),attentionVersion,raisedAt,attentionAt,now,ack);
    return new Acknowledged(changed,ownRead,transition(changed,TransitionKind.ACKNOWLEDGED,state(),now,correlationId,actor.identity()));
  }
  public boolean unread(ReadMarker marker) {
    if(marker==null) return true;
    conflict(marker.alertId().equals(id) && marker.attentionVersion()<=attentionVersion,"Invalid read marker");
    return marker.attentionVersion()<attentionVersion;
  }
}
