package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;
import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import static com.agrosphere.operations.alerts.domain.PrivacyCommandRules.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.alerts.domain.*;
import com.agrosphere.operations.alerts.domain.AlertRules.Actor;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PrivacyAlertTest {
  final Instant now=Instant.parse("2026-10-31T12:00:00Z");
  final UUID id=UUID.randomUUID(),incident=UUID.randomUUID(),estate=UUID.randomUUID(),block=UUID.randomUUID(),correlation=UUID.randomUUID();
  final Actor manager=new Actor(new Identity("https://issuer.example.invalid","generated-manager"),Set.of("ESTATE_MANAGER"),Set.of(estate));
  Instant days(long n){return now.plus(Duration.ofDays(n));}
  Source source(long version,Severity severity,Status status){return new Source(incident,estate,block,ZoneId.of("Africa/Libreville"),Provenance.SIMULATED,version,now.minusSeconds(60),Category.TREE_DAMAGE,severity,status);}
  PrivacyAlert initial(){return PrivacyAlert.evaluate(id,null,null,source(0,Severity.HIGH,Status.OPEN),now,correlation).alert();}
  PrivacyAlert.Change revise(PrivacyAlert a,Severity severity,Status status,Instant at){return PrivacyAlert.evaluate(id,a,a.source(),source(a.source().version()+1,severity,status),at,correlation);}
  UUID key(Instant at){return new UUID((at.toEpochMilli()<<16)|0x7000L,0x8000000000000001L);}
  @Test void canonicalKeyAndFutureSkewBoundaries(){
    for(long delta:new long[]{-300,0,300})assertEquals(Decision.NEW,decide(key(now.plusSeconds(delta)).toString(),now,null,null));
    assertEquals(Decision.UNKNOWN,decide(key(now.minusSeconds(301)).toString(),now,null,null));
    assertEquals(Decision.INVALID,decide(key(now.plusSeconds(301)).toString(),now,null,null));
    assertEquals(Decision.INVALID,decide(UUID.randomUUID().toString(),now,null,null));
    assertEquals(Decision.INVALID,decide(key(now).toString().toUpperCase(Locale.ROOT),now,null,null));
  }
  @Test void expiredKeyIsNeverNewEvenAfterReceiptDeletion(){
    UUID key=key(days(-30));
    assertEquals(Decision.EXPIRED,decide(key.toString(),now,null,null));
    assertEquals(Decision.EXPIRED,decide(key.toString(),now,days(1),null));
  }
  @Test void receiptLifetimeDoesNotMoveWithReplayOrFutureSkew(){
    UUID k=key(now.plusSeconds(300));
    assertEquals(days(30),receiptDeadline(k,now,null));
    assertEquals(days(10),receiptDeadline(k,now,days(10)));
    assertEquals(Decision.REPLAY,decide(k.toString(),now,now.plusNanos(1),null));
    assertEquals(Decision.EXPIRED,decide(k.toString(),now,now,null));
  }
  @Test void removalCoversPreviouslyAdmittedFutureKeysAndPendingNewCommands(){
    UUID k=key(now.plusSeconds(300));var removal=removal(now.plusSeconds(10));
    assertEquals(Decision.EXPIRED,decide(k.toString(),now.plusSeconds(10),null,new Removal(removal.cutoff(),false)));
    Instant later=removal.cutoff().plusSeconds(1);
    assertEquals(Decision.EXPIRED,decide(key(later).toString(),later,null,removal));
    assertEquals(Decision.NEW,decide(key(later).toString(),later,null,new Removal(removal.cutoff(),false)));
  }
  @Test void inactiveEditsDoNotResetReadOrHistoryClocks(){
    var inactive=revise(initial(),Severity.HIGH,Status.RESOLVED,now.plusSeconds(1)).alert();
    var edited=revise(inactive,Severity.MEDIUM,Status.RESOLVED,days(10)).alert();
    assertEquals(inactive.inactiveSince(),edited.inactiveSince());
    assertFalse(edited.readExpired(inactive.inactiveSince().plus(Duration.ofDays(30)).minusNanos(1)));
    assertTrue(edited.readExpired(inactive.inactiveSince().plus(Duration.ofDays(30))));
    assertTrue(edited.historyExpired(inactive.inactiveSince().plus(Duration.ofDays(365))));
  }
  @Test void reopeningBeforeExpiryCancelsClocksButStartsFreshAttention(){
    var inactive=revise(initial(),Severity.HIGH,Status.RESOLVED,now.plusSeconds(1)).alert();
    var reopened=revise(inactive,Severity.HIGH,Status.OPEN,days(20));
    assertFalse(reopened.expireReads());assertNull(reopened.alert().inactiveSince());
    assertEquals(inactive.attentionVersion()+1,reopened.alert().attentionVersion());
    assertFalse(reopened.alert().acknowledged());
  }
  @Test void reopeningAtExpiryMustRemoveOldCopiesBeforeCancellingClocks(){
    var inactive=revise(initial(),Severity.HIGH,Status.RESOLVED,now.plusSeconds(1)).alert();
    var reopened=revise(inactive,Severity.HIGH,Status.OPEN,inactive.inactiveSince().plus(Duration.ofDays(30)));
    assertTrue(reopened.expireReads());assertFalse(reopened.expireHistory());assertNull(reopened.alert().inactiveSince());
  }
  @Test void expiredAttributionAndHistoryCannotResurrectOnReopening(){
    var ack=initial().acknowledge(manager,1,1,now.plusSeconds(1),correlation).alert();
    var inactive=revise(ack,Severity.HIGH,Status.RESOLVED,now.plusSeconds(2)).alert();
    Instant end=inactive.inactiveSince().plus(Duration.ofDays(365));
    var expired=inactive.expire(end);
    assertTrue(expired.acknowledged());assertNull(expired.acknowledgedAt());
    assertEquals(ack.alertVersion()+1,expired.alertVersion());
    assertEquals(end,expired.lastHistoryExpiredAt());
    var reopened=revise(inactive,Severity.HIGH,Status.OPEN,end);
    assertTrue(reopened.expireReads());assertTrue(reopened.expireHistory());
    assertEquals(end,reopened.alert().lastHistoryExpiredAt());assertFalse(reopened.alert().acknowledged());
  }
  @Test void sameRevisionIsIdempotentButMismatchesGapsAndReversedTimesFail(){
    var a=initial();assertNull(PrivacyAlert.evaluate(id,a,null,a.source(),now,correlation).transition());
    assertThrows(Conflict.class,()->PrivacyAlert.evaluate(id,a,a.source(),source(2,Severity.HIGH,Status.OPEN),now,correlation));
    assertThrows(Conflict.class,()->PrivacyAlert.evaluate(id,a,a.source(),source(0,Severity.CRITICAL,Status.OPEN),now,correlation));
    assertThrows(Conflict.class,()->revise(a,Severity.HIGH,Status.OPEN,now.minusSeconds(1)));
  }
  @Test void firstAckRetainsNoActorInRootAndChecksVersionsAndPermissions(){
    var a=initial();var change=a.acknowledge(manager,1,1,now.plusSeconds(1),correlation);
    assertTrue(change.alert().acknowledged());assertEquals(manager.identity(),change.transition().acknowledgedBy());
    assertThrows(Conflict.class,()->change.alert().acknowledge(manager,2,1,now.plusSeconds(2),correlation));
    assertThrows(Conflict.class,()->a.acknowledge(manager,2,1,now.plusSeconds(1),correlation));
    var reader=new Actor(manager.identity(),Set.of("FIELD_SUPERVISOR"),Set.of(estate));
    assertThrows(Denied.class,()->a.acknowledge(reader,1,1,now.plusSeconds(1),correlation));
    assertTrue(Arrays.stream(PrivacyAlert.class.getRecordComponents()).noneMatch(c->c.getType()==Identity.class));
  }
  @Test void escalationResetsAttentionWhileSameSeverityAndDowngradeDoNot(){
    var a=initial();var same=revise(a,Severity.HIGH,Status.IN_REVIEW,now.plusSeconds(1)).alert();
    assertEquals(a.attentionVersion(),same.attentionVersion());
    var critical=revise(same,Severity.CRITICAL,Status.IN_REVIEW,now.plusSeconds(2)).alert();
    assertEquals(a.attentionVersion()+1,critical.attentionVersion());
    assertEquals(critical.attentionVersion(),revise(critical,Severity.HIGH,Status.OPEN,now.plusSeconds(3)).alert().attentionVersion());
  }
}
