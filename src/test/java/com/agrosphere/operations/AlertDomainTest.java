package com.agrosphere.operations;

import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import static org.junit.jupiter.api.Assertions.*;
import com.agrosphere.operations.alerts.domain.AlertRules;
import com.agrosphere.operations.alerts.domain.CropHealthAlert;
import com.agrosphere.operations.alerts.application.AlertPorts;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AlertDomainTest {
  final UUID incident=UUID.randomUUID(),estate=UUID.randomUUID(),block=UUID.randomUUID(),id=UUID.randomUUID(),correlation=UUID.randomUUID();
  final Instant now=Instant.parse("2026-10-31T12:00:00Z");
  final Identity identity=new Identity("https://identity.example.invalid/realms/generated","generated-manager");
  AlertRules.Actor actor(String...roles) { return new AlertRules.Actor(identity,Set.of(roles),Set.of(estate)); }
  Source source(long version,Severity severity,Status status) {
    return new Source(incident,estate,block,ZoneId.of("Etc/UTC"),Provenance.SIMULATED,version,now.minusSeconds(3600),Category.TREE_DAMAGE,severity,status);
  }
  CropHealthAlert initial() { return CropHealthAlert.evaluate(id,null,null,source(0,Severity.HIGH,Status.OPEN),now,correlation).alert(); }
  CropHealthAlert nextSource(CropHealthAlert a,Severity severity,Status status) {
    return CropHealthAlert.evaluate(id,a,a.source(),source(a.source().version()+1,severity,status),a.updatedAt().plusSeconds(1),correlation).alert();
  }
  @Test void exhaustiveHumanSeverityStatusPredicate() {
    for(var severity:Severity.values()) for(var status:Status.values()) {
      boolean eligible=(severity==Severity.HIGH||severity==Severity.CRITICAL)&&(status==Status.OPEN||status==Status.IN_REVIEW);
      var result=CropHealthAlert.evaluate(id,null,null,source(0,severity,status),now,correlation);
      assertEquals(eligible,result.alert()!=null);
      assertEquals(eligible,result.transition()!=null);
      if(eligible) { assertEquals(1,result.alert().attentionVersion());assertEquals(1,result.alert().alertVersion()); }
    }
  }
  @Test void onlySimulatedInputsEnterDomainIncludingHydration() {
    for(var provenance:Provenance.values()) if(provenance!=Provenance.SIMULATED)
      assertThrows(Denied.class,()->new Source(incident,estate,block,ZoneId.of("Etc/UTC"),provenance,0,now,Category.TREE_DAMAGE,Severity.HIGH,Status.OPEN));
    assertThrows(Denied.class,()->new Source(incident,estate,block,ZoneId.of("Etc/UTC"),null,0,now,Category.TREE_DAMAGE,Severity.HIGH,Status.OPEN));
  }
  @Test void firstFutureCommandCanQualifyOldIncidentWithoutBackfill() {
    var before=source(17,Severity.LOW,Status.OPEN);var after=source(18,Severity.HIGH,Status.IN_REVIEW);
    var result=CropHealthAlert.evaluate(id,null,before,after,now,correlation);
    assertEquals(18,result.alert().source().version());assertEquals(1,result.alert().attentionVersion());
    assertEquals(now,result.alert().raisedAt());assertTrue(result.alert().source().observedAt().isBefore(result.alert().raisedAt()));
  }
  @Test void sourceRetryHasNoDuplicateEffectAndEqualVersionConflictIsRejected() {
    var a=initial();var replay=CropHealthAlert.evaluate(UUID.randomUUID(),a,null,a.source(),now,correlation);
    assertSame(a,replay.alert());assertNull(replay.transition());
    assertThrows(Conflict.class,()->CropHealthAlert.evaluate(id,a,null,source(0,Severity.CRITICAL,Status.OPEN),now,correlation));
  }
  @Test void staleGapAndMismatchedSourceStateFailClosed() {
    var a=initial();
    assertThrows(Conflict.class,()->CropHealthAlert.evaluate(id,a,a.source(),source(2,Severity.HIGH,Status.OPEN),now,correlation));
    assertThrows(Conflict.class,()->CropHealthAlert.evaluate(id,null,null,source(1,Severity.HIGH,Status.OPEN),now,correlation));
    assertThrows(Conflict.class,()->CropHealthAlert.evaluate(id,a,source(0,Severity.LOW,Status.OPEN),source(1,Severity.HIGH,Status.OPEN),now,correlation));
    var later=nextSource(a,Severity.HIGH,Status.IN_REVIEW);
    assertThrows(Conflict.class,()->CropHealthAlert.evaluate(id,later,null,a.source(),now,correlation));
  }
  @Test void immutableIdentityCannotBeMovedToAnotherEstateOrBlock() {
    var before=source(0,Severity.HIGH,Status.OPEN);
    var after=new Source(incident,UUID.randomUUID(),block,before.estateTimezone(),Provenance.SIMULATED,1,before.observedAt(),before.category(),before.severity(),before.status());
    assertThrows(Conflict.class,()->CropHealthAlert.evaluate(id,initial(),before,after,now,correlation));
  }
  @Test void ordinaryEditsAndTriageRetainAttentionAndAcknowledgement() {
    var a=initial();var ack=a.acknowledge(actor("ESTATE_MANAGER"),1,1,null,now,correlation).alert();
    var triaged=nextSource(ack,Severity.HIGH,Status.IN_REVIEW);
    assertEquals(1,triaged.attentionVersion());assertEquals(3,triaged.alertVersion());
    assertSame(ack.acknowledgement(),triaged.acknowledgement());assertEquals(1,triaged.source().version());
    assertEquals(0,a.source().version());assertNull(a.acknowledgement());
  }
  @Test void escalationInvalidatesSharedAckAndEarlierOwnRead() {
    var a=initial();var result=a.acknowledge(actor("ESTATE_MANAGER"),1,1,null,now,correlation);
    var escalated=nextSource(result.alert(),Severity.CRITICAL,Status.OPEN);
    assertEquals(2,escalated.attentionVersion());assertNull(escalated.acknowledgement());assertTrue(escalated.unread(result.ownRead()));
    assertEquals(now.plusSeconds(1),escalated.attentionAt());assertEquals(a.id(),escalated.id());
    assertThrows(Conflict.class,()->escalated.markRead(actor("ESTATE_MANAGER"),1,result.ownRead(),now.plusSeconds(2)));
  }
  @Test void criticalToHighReductionRetainsAttentionAndAck() {
    var a=nextSource(initial(),Severity.CRITICAL,Status.OPEN);
    var ack=a.acknowledge(actor("ESTATE_MANAGER"),a.alertVersion(),a.attentionVersion(),null,a.updatedAt(),correlation).alert();
    var reduced=nextSource(ack,Severity.HIGH,Status.IN_REVIEW);
    assertEquals(ack.attentionVersion(),reduced.attentionVersion());assertSame(ack.acknowledgement(),reduced.acknowledgement());
  }
  @Test void belowThresholdAndResolutionDeactivateThenReactivationResets() {
    for(var status:List.of(Status.OPEN,Status.RESOLVED)) {
      var a=initial();var ack=a.acknowledge(actor("ESTATE_MANAGER"),1,1,null,now,correlation).alert();
      var inactive=nextSource(ack,status==Status.OPEN?Severity.MEDIUM:Severity.HIGH,status);
      assertEquals(State.INACTIVE,inactive.state());assertNotNull(inactive.inactiveReason());assertNotNull(inactive.acknowledgement());
      var active=nextSource(inactive,Severity.HIGH,Status.OPEN);
      assertEquals(2,active.attentionVersion());assertNull(active.acknowledgement());assertEquals(id,active.id());
    }
  }
  @Test void voidRetainsEvidenceAndPreventsAcknowledgement() {
    var a=nextSource(initial(),Severity.HIGH,Status.VOIDED);
    assertEquals(InactiveReason.VOIDED,a.inactiveReason());
    assertThrows(Conflict.class,()->a.acknowledge(actor("ESTATE_MANAGER"),a.alertVersion(),a.attentionVersion(),null,a.updatedAt(),correlation));
    assertNotNull(a.markRead(actor("AUDITOR"),a.attentionVersion(),null,a.updatedAt()));
  }
  @Test void explicitOwnReadDoesNotAcknowledgeAndFreshKeyNoopRetainsTime() {
    var a=initial();var marker=a.markRead(actor("FIELD_SUPERVISOR"),1,null,now);
    assertFalse(a.unread(marker));assertNull(a.acknowledgement());assertEquals(1,a.alertVersion());
    assertSame(marker,a.markRead(actor("FIELD_SUPERVISOR"),1,marker,now.plusSeconds(5)));
    var other=new AlertRules.Actor(new Identity(identity.issuer(),"generated-other"),Set.of("ESTATE_MANAGER"),Set.of(estate));
    assertThrows(Conflict.class,()->a.markRead(other,1,marker,now));
  }
  @Test void sharedFirstAcknowledgementIsNotSourceResolution() {
    var a=initial();var result=a.acknowledge(actor("ESTATE_MANAGER"),1,1,null,now,correlation);
    assertEquals(Status.OPEN,result.alert().source().status());assertEquals(2,result.alert().alertVersion());
    assertFalse(result.alert().unread(result.ownRead()));assertEquals(identity,result.transition().acknowledgedBy());
    assertThrows(Conflict.class,()->result.alert().acknowledge(actor("ESTATE_MANAGER"),2,1,result.ownRead(),now,correlation));
  }
  @Test void displayedVersionRaceCannotAcknowledgeAfterSourceChange() {
    var original=initial();var changed=nextSource(original,Severity.HIGH,Status.IN_REVIEW);
    assertThrows(Conflict.class,()->changed.acknowledge(actor("ESTATE_MANAGER"),1,1,null,changed.updatedAt(),correlation));
    var winner=original.acknowledge(actor("ESTATE_MANAGER"),1,1,null,now,correlation).alert();
    assertThrows(Conflict.class,()->winner.acknowledge(actor("ESTATE_ADMIN"),1,1,null,now,correlation));
  }
  @Test void positiveRoleMatrixAndScopeAreIndependent() {
    for(String role:List.of("PLATFORM_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER","FIELD_SUPERVISOR","AUDITOR")) {
      actor(role).authorize(estate,Permission.READ);
      if(role.equals("FIELD_SUPERVISOR")||role.equals("AUDITOR")) assertThrows(Denied.class,()->actor(role).authorize(estate,Permission.MANAGER));
      else actor(role).authorize(estate,Permission.MANAGER);
    }
    for(String role:List.of("AGRONOMIST","VIEWER","PLANTATION_ADMIN","FIELD_WORKER","UNKNOWN"))
      for(var permission:Permission.values()) assertThrows(Denied.class,()->actor(role).authorize(estate,permission));
    assertThrows(Denied.class,()->actor("ESTATE_MANAGER").authorize(UUID.randomUUID(),Permission.READ));
    actor("VIEWER","ESTATE_MANAGER").authorize(estate,Permission.MANAGER);
    assertThrows(Denied.class,()->actor("PLATFORM_ADMIN").authorize(null,Permission.READ));
    assertThrows(Denied.class,()->initial().acknowledge(actor("FIELD_SUPERVISOR"),1,1,null,now,correlation));
  }
  @Test void immutableRoleAndEstateCopiesPreventLaterPrivilegeChange() {
    var roles=new HashSet<>(Set.of("AUDITOR"));var estates=new HashSet<>(Set.of(estate));
    var a=new AlertRules.Actor(identity,roles,estates);roles.add("ESTATE_MANAGER");estates.clear();
    a.authorize(estate,Permission.HISTORY);assertThrows(Denied.class,()->a.authorize(estate,Permission.MANAGER));
  }
  @Test void replayRequiresCurrentPermissionsOwnerIssuerAndOriginalCommand() {
    UUID key=UUID.randomUUID();String path="/api/v1/alerts/"+id+"/acknowledgements",hash="a".repeat(64);
    var receipt=new AlertPorts.Receipt(identity,key,estate,Permission.MANAGER,path,hash,"{}");
    receipt.authorizeReplay(actor("ESTATE_MANAGER"),key,path,hash);
    assertThrows(Denied.class,()->receipt.authorizeReplay(actor("AUDITOR"),key,path,hash));
    var other=new AlertRules.Actor(new Identity("https://other.example.invalid",identity.subject()),Set.of("ESTATE_MANAGER"),Set.of(estate));
    assertThrows(Denied.class,()->receipt.authorizeReplay(other,key,path,hash));
    assertThrows(Conflict.class,()->receipt.authorizeReplay(actor("ESTATE_MANAGER"),key,path,"b".repeat(64)));
    assertThrows(Invalid.class,()->new AlertPorts.Receipt(identity,key,estate,Permission.READ,path,hash,"{}"));
  }
  @Test void opaqueSubjectsAreNotTrimmedOrParsedAsWorkerUuids() {
    var opaque=new Identity(identity.issuer()," signed opaque subject ");assertEquals(" signed opaque subject ",opaque.subject());
    assertThrows(Invalid.class,()->new Identity(identity.issuer()," "));
    assertThrows(Invalid.class,()->new Identity("not-an-issuer","generated"));
    assertThrows(Invalid.class,()->new Identity("https://user:password@example.invalid","generated"));
  }
  @Test void chronologyAndPrecisionAreCheckedWithoutBackdatingRaisedTime() {
    assertThrows(Invalid.class,()->CropHealthAlert.evaluate(id,null,null,source(0,Severity.HIGH,Status.OPEN),now.minusSeconds(7200),correlation));
    assertThrows(Invalid.class,()->CropHealthAlert.evaluate(id,null,null,source(0,Severity.HIGH,Status.OPEN),now.plusNanos(1),correlation));
    assertThrows(Conflict.class,()->initial().markRead(actor("AUDITOR"),1,null,now.minusSeconds(1)));
    assertEquals(now,AlertRules.commandTime(Clock.fixed(now.plusNanos(999),ZoneOffset.UTC)));
  }
  @Test void pageArithmeticAndVersionOverflowFailRatherThanWrap() {
    AlertPorts.pageBounds(500,20);AlertPorts.pageBounds(10000,1);
    assertThrows(Invalid.class,()->AlertPorts.pageBounds(Integer.MAX_VALUE,100));
    assertThrows(Invalid.class,()->AlertPorts.pageBounds(0,101));
    assertThrows(Conflict.class,()->AlertRules.next(Long.MAX_VALUE));
    var a=initial();var exhausted=new CropHealthAlert(id,a.source(),Long.MAX_VALUE,1,now,now,now,null);
    assertThrows(Conflict.class,()->exhausted.acknowledge(actor("ESTATE_MANAGER"),Long.MAX_VALUE,1,null,now,correlation));
  }
  @Test void queryProjectionsDoNotConfuseOtherUsersReadsOrInventCounts() {
    var a=initial();var marker=a.markRead(actor("AUDITOR"),1,null,now);
    assertFalse(new AlertPorts.View(a,identity,marker).unreadForMe());
    assertTrue(new AlertPorts.View(a,new Identity(identity.issuer(),"generated-other"),null).unreadForMe());
    assertThrows(Conflict.class,()->new AlertPorts.View(a,new Identity(identity.issuer(),"generated-other"),marker));
    assertThrows(Invalid.class,()->new AlertPorts.Counts(1,2,0,now));
    assertThrows(Invalid.class,()->new AlertPorts.Page<>(List.of(a),0,20,0,now));
  }
}
