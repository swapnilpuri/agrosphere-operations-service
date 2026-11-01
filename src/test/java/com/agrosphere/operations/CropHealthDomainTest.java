package com.agrosphere.operations;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import static org.junit.jupiter.api.Assertions.*;

import com.agrosphere.operations.crophealth.domain.*;
import com.agrosphere.operations.crophealth.application.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CropHealthDomainTest {
  final UUID estate=UUID.randomUUID(), block=UUID.randomUUID();
  final Instant now=Instant.parse("2026-10-31T12:00:00Z");
  final Context context=new Context(estate,block,ZoneId.of("Africa/Libreville"));
  Actor actor(String... roles) { return new Actor("signed-sub",Set.of(roles),Set.of(estate)); }
  Command command(Actor actor, Instant at) { return new Command(actor,Clock.fixed(at,ZoneOffset.UTC),UUID.randomUUID()); }
  Command manager() { return command(actor("ESTATE_MANAGER"),now); }
  Observation observation(Outcome outcome) { return new Observation(now.minusSeconds(3600),outcome,"SIMULATED inspection"); }
  Report report() { return new Report(now.minusSeconds(3600),Category.TREE_DAMAGE,Severity.LOW,"SIMULATED report","SIMULATED evidence"); }
  CropHealthChanges.ChangeSet followUp() {
    return CropHealthChanges.record(UUID.randomUUID(),UUID.randomUUID(),context,Provenance.SIMULATED,
        observation(Outcome.FOLLOW_UP),report(),null,manager());
  }
  CropIncident direct() {
    return CropHealthChanges.directReport(UUID.randomUUID(),context,Provenance.SIMULATED,report(),null,manager()).incident();
  }
  @Test void clearDoesNotInventIncidentOrHealthOutsideItsObservation() {
    var result=CropHealthChanges.record(UUID.randomUUID(),null,context,Provenance.SIMULATED,observation(Outcome.CLEAR),null,null,manager());
    assertNull(result.incident());assertEquals(1,result.revisions().size());assertEquals(0,result.inspection().audit().version());
    assertThrows(Invalid.class,()->FieldInspection.record(UUID.randomUUID(),UUID.randomUUID(),context,Provenance.SIMULATED,
        observation(Outcome.CLEAR),report(),null,manager()));
  }
  @Test void followUpProducesTwoDistinctLinkedRevisionsForOneTransaction() {
    var result=followUp();assertEquals(2,result.revisions().size());
    assertEquals(result.incident().id(),result.inspection().linkedIncidentId());assertEquals(result.inspection().id(),result.incident().inspectionId());
    assertNotEquals(result.incident().id(),result.inspection().id());assertEquals(Status.OPEN,result.incident().status());
    assertThrows(UnsupportedOperationException.class,()->result.revisions().clear());
  }
  @Test void followUpRequiresReportAndEqualObservationTime() {
    assertThrows(Invalid.class,()->FieldInspection.record(UUID.randomUUID(),UUID.randomUUID(),context,Provenance.SIMULATED,observation(Outcome.FOLLOW_UP),null,null,manager()));
    Report mismatch=new Report(now.minusSeconds(10),Category.TREE_DAMAGE,Severity.LOW,"SIMULATED","SIMULATED");
    assertThrows(Invalid.class,()->FieldInspection.record(UUID.randomUUID(),UUID.randomUUID(),context,Provenance.SIMULATED,observation(Outcome.FOLLOW_UP),mismatch,null,manager()));
  }
  @Test void directReportNeverCreatesInspectionOrWorkerIdentity() { assertNull(direct().inspectionId()); }
  @Test void nonSimulatedMutationsCannotEnterDomainEvenViaHydration() {
    for (var provenance:Provenance.values()) if(provenance!=Provenance.SIMULATED)
      assertThrows(Denied.class,()->CropHealthChanges.directReport(UUID.randomUUID(),context,provenance,report(),null,manager()));
    assertThrows(Denied.class,()->new Attribution(estate,block,context.timezone(),null));
  }
  @Test void positiveRoleMatrixRejectsLegacyUnions() {
    for(String role:List.of("FIELD_WORKER","VIEWER","PLANTATION_ADMIN","UNKNOWN")) {
      var a=actor(role);assertThrows(Denied.class,()->a.read(estate));assertThrows(Denied.class,()->a.manage(estate));
      assertThrows(Denied.class,()->a.history(estate));assertThrows(Denied.class,()->a.create(estate,context.timezone(),now,now,null));
    }
    for(String role:List.of("ESTATE_ADMIN","ESTATE_MANAGER")) { actor(role).manage(estate);actor(role).history(estate); }
    actor("AUDITOR").read(estate);actor("AUDITOR").history(estate);assertThrows(Denied.class,()->actor("AUDITOR").manage(estate));
    actor("FIELD_SUPERVISOR").read(estate);assertThrows(Denied.class,()->actor("FIELD_SUPERVISOR").history(estate));
    assertThrows(Denied.class,()->actor("FIELD_SUPERVISOR").manage(estate));
  }
  @Test void mixedRolesNeverWidenEstateScopeAndPlatformStillNeedsExplicitEstate() {
    var a=actor("PLANTATION_ADMIN","ESTATE_MANAGER");a.manage(estate);
    assertThrows(Denied.class,()->a.manage(UUID.randomUUID()));
    new Actor("admin",Set.of("PLATFORM_ADMIN"),Set.of()).manage(UUID.randomUUID());
    assertThrows(Denied.class,()->new Actor("admin",Set.of("PLATFORM_ADMIN"),Set.of()).read(null));
  }
  @Test void supervisorTodayUsesEstateDateAcrossUtcMidnight() {
    var a=actor("FIELD_SUPERVISOR");Instant at=Instant.parse("2026-10-31T00:30:00Z");
    ZoneId zone=ZoneId.of("America/Chicago");
    a.create(estate,zone,Instant.parse("2026-10-30T12:00:00Z"),at,null);
    assertThrows(Denied.class,()->a.create(estate,zone,Instant.parse("2026-10-29T23:00:00Z"),at,null));
    assertThrows(Invalid.class,()->a.create(estate,zone,at.plusSeconds(1),at,null));
  }
  @Test void managerBackdateRequiresReasonAndDstUsesCanonicalZone() {
    var a=actor("ESTATE_MANAGER");ZoneId zone=ZoneId.of("America/Chicago");
    Instant at=Instant.parse("2026-11-01T08:00:00Z");
    a.create(estate,zone,Instant.parse("2026-11-01T06:30:00Z"),at,null);
    a.create(estate,zone,Instant.parse("2026-11-01T07:30:00Z"),at,null);
    assertThrows(Invalid.class,()->a.create(estate,zone,at.minusSeconds(172800),at,null));
    a.create(estate,zone,at.minusSeconds(172800),at,"SIMULATED backdate");
  }
  @Test void explicitOffsetsNormalizeAndExcessPrecisionIsRejected() {
    assertEquals(now,parseObservation("2026-10-31T13:00:00+01:00"));
    assertThrows(Invalid.class,()->parseObservation("2026-10-31T12:00:00"));
    assertThrows(Invalid.class,()->parseObservation("2026-10-31T12:00:00.1234567Z"));
    assertThrows(Invalid.class,()->parseObservation("2026-02-30T12:00:00Z"));
    assertThrows(Invalid.class,()->new Observation(now.plusNanos(1),Outcome.CLEAR,"SIMULATED"));
  }
  @Test void unicodeNormalizationPreservesInteriorAndOpaqueSubject() {
    assertEquals("é 🌱",text("\u00a0é 🌱\u2003",200));assertEquals(" A  B ",subject(" A  B "));
    assertEquals("🌱".repeat(200),text("🌱".repeat(200),200));
    for(String s:List.of("","  ","x\ny","x\u0085y","x".repeat(201))) assertThrows(Invalid.class,()->text(s,200));
    assertThrows(Invalid.class,()->subject(" "));
  }
  @Test void incidentLifecycleRetainsResolutionAuditAndVoidIsTerminal() {
    var original=direct();var triaged=original.transition(0,Kind.TRIAGE,null,null,"SIMULATED triage",manager());
    assertEquals(Status.IN_REVIEW,triaged.status());assertEquals(Status.OPEN,original.status());
    var resolved=triaged.transition(1,Kind.RESOLVE,now.minusSeconds(1),"SIMULATED resolution","SIMULATED closure",manager());
    assertEquals(Status.RESOLVED,resolved.status());
    var reopened=resolved.transition(2,Kind.REOPEN,null,null,"SIMULATED revisit",manager());
    assertNull(reopened.resolvedAt());assertNull(reopened.resolutionNote());assertNotNull(reopened.earliestRetainedResolutionAt());
    var voided=reopened.transition(3,Kind.VOID,null,null,"SIMULATED void",manager());
    assertThrows(Conflict.class,()->voided.transition(4,Kind.REOPEN,null,null,"SIMULATED",manager()));
    assertThrows(Conflict.class,()->voided.edit(4,report(),true,"SIMULATED",manager()));
  }
  @Test void illegalLifecycleTransitionsAndStaleVersionsConflict() {
    var open=direct();assertThrows(Conflict.class,()->open.transition(0,Kind.REOPEN,null,null,"SIMULATED",manager()));
    assertThrows(Conflict.class,()->open.transition(1,Kind.TRIAGE,null,null,"SIMULATED",manager()));
    var triaged=open.transition(0,Kind.TRIAGE,null,null,"SIMULATED",manager());
    assertThrows(Conflict.class,()->triaged.transition(1,Kind.TRIAGE,null,null,"SIMULATED",manager()));
    assertThrows(Invalid.class,()->open.transition(0,Kind.AMEND,null,null,"SIMULATED",manager()));
    assertThrows(Invalid.class,()->open.transition(0,Kind.TRIAGE,now,"SIMULATED","SIMULATED",manager()));
  }
  @Test void resolutionCannotPrecedeObservationOrExceedNow() {
    var open=direct();
    for(Instant at:List.of(report().observedAt().minusSeconds(1),now.plusSeconds(1)))
      assertThrows(Invalid.class,()->open.transition(0,Kind.RESOLVE,at,"SIMULATED","SIMULATED",manager()));
    assertThrows(Invalid.class,()->open.transition(0,Kind.RESOLVE,now," ","SIMULATED",manager()));
  }
  @Test void correctionCannotInvalidateAnyRetainedClosureAfterReopenAndResolveAgain() {
    var resolved=direct().transition(0,Kind.RESOLVE,now.minusSeconds(600),"SIMULATED","SIMULATED",manager());
    var reopened=resolved.transition(1,Kind.REOPEN,null,null,"SIMULATED",manager());
    var resolvedAgain=reopened.transition(2,Kind.RESOLVE,now.minusSeconds(100),"SIMULATED","SIMULATED",manager());
    assertEquals(now.minusSeconds(600),resolvedAgain.earliestRetainedResolutionAt());
    Report afterFirst=new Report(now.minusSeconds(200),Category.PEST_SIGNS,Severity.HIGH,"SIMULATED revised","SIMULATED");
    assertThrows(Invalid.class,()->resolvedAgain.edit(3,afterFirst,true,"SIMULATED",manager()));
    assertThrows(Invalid.class,()->reopened.edit(2,afterFirst,true,"SIMULATED",manager()));
  }
  @Test void amendmentsCannotEditResolvedAndCorrectionsRetainStatus() {
    var resolved=direct().transition(0,Kind.RESOLVE,now,"SIMULATED","SIMULATED",manager());
    Report replacement=new Report(report().observedAt(),Category.PEST_SIGNS,Severity.HIGH,"SIMULATED revised","SIMULATED revised");
    assertThrows(Conflict.class,()->resolved.edit(1,replacement,false,"SIMULATED",manager()));
    var changed=resolved.edit(1,replacement,true,"SIMULATED",manager());assertEquals(Status.RESOLVED,changed.status());assertEquals(resolved.resolvedAt(),changed.resolvedAt());
    assertThrows(Conflict.class,()->direct().edit(0,report(),true,"SIMULATED",manager()));
  }
  @Test void linkedIncidentMustBeVoidedBeforeClearOrInspectionVoid() {
    var pair=followUp();var inspection=pair.inspection();var incident=pair.incident();
    assertThrows(Conflict.class,()->inspection.correct(0,0L,observation(Outcome.CLEAR),incident,null,null,"SIMULATED",manager()));
    assertThrows(Conflict.class,()->inspection.voidInspection(0,0L,incident,"SIMULATED",manager()));
    var voided=CropHealthChanges.transition(incident,inspection,0,0L,Kind.VOID,null,null,"SIMULATED",manager()).incident();
    var clear=CropHealthChanges.correctInspection(inspection,voided,0,1L,observation(Outcome.CLEAR),null,null,"SIMULATED",manager());
    assertEquals(incident.id(),clear.inspection().linkedIncidentId());assertEquals(Outcome.CLEAR,clear.inspection().observation().outcome());
    assertThrows(Conflict.class,()->clear.inspection().correct(1,1L,observation(Outcome.FOLLOW_UP),voided,null,null,"SIMULATED",manager()));
    assertEquals(InspectionState.VOIDED,clear.inspection().voidInspection(1,1L,voided,"SIMULATED",manager()).state());
  }
  @Test void firstFollowUpCorrectionCreatesOnlyFirstIncidentAtomically() {
    var clear=CropHealthChanges.record(UUID.randomUUID(),null,context,Provenance.SIMULATED,observation(Outcome.CLEAR),null,null,manager()).inspection();
    var changed=CropHealthChanges.correctInspection(clear,null,0,null,observation(Outcome.FOLLOW_UP),UUID.randomUUID(),report(),"SIMULATED concern",manager());
    assertEquals(2,changed.revisions().size());assertEquals(1,changed.inspection().audit().version());assertEquals(0,changed.incident().audit().version());
    assertEquals(Kind.REPORT,changed.revisions().get(1).commandKind());
  }
  @Test void pairedVersionsAndIdentityAreMandatoryAndInspectionIsNeverSilentlyEdited() {
    var pair=followUp();var i=pair.inspection();var c=pair.incident();
    assertThrows(Invalid.class,()->CropHealthChanges.transition(c,null,0,null,Kind.TRIAGE,null,null,"SIMULATED",manager()));
    assertThrows(Invalid.class,()->CropHealthChanges.transition(c,i,0,null,Kind.TRIAGE,null,null,"SIMULATED",manager()));
    assertThrows(Conflict.class,()->CropHealthChanges.transition(c,i,0,1L,Kind.TRIAGE,null,null,"SIMULATED",manager()));
    assertThrows(Invalid.class,()->i.validatePair(direct(),0L));
    var change=CropHealthChanges.transition(c,i,0,0L,Kind.TRIAGE,null,null,"SIMULATED",manager());
    assertSame(i,change.inspection());assertEquals(1,change.revisions().size());assertEquals(0,i.audit().version());
  }
  @Test void inspectionVoidedIsTerminalAndNoOpCorrectionsConflict() {
    var clear=CropHealthChanges.record(UUID.randomUUID(),null,context,Provenance.SIMULATED,observation(Outcome.CLEAR),null,null,manager()).inspection();
    assertThrows(Conflict.class,()->clear.correct(0,null,observation(Outcome.CLEAR),null,null,null,"SIMULATED",manager()));
    var voided=clear.voidInspection(0,null,null,"SIMULATED",manager());
    assertThrows(Conflict.class,()->voided.voidInspection(1,null,null,"SIMULATED",manager()));
  }
  @Test void retainedContextsDoNotRequireActiveMasterOrGeometryToCorrect() {
    var c=direct();Report changed=new Report(report().observedAt(),Category.PEST_SIGNS,Severity.MEDIUM,"SIMULATED revised","SIMULATED");
    assertEquals(context.timezone(),c.edit(0,changed,true,"SIMULATED",manager()).attribution().estateTimezone());
    assertThrows(DateTimeException.class,()->ZoneId.of("Invented/Zone"));
  }
  @Test void auditAndRevisionRejectMismatchAndClockRollback() {
    var c=direct();assertThrows(Invalid.class,()->new Revision(c,Kind.TRIAGE,"SIMULATED",now,"signed-sub",UUID.randomUUID()));
    assertThrows(Invalid.class,()->new Revision(c,Kind.REPORT,null,now,"other-sub",UUID.randomUUID()));
    assertThrows(Invalid.class,()->c.transition(0,Kind.TRIAGE,null,null,"SIMULATED",command(actor("ESTATE_MANAGER"),now.minusSeconds(1))));
  }
  @Test void queryBoundsPreventUnscopedOrUnboundedReads() {
    assertThrows(NullPointerException.class,()->new CropHealthPorts.Filter(null,null,null,null,Provenance.SIMULATED,0,25));
    assertThrows(Invalid.class,()->new CropHealthPorts.Filter(estate,null,now,null,Provenance.SIMULATED,0,25));
    assertThrows(Invalid.class,()->new CropHealthPorts.Filter(estate,null,now,now,Provenance.SIMULATED,0,25));
    assertThrows(Invalid.class,()->new CropHealthPorts.Filter(estate,null,null,null,Provenance.SIMULATED,Integer.MAX_VALUE,100));
    var f=new CropHealthPorts.Filter(estate,null,null,null,Provenance.SIMULATED,0,25);
    assertEquals(Set.of(Status.OPEN,Status.IN_REVIEW),new CropHealthPorts.IncidentFilter(f,null,null,null).statuses());
    assertThrows(Invalid.class,()->new CropHealthPorts.IncidentFilter(f,null,null,Set.of()));
  }
  @Test void inspectionAndIncidentCannotReuseTheSameAggregateIdentity() {
    UUID id=UUID.randomUUID();
    assertThrows(Invalid.class,()->FieldInspection.record(id,id,context,Provenance.SIMULATED,
        observation(Outcome.FOLLOW_UP),report(),null,manager()));
  }
  @Test void commandReceiptRequiresSuccessCanonicalHashAndCreateLocation() {
    String hash="a".repeat(64);UUID key=UUID.randomUUID();
    var receipt=new CropHealthPorts.Receipt(" signed-sub ",key,"POST","/api/v1/incidents",hash,1,201,"{}","/api/v1/incidents/example");
    assertEquals(" signed-sub ",receipt.actor());
    assertThrows(Invalid.class,()->new CropHealthPorts.Receipt("actor",key,"POST","/api/v1/incidents",hash,1,201,"{}",null));
    assertThrows(Invalid.class,()->new CropHealthPorts.Receipt("actor",key,"POST","/api/v1/incidents",hash,1,409,"{}",null));
    assertThrows(Invalid.class,()->new CropHealthPorts.Receipt("actor",key,"POST","/api/v1/workers",hash,1,200,"{}",null));
    assertThrows(Invalid.class,()->new CropHealthPorts.Receipt("actor",key,"POST","/api/v1/incidents","wrong",1,200,"{}",null));
  }
}
