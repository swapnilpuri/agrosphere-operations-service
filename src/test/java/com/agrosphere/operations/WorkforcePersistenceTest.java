package com.agrosphere.operations;

import static com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import static org.junit.jupiter.api.Assertions.*;
import com.agrosphere.operations.workforce.domain.*;
import com.agrosphere.operations.workforce.infrastructure.WorkforceState;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkforcePersistenceTest {
  final UUID estate=UUID.randomUUID();
  final Instant now=Instant.parse("2026-10-29T12:00:00.123456Z");
  final LocalDate date=LocalDate.of(2026,10,29);
  final Command manager=new Command(new Actor(" opaque-signed-sub ",Set.of("ESTATE_MANAGER"),Set.of(estate)),
      Clock.fixed(now,ZoneOffset.UTC),UUID.randomUUID());
  final EstateContext master=new EstateContext(estate,ZoneId.of("Africa/Blantyre"));
  WorkerRegistration worker() {
    return WorkerRegistration.register(UUID.randomUUID(),UUID.randomUUID(),master,"SIMULATED-01","SIMULATED — Unicode 🧑",null,date,
        Provenance.SIMULATED,manager);
  }
  @Test void privateWorkerCodecPreservesUnicodeOpaqueSubjectsAndMicroseconds() {
    var worker=worker();var encoded=WorkforceState.write(worker);
    assertEquals(worker,WorkforceState.worker(encoded));
    var n=WorkforceState.parse(encoded);
    assertEquals("2026-10-29T12:00:00.123456Z",n.path("audit").path("createdAt").asText());
    assertEquals(" opaque-signed-sub ",n.path("audit").path("createdBy").asText());
    assertTrue(n.path("divisionId").isNull());
  }
  @Test void closedAndReopenedPeriodsSurvivePrivateSnapshotRoundtrip() {
    var worker=worker();
    var later=new Command(manager.actor(),Clock.fixed(now.plusSeconds(86400),ZoneOffset.UTC),manager.correlationId());
    var closed=worker.closePeriod(0,worker.activePeriods().getFirst().id(),date.plusDays(1),"SIMULATED close",Set.of(),master.timezone(),later);
    var reopened=closed.openPeriod(1,UUID.randomUUID(),date.plusDays(1),"SIMULATED reopen",master,later);
    assertEquals(reopened,WorkforceState.worker(WorkforceState.write(reopened)));
  }
  @Test void dailyAttributionNullDivisionAndVoidAreNotLost() {
    var worker=worker();var day=AttendanceDay.record(UUID.randomUUID(),worker,date,AttendanceStatus.EXCUSED,master,null,manager);
    var voided=day.voidDay(0,"SIMULATED mistaken",manager);
    assertEquals(day,WorkforceState.day(WorkforceState.write(day)));
    assertEquals(voided,WorkforceState.day(WorkforceState.write(voided)));
    assertNotEquals(day.status(),voided.status());
    assertThrows(RuntimeException.class,()->WorkforceState.day("{}"));
  }
}
