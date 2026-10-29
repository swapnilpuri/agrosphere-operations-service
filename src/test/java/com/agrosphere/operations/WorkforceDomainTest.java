package com.agrosphere.operations;

import static com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import static org.junit.jupiter.api.Assertions.*;

import com.agrosphere.operations.workforce.application.WorkforcePorts.*;
import com.agrosphere.operations.workforce.domain.*;
import com.agrosphere.operations.workforce.domain.WorkerRegistration.ActivePeriod;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkforceDomainTest {
  final UUID estate = UUID.randomUUID(), otherEstate = UUID.randomUUID(), workerId = UUID.randomUUID();
  final UUID periodId = UUID.randomUUID(), division = UUID.randomUUID();
  final Instant now = Instant.parse("2026-10-29T12:00:00Z");
  final LocalDate today = LocalDate.of(2026, 10, 29);
  final EstateContext master = new EstateContext(estate, ZoneId.of("Africa/Libreville"));

  Actor actor(String... roles) { return new Actor("signed-sub", Set.of(roles), Set.of(estate)); }
  Command command(Actor actor) { return at(actor, now); }
  Command at(Actor actor, Instant instant) { return new Command(actor, Clock.fixed(instant, ZoneOffset.UTC), UUID.randomUUID()); }
  Command manager() { return command(actor("ESTATE_MANAGER")); }
  WorkerRegistration worker() {
    return WorkerRegistration.register(workerId, periodId, master, " simulated-01 ", "SIMULATED — Example",
        division, today.minusDays(2), Provenance.SIMULATED, manager());
  }
  AttendanceDay day() {
    return AttendanceDay.record(UUID.randomUUID(), worker(), today, AttendanceStatus.PRESENT, master, null,
        command(actor("FIELD_SUPERVISOR")));
  }

  @Test void registrationIsIndependentOfLoginAndPreservesCanonicalIdentity() {
    var worker = worker();
    assertEquals("SIMULATED-01", worker.workerCode());
    assertEquals(workerId, worker.id()); assertEquals(estate, worker.estateId());
    assertEquals(0, worker.audit().version());
    assertEquals("signed-sub", worker.audit().createdBy());
    assertTrue(worker.periodOn(today).isPresent());
    assertTrue(worker.periodOn(today.minusDays(3)).isEmpty());
    assertFalse(worker.workerCode().equals(worker.audit().createdBy()));
  }

  @Test void immutableCollectionsCannotBeMutatedByCallers() {
    var worker = worker();
    assertThrows(UnsupportedOperationException.class, () -> worker.activePeriods().clear());
    var source = new HashSet<>(Set.of("ESTATE_MANAGER"));
    var actor = new Actor("sub", source, Set.of(estate)); source.clear();
    actor.requireManager(estate);
    assertThrows(UnsupportedOperationException.class, () -> actor.roles().clear());
  }

  @Test void unicodeNamesAndReasonsUseCodepointsWithoutChangingSignedSubject() {
    String unicode = "🧑".repeat(200);
    var changed = worker().edit(0, "  " + unicode + "  ", false, null, "SIMULATED correction", manager());
    assertEquals(unicode, changed.displayName());
    assertEquals(" opaque-sub ", new Actor(" opaque-sub ", Set.of(), Set.of()).subject());
    assertEquals("OPAQUE-sub", subject("OPAQUE-sub"));
    assertThrows(Invalid.class, () -> subject("\u00a0"));
    assertThrows(Invalid.class, () -> worker().edit(0, "🧑".repeat(201), false, null, "SIMULATED correction", manager()));
  }

  @Test void workerCodesAreAsciiAndNamesNeverSupplyIdentity() {
    for (String invalid : List.of("", " ", "SIMULATED A", "é", "ß", "A".repeat(65), "A/B"))
      assertThrows(Invalid.class, () -> WorkerRegistration.register(workerId, periodId, master, invalid,
          "SIMULATED name", null, today, Provenance.SIMULATED, manager()));
    assertEquals("SIMULATED_name", text(" SIMULATED_name ", 200));
  }

  @Test void profileEditsRetainCodeAndEstateAndRequireReasonAndVersion() {
    var worker = worker();
    var changed = worker.edit(0, "SIMULATED revised", true, null, "SIMULATED clear division", manager());
    assertNull(changed.divisionId()); assertEquals(worker.workerCode(), changed.workerCode());
    assertEquals(worker.estateId(), changed.estateId()); assertEquals(worker.id(), changed.id());
    assertEquals(1, changed.audit().version()); assertEquals(0, worker.audit().version());
    assertThrows(Conflict.class, () -> changed.edit(0, "SIMULATED stale", false, null, "SIMULATED reason", manager()));
    assertThrows(Invalid.class, () -> worker.edit(0, worker.displayName(), false, null, "SIMULATED no-op", manager()));
    assertThrows(Invalid.class, () -> worker.edit(0, "SIMULATED changed", false, null, " ", manager()));
  }

  @Test void futureRegistrationAndReactivationAreRejected() {
    assertThrows(Invalid.class, () -> WorkerRegistration.register(workerId, periodId, master, "SIMULATED-1", "SIMULATED",
        null, today.plusDays(1), Provenance.SIMULATED, manager()));
    var closed = worker().closePeriod(0, periodId, today, "SIMULATED close", Set.of(), master.timezone(), manager());
    assertThrows(Invalid.class, () -> closed.openPeriod(1, UUID.randomUUID(), today.plusDays(1), "SIMULATED open", master, manager()));
  }

  @Test void exclusivePeriodsCanCloseAndReopenAtTheSameBoundary() {
    var closed = worker().closePeriod(0, periodId, today, "SIMULATED close", Set.of(today.minusDays(1)), master.timezone(), manager());
    assertTrue(closed.periodOn(today).isEmpty()); assertTrue(closed.periodOn(today.minusDays(1)).isPresent());
    var opened = closed.openPeriod(1, UUID.randomUUID(), today, "SIMULATED open", master, manager());
    assertEquals(2, opened.activePeriods().size()); assertTrue(opened.periodOn(today).isPresent());
    assertEquals(2, opened.audit().version());
    assertThrows(Conflict.class, () -> opened.openPeriod(2, UUID.randomUUID(), today, "SIMULATED duplicate", master, manager()));
  }

  @Test void periodCloseMustPreserveAllRetainedNonvoidedAttendance() {
    assertThrows(Conflict.class, () -> worker().closePeriod(0, periodId, today, "SIMULATED close", Set.of(today), master.timezone(), manager()));
    var closed = worker().closePeriod(0, periodId, today.plusDays(1), "SIMULATED through today", Set.of(today), master.timezone(), manager());
    assertTrue(closed.periodOn(today).isPresent());
    assertThrows(Invalid.class, () -> worker().closePeriod(0, periodId, today.plusDays(2), "SIMULATED future", Set.of(), master.timezone(), manager()));
    assertThrows(Invalid.class, () -> worker().closePeriod(0, periodId, today.minusDays(2), "SIMULATED empty", Set.of(), master.timezone(), manager()));
  }

  @Test void reactivationCannotOverlapOldPeriodsOrChangeEstate() {
    var closed = worker().closePeriod(0, periodId, today, "SIMULATED close", Set.of(), master.timezone(), manager());
    assertThrows(Conflict.class, () -> closed.openPeriod(1, UUID.randomUUID(), today.minusDays(1), "SIMULATED overlap", master, manager()));
    assertThrows(Invalid.class, () -> closed.openPeriod(1, UUID.randomUUID(), today, "SIMULATED foreign", new EstateContext(otherEstate, ZoneOffset.UTC), manager()));
  }

  @Test void rehydratedPeriodsRejectDuplicateIdsOverlapsAndUnprovenClosure() {
    var worker = worker();
    assertThrows(Invalid.class, () -> new ActivePeriod(periodId, today, today.plusDays(1), 0, null));
    assertThrows(Invalid.class, () -> new WorkerRegistration(workerId, estate, "SIMULATED-1", "SIMULATED", null,
        Provenance.SIMULATED, List.of(worker.activePeriods().getFirst(),worker.activePeriods().getFirst()), worker.audit()));
    assertThrows(Conflict.class, () -> new WorkerRegistration(workerId, estate, "SIMULATED-1", "SIMULATED", null,
        Provenance.SIMULATED, List.of(worker.activePeriods().getFirst(),new ActivePeriod(UUID.randomUUID(),today,null,0,null)), worker.audit()));
  }

  @Test void roleMatrixUsesPositiveFeaturePermissions() {
    for (String role : List.of("PLATFORM_ADMIN", "ESTATE_ADMIN", "ESTATE_MANAGER", "FIELD_SUPERVISOR", "AUDITOR", "VIEWER", "FIELD_WORKER", "PLANTATION_ADMIN", "AGRONOMIST")) {
      var actor = actor(role);
      boolean manager = Set.of("PLATFORM_ADMIN", "ESTATE_ADMIN", "ESTATE_MANAGER").contains(role);
      if (manager || Set.of("FIELD_SUPERVISOR", "AUDITOR").contains(role)) assertDoesNotThrow(() -> actor.requireRead(estate));
      else assertThrows(Denied.class, () -> actor.requireRead(estate));
      if (manager) assertDoesNotThrow(() -> actor.requireManager(estate));
      else assertThrows(Denied.class, () -> actor.requireManager(estate));
      if (manager || role.equals("AUDITOR")) assertDoesNotThrow(() -> actor.requireHistory(estate));
      else assertThrows(Denied.class, () -> actor.requireHistory(estate));
      if (manager || role.equals("FIELD_SUPERVISOR")) assertDoesNotThrow(() -> actor.requireRecord(estate, today, today, null));
      else assertThrows(Denied.class, () -> actor.requireRecord(estate, today, today, null));
    }
  }

  @Test void mixedRolesDoNotGrantGlobalOrWorkerPersonalDataAccess() {
    assertThrows(Denied.class, () -> actor("FIELD_WORKER", "VIEWER").requireRead(estate));
    assertDoesNotThrow(() -> actor("FIELD_SUPERVISOR", "VIEWER").requireRecord(estate, today, today, null));
    assertThrows(Denied.class, () -> actor("ESTATE_MANAGER", "PLANTATION_ADMIN").requireManager(otherEstate));
    assertThrows(Denied.class, () -> actor("PLANTATION_ADMIN").requireManager(estate));
    assertDoesNotThrow(() -> new Actor("platform-sub", Set.of("PLATFORM_ADMIN"), Set.of()).requireManager(otherEstate));
    assertThrows(Denied.class, () -> new Actor("manager-sub", Set.of("ESTATE_MANAGER"), Set.of()).requireRead(estate));
  }

  @Test void supervisorCannotBackdateAndManagerMustExplainPastObservations() {
    var worker = worker();
    assertThrows(Denied.class, () -> AttendanceDay.record(UUID.randomUUID(), worker, today.minusDays(1), AttendanceStatus.PRESENT,
        master, "SIMULATED late entry", command(actor("FIELD_SUPERVISOR"))));
    assertThrows(Invalid.class, () -> AttendanceDay.record(UUID.randomUUID(), worker, today.minusDays(1), AttendanceStatus.PRESENT, master, null, manager()));
    var historical = AttendanceDay.record(UUID.randomUUID(), worker, today.minusDays(1), AttendanceStatus.ABSENT, master, "SIMULATED late entry", manager());
    assertEquals(today.minusDays(1), historical.attribution().attendanceDate());
  }

  @Test void futureInactiveAndForeignEstateObservationsAreRejected() {
    assertThrows(Invalid.class, () -> AttendanceDay.record(UUID.randomUUID(), worker(), today.plusDays(1), AttendanceStatus.PRESENT, master, null, manager()));
    assertThrows(Invalid.class, () -> AttendanceDay.record(UUID.randomUUID(), worker(), today.minusDays(3), AttendanceStatus.PRESENT, master, "SIMULATED inactive", manager()));
    assertThrows(Invalid.class, () -> AttendanceDay.record(UUID.randomUUID(), worker(), today, AttendanceStatus.PRESENT,
        new EstateContext(otherEstate, ZoneOffset.UTC), null, manager()));
    assertThrows(Denied.class, () -> AttendanceDay.record(UUID.randomUUID(), worker(), today, AttendanceStatus.PRESENT, master, null,
        command(new Actor("foreign-sub", Set.of("FIELD_SUPERVISOR"), Set.of(otherEstate)))));
  }

  @Test void absenceIsExplicitAndVoidIsNotAnInitialObservation() {
    for (var status : List.of(AttendanceStatus.PRESENT,AttendanceStatus.ABSENT,AttendanceStatus.EXCUSED))
      assertEquals(status, AttendanceDay.record(UUID.randomUUID(), worker(), today, status, master, null, manager()).status());
    assertThrows(Invalid.class, () -> AttendanceDay.record(UUID.randomUUID(), worker(), today, AttendanceStatus.VOIDED, master, null, manager()));
    assertThrows(Invalid.class, () -> AttendanceDay.record(UUID.randomUUID(), worker(), today, null, master, null, manager()));
  }

  @Test void correctionsPreserveAttributionAndPriorImmutableState() {
    var day = day(); var changed = day.correct(0, AttendanceStatus.EXCUSED, "SIMULATED correction", manager());
    assertEquals(day.attribution(), changed.attribution()); assertEquals(day.id(), changed.id());
    assertEquals(AttendanceStatus.PRESENT, day.status()); assertEquals(1, changed.audit().version());
    assertThrows(Conflict.class, () -> changed.correct(0, AttendanceStatus.ABSENT, "SIMULATED stale", manager()));
    assertThrows(Invalid.class, () -> day.correct(0, AttendanceStatus.PRESENT, "SIMULATED no-op", manager()));
    assertThrows(Denied.class, () -> day.correct(0, AttendanceStatus.ABSENT, "SIMULATED denied", command(actor("FIELD_SUPERVISOR"))));
  }

  @Test void voidAndRerecordReuseDailyIdentityAndRefreshContextOnlyInNewRevision() {
    var worker = worker(); var day = day();
    var voided = day.voidDay(0, "SIMULATED mistaken", manager());
    assertEquals(day.attribution(), voided.attribution()); assertEquals(AttendanceStatus.VOIDED, voided.status());
    assertThrows(Invalid.class, () -> voided.correct(1, AttendanceStatus.ABSENT, "SIMULATED invalid", manager()));
    assertThrows(Invalid.class, () -> voided.voidDay(1, "SIMULATED repeat with new key", manager()));
    var changedWorker = worker.edit(0, "SIMULATED revised", true, null, "SIMULATED profile", manager());
    var rerecorded = voided.rerecord(1, changedWorker, AttendanceStatus.ABSENT, master, "SIMULATED rerecord", manager());
    assertEquals(day.id(), rerecorded.id()); assertEquals(day.attribution().attendanceDate(), rerecorded.attribution().attendanceDate());
    assertNull(rerecorded.attribution().divisionIdSnapshot()); assertEquals(1, rerecorded.attribution().workerVersionSnapshot());
    assertEquals(division, day.attribution().divisionIdSnapshot()); assertEquals(2, rerecorded.audit().version());
  }

  @Test void rerecordRevalidatesIdentityCurrentActivePeriodAndManager() {
    var voided = day().voidDay(0, "SIMULATED mistaken", manager());
    var closed = worker().closePeriod(0, periodId, today, "SIMULATED close after void", Set.of(), master.timezone(), manager());
    assertThrows(Invalid.class, () -> voided.rerecord(1, closed, AttendanceStatus.PRESENT, master, "SIMULATED inactive", manager()));
    var reopened = closed.openPeriod(1, UUID.randomUUID(), today, "SIMULATED reopen", master, manager());
    var restored = voided.rerecord(1, reopened, AttendanceStatus.PRESENT, master, "SIMULATED restore", manager());
    assertNotEquals(periodId, restored.attribution().activePeriodId());
    assertThrows(Denied.class, () -> voided.rerecord(1, reopened, AttendanceStatus.PRESENT, master, "SIMULATED denied", command(actor("FIELD_SUPERVISOR"))));
    var other = WorkerRegistration.register(UUID.randomUUID(),UUID.randomUUID(),master,"SIMULATED-2","SIMULATED other",null,today,Provenance.SIMULATED,manager());
    assertThrows(Invalid.class, () -> voided.rerecord(1, other, AttendanceStatus.PRESENT, master, "SIMULATED wrong person", manager()));
  }

  @Test void canonicalTimezoneControlsMidnightRatherThanServerOrBrowserDate() {
    Instant late = Instant.parse("2026-10-29T22:30:00Z");
    var malawi = new EstateContext(estate, ZoneId.of("Africa/Blantyre"));
    assertEquals(today.plusDays(1), malawi.today(late)); assertEquals(today, master.today(late));
    var worker = worker();
    var result = AttendanceDay.record(UUID.randomUUID(),worker,today.plusDays(1),AttendanceStatus.PRESENT,malawi,null,at(actor("FIELD_SUPERVISOR"),late));
    assertEquals("Africa/Blantyre", result.attribution().estateTimezone());
    assertThrows(Invalid.class, () -> AttendanceDay.record(UUID.randomUUID(),worker,today.plusDays(1),AttendanceStatus.PRESENT,master,null,at(actor("FIELD_SUPERVISOR"),late)));
  }

  @Test void syntheticDstFoldDoesNotChangeDailyIdentityAndAuditIsUtcMicroseconds() {
    var paris = new EstateContext(estate, ZoneId.of("Europe/Paris"));
    Instant before = Instant.parse("2026-10-25T00:30:00Z"), after = Instant.parse("2026-10-25T01:30:00Z");
    assertEquals(paris.today(before), paris.today(after));
    var precise = at(actor("ESTATE_MANAGER"), now.plusNanos(123456789));
    var worker = WorkerRegistration.register(workerId,periodId,master,"SIMULATED-1","SIMULATED",null,today,Provenance.SIMULATED,precise);
    assertEquals(123456000, worker.audit().createdAt().getNano());
    assertThrows(Invalid.class, () -> worker.edit(0, "SIMULATED changed",false,null,"SIMULATED clock reversal",at(actor("ESTATE_MANAGER"),now.minusSeconds(1))));
  }

  @Test void historicalCorrectionNeedsNoCurrentlyActiveMasterOrRegistration() {
    var day = day();
    var tomorrow = at(actor("ESTATE_MANAGER"),now.plusSeconds(86400));
    var changed = day.correct(0,AttendanceStatus.EXCUSED,"SIMULATED historical correction",tomorrow);
    assertEquals(day.attribution(),changed.attribution());
    assertEquals(now.plusSeconds(86400),changed.audit().updatedAt());
  }

  @Test void realCollectionIsTechnicallyDeniedBeforePrivacyEnablement() {
    assertThrows(Denied.class, () -> WorkerRegistration.register(workerId,periodId,master,"EXAMPLE","Example",null,today,Provenance.REAL,manager()));
    var simulated=worker();
    var rehydrated = new WorkerRegistration(simulated.id(),estate,simulated.workerCode(),simulated.displayName(),division,
        Provenance.REAL,simulated.activePeriods(),simulated.audit());
    assertThrows(Denied.class, () -> AttendanceDay.record(UUID.randomUUID(),rehydrated,today,AttendanceStatus.PRESENT,master,null,manager()));
    assertThrows(Denied.class, () -> rehydrated.edit(0,"Example changed",false,null,"Example reason",manager()));
  }

  @Test void evidencePortsRequireReasonsAndSuccessfulReceipts() {
    var worker = worker();
    assertThrows(Invalid.class, () -> new Revision<>(worker.id(),1,CommandKind.PROFILE,null,now,"actor",UUID.randomUUID(),worker));
    assertThrows(Invalid.class, () -> new Receipt("actor",UUID.randomUUID(),"POST","/api/v1/workers","0".repeat(64),409,"{}",null));
    assertThrows(Invalid.class, () -> new Receipt("actor",UUID.randomUUID(),"POST","/api/v1/workers","wrong",201,"{}",null));
    assertDoesNotThrow(() -> new Revision<>(worker.id(),0,CommandKind.REGISTER,null,now,"signed-sub",UUID.randomUUID(),worker));
    assertThrows(Invalid.class, () -> new Revision<>(worker.id(),0,CommandKind.REGISTER,null,now,"other-sub",UUID.randomUUID(),worker));
    assertThrows(Invalid.class, () -> new Revision<>(UUID.randomUUID(),0,CommandKind.REGISTER,null,now,"signed-sub",UUID.randomUUID(),worker));
    assertThrows(Invalid.class, () -> new Receipt("actor",UUID.randomUUID(),"POST","/api/v1/workers","0".repeat(64),201,"{}",null));
  }

  @Test void reasonsRejectControlsBlankValuesAndOversizedText() {
    for (String reason : List.of("", " ", "x\ny", "x\ty", "x\u0085y", "x".repeat(1001)))
      assertThrows(Invalid.class, () -> day().voidDay(0,reason,manager()));
    assertEquals("SIMULATED reason", reason("  SIMULATED reason  "));
  }
}
