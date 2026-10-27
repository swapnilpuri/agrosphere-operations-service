package com.agrosphere.operations;

import static com.agrosphere.operations.domain.CollectionRules.*;
import static org.junit.jupiter.api.Assertions.*;

import com.agrosphere.operations.domain.*;
import com.agrosphere.operations.domain.LatexCollection.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CollectionDomainTest {
  final UUID estate = UUID.randomUUID(), block = UUID.randomUUID(), division = UUID.randomUUID();
  final UUID assignmentId = UUID.randomUUID(), recordId = UUID.randomUUID(), collectionId = UUID.randomUUID();
  final Instant now = Instant.parse("2026-10-27T18:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  final LocalDate date = LocalDate.of(2026, 10, 27);
  final WeightEvidence evidence = new WeightEvidence(WeightMethod.SCALE, "SIMULATED-SCALE", null);

  BigDecimal decimal(String value) { return new BigDecimal(value); }

  TappingAssignment assignment(Rules.AssignmentStatus status) {
    return new TappingAssignment(assignmentId, null, estate, block, "worker-sub", date, 100,
        status, now.minusSeconds(10800), now.minusSeconds(7200), 2, "actor", "actor");
  }

  TappingRecord record(int actual, Instant completed) {
    return new TappingRecord(recordId, assignmentId, "worker-sub", estate, block, date,
        100, actual, now.minusSeconds(10800), completed, null, "actor", now.minusSeconds(10800), completed);
  }

  MasterSnapshot master(String zone) {
    return new MasterSnapshot(estate, block, division, zone, 2, 3, now.minusSeconds(60));
  }

  WeightObservation weight(String mass, Instant collected, String backdate) {
    return new WeightObservation(collected, decimal(mass), evidence, backdate);
  }

  LatexCollection pending() {
    return LatexCollection.create(collectionId, record(50, now.minusSeconds(7200)),
        assignment(Rules.AssignmentStatus.COMPLETED), master("UTC"), " lot-01 ",
        weight("125.500", now.minusSeconds(3600), null), Provenance.SIMULATED, null, clock, "recorder-sub");
  }

  DrcMeasurement measurement(LatexCollection c, long version, String percent,
      Instant measuredAt, Clock at, Provenance provenance, String actor) {
    return new DrcMeasurement(UUID.randomUUID(), c.id(), version, decimal(percent), measuredAt,
        DrcMethod.FIELD_TEST, "SIMULATED-TEST", null, null, provenance, CollectionRules.now(at), actor);
  }

  LatexCollection calculated() {
    var c = pending();
    return c.recordDrc(0, measurement(c, 1, "32.40", now.minusSeconds(1800), clock,
        Provenance.SIMULATED, "supervisor-sub"), clock, "supervisor-sub");
  }

  @Test void calculationVectorsAndMissingVersusZero() {
    for (String[] vector : new String[][] {
        {"125.500", "32.40", "40.6620000", "40.662"},
        {"0.001", "50.00", "0.0005000", "0.001"},
        {"1.005", "50.00", "0.5025000", "0.503"},
        {"999999.999", "100.00", "999999.9990000", "999999.999"},
        {"10.000", "0.00", "0.0000000", "0.000"}}) {
      var result = calculate(decimal(vector[0]), decimal(vector[1]));
      assertEquals(decimal(vector[2]), result.exactKg());
      assertEquals(decimal(vector[3]), result.dryKg());
      assertEquals(POLICY, result.policy());
    }
    assertNull(calculate(decimal("10"), null));
    var roundedLot = calculate(decimal("0.001"), decimal("50"));
    assertEquals(decimal("0.002"), roundedLot.dryKg().add(roundedLot.dryKg()));
  }

  @Test void decimalBoundariesDoNotRoundInvalidInputs() {
    assertEquals(decimal("1.000"), wetKg(decimal("1.0000")));
    assertEquals(decimal("32.40"), drcPercent(decimal("32.4000")));
    for (String invalid : List.of("0", "-1", "1000000", "1.0001"))
      assertThrows(Rules.Violation.class, () -> wetKg(decimal(invalid)));
    for (String invalid : List.of("-0.01", "100.01", "32.401"))
      assertThrows(Rules.Violation.class, () -> drcPercent(decimal(invalid)));
    assertThrows(Rules.Violation.class, () -> wetKg(null));
  }

  @Test void offsetAndExactMicrosecondsIncludingArbitrarilyManyTrailingZeros() {
    assertEquals(Instant.parse("2026-10-27T04:30:00.123456Z"),
        observedTime("2026-10-27T10:00:00.123456000000000+05:30"));
    for (String value : List.of("2026-10-27T10:00:00", "2026-10-27T10:00:00.0000001Z",
        "2026-10-27T10:00:00.1000001Z", "2026-02-30T10:00:00Z"))
      assertThrows(Rules.Violation.class, () -> observedTime(value));
    assertThrows(Rules.Violation.class, () -> observation(now.plusNanos(1)));
    assertEquals(now, CollectionRules.now(Clock.fixed(now.plusNanos(999), ZoneOffset.UTC)));
  }

  @Test void completedNonzeroExecutionRequiredForEveryStatus() {
    for (var status : Rules.AssignmentStatus.values()) {
      if (status == Rules.AssignmentStatus.COMPLETED) continue;
      assertThrows(Rules.Violation.class, () -> LatexCollection.create(collectionId,
          record(50, now.minusSeconds(7200)), assignment(status), master("UTC"), "LOT",
          weight("1", now.minusSeconds(3600), null), Provenance.SIMULATED, null, clock, "actor"));
    }
    for (var invalid : List.of(record(0, now.minusSeconds(7200)), record(50, null)))
      assertThrows(Rules.Violation.class, () -> LatexCollection.create(collectionId,
          invalid, assignment(Rules.AssignmentStatus.COMPLETED), master("UTC"), "LOT",
          weight("1", now.minusSeconds(3600), null), Provenance.SIMULATED, null, clock, "actor"));
  }

  @Test void snapshotsAreDerivedAndMasterOwnershipMustAgree() {
    var c = pending();
    assertEquals(recordId, c.attribution().tappingRecordId());
    assertEquals("worker-sub", c.attribution().workerId());
    assertEquals(division, c.attribution().master().divisionId());
    assertEquals("recorder-sub", c.createdBy());
    assertEquals("LOT-01", c.collectionReference());
    var wrong = new MasterSnapshot(UUID.randomUUID(), block, division, "UTC", 0, 0, now);
    assertThrows(Rules.Violation.class, () -> LatexCollection.create(collectionId,
        record(50, now.minusSeconds(7200)), assignment(Rules.AssignmentStatus.COMPLETED), wrong,
        "LOT", c.weight(), Provenance.SIMULATED, null, clock, "actor"));
  }

  @Test void recordAndAssignmentMustReferToSameExecution() {
    var wrong = new TappingRecord(recordId, UUID.randomUUID(), "worker-sub", estate, block, date,
        100, 50, now.minusSeconds(10800), now.minusSeconds(7200), null, "actor", now, now);
    assertThrows(Rules.Violation.class, () -> LatexCollection.create(collectionId, wrong,
        assignment(Rules.AssignmentStatus.COMPLETED), master("UTC"), "LOT", pending().weight(),
        Provenance.SIMULATED, null, clock, "actor"));
  }

  @Test void referencesNormalizeAndRejectNonAsciiOrMalformedTokens() {
    assertEquals("LOT-01", reference(" lot-01 "));
    for (String value : List.of("", " ", "LOT 1", "LOT/1", "é", "ß", "x".repeat(65)))
      assertThrows(Rules.Violation.class, () -> reference(value));
    assertThrows(Rules.Violation.class, () -> reference(null));
  }

  @Test void collectionChronologyRejectsFutureAndPreCompletion() {
    for (Instant value : List.of(now.plusSeconds(1), now.minusSeconds(7201)))
      assertThrows(Rules.Violation.class, () -> LatexCollection.create(collectionId,
          record(50, now.minusSeconds(7200)), assignment(Rules.AssignmentStatus.COMPLETED),
          master("UTC"), "LOT", weight("1", value, null), Provenance.SIMULATED, null, clock, "actor"));
  }

  @Test void pendingAndInitialMeasurementKeepOriginalEvidence() {
    var original = pending();
    var changed = original.recordDrc(0, measurement(original, 1, "0", now.minusSeconds(1800), clock,
        Provenance.SIMULATED, "actor"), clock, "actor");
    assertEquals(Status.PENDING_DRC, original.status());
    assertNull(original.measurement());
    assertNull(original.calculation());
    assertEquals(Status.CALCULATED, changed.status());
    assertEquals(decimal("0.000"), changed.calculation().dryKg());
    assertEquals(1, changed.version());
    assertThrows(Rules.Violation.class, () -> changed.recordDrc(1,
        measurement(changed, 2, "1", now, clock, Provenance.SIMULATED, "actor"), clock, "actor"));
  }

  @Test void measurementEvidenceAndChronologyAreRequired() {
    var c = pending();
    assertThrows(Rules.Violation.class, () -> c.recordDrc(0,
        measurement(c, 1, "50", now.minusSeconds(3601), clock, Provenance.SIMULATED, "actor"), clock, "actor"));
    assertThrows(Rules.Violation.class, () -> measurement(c, 1, "50", now.plusSeconds(1), clock, Provenance.SIMULATED, "actor"));
    assertThrows(Rules.Violation.class, () -> new DrcMeasurement(UUID.randomUUID(), c.id(), 1,
        decimal("32.40"), now, DrcMethod.LAB_REPORTED, "source", null, null,
        Provenance.SIMULATED, now, "actor"));
    assertThrows(Rules.Violation.class, () -> new WeightEvidence(WeightMethod.SCALE, " ", null));
  }

  @Test void selectedMeasurementMustMatchOwnerProvenanceAndRevision() {
    var c = pending();
    assertThrows(Rules.Violation.class, () -> c.recordDrc(0,
        measurement(c, 1, "50", now, clock, Provenance.REAL, "actor"), clock, "actor"));
    assertThrows(Rules.Violation.class, () -> c.recordDrc(0,
        measurement(c, 2, "50", now, clock, Provenance.SIMULATED, "actor"), clock, "actor"));
    var other = new DrcMeasurement(UUID.randomUUID(), UUID.randomUUID(), 1, decimal("50"), now,
        DrcMethod.FIELD_TEST, "source", null, null, Provenance.SIMULATED, now, "actor");
    assertThrows(Rules.Violation.class, () -> c.recordDrc(0, other, clock, "actor"));
  }

  @Test void auditActorIsOpaqueSignedSubjectAndCannotBeForgedOnMeasurement() {
    var c = pending();
    var m = measurement(c, 1, "50", now, clock, Provenance.SIMULATED, " actor-with-spaces ");
    var changed = c.recordDrc(0, m, clock, " actor-with-spaces ");
    assertEquals(" actor-with-spaces ", changed.updatedBy());
    assertThrows(Rules.Violation.class, () -> c.recordDrc(0, m, clock, "other"));
  }

  @Test void correctionRecalculatesAndOldInputRemainsImmutable() {
    var old = calculated();
    var changed = old.correct(1, weight("100.000", old.weight().collectedAt(), null), null,
        "correct scale transcription", clock, "manager");
    assertEquals(decimal("40.662"), old.calculation().dryKg());
    assertEquals(decimal("32.400"), changed.calculation().dryKg());
    assertSame(old.measurement(), changed.measurement());
    assertEquals(old.attribution(), changed.attribution());
    assertEquals(2, changed.version());
  }

  @Test void timeCorrectionPastSelectedMeasurementRequiresAtomicReplacement() {
    var old = calculated();
    var later = Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC);
    var newWeight = weight("125.500", now.plusSeconds(600), null);
    assertThrows(Rules.Violation.class, () -> old.correct(1, newWeight, null, "time fix", later, "manager"));
    var m = measurement(old, 2, "30", now.plusSeconds(1200), later, Provenance.SIMULATED, "manager");
    var changed = old.correct(1, newWeight, m, "time fix with replacement", later, "manager");
    assertEquals(m.id(), changed.measurement().id());
    assertNotEquals(old.measurement().id(), changed.measurement().id());
    assertEquals(decimal("37.650"), changed.calculation().dryKg());
  }

  @Test void pendingCorrectionMayIntroduceFirstMeasurement() {
    var old = pending();
    var changed = old.correct(0, null, measurement(old, 1, "30", now, clock,
        Provenance.SIMULATED, "manager"), "add corrected evidence", clock, "manager");
    assertEquals(Status.CALCULATED, changed.status());
  }

  @Test void noopBlankReasonAndStaleWritesRejected() {
    var old = calculated();
    assertThrows(Rules.Violation.class, () -> old.correct(1, old.weight(), null, "reason", clock, "manager"));
    assertThrows(Rules.Violation.class, () -> old.correct(1, weight("100", old.weight().collectedAt(), null), null, " ", clock, "manager"));
    assertThrows(Rules.Violation.class, () -> old.voidCollection(0, "reason", clock, "manager"));
    assertThrows(Rules.Violation.class, () -> old.correct(0, weight("100", old.weight().collectedAt(), null), null, "reason", clock, "manager"));
  }

  @Test void voidIsTerminalAndRetainsPendingOrCalculatedEvidence() {
    for (var old : List.of(pending(), calculated())) {
      var changed = old.voidCollection(old.version(), "duplicate physical lot", clock, "manager");
      assertEquals(Status.VOIDED, changed.status());
      assertEquals(old.measurement(), changed.measurement());
      assertEquals(old.calculation(), changed.calculation());
      assertThrows(Rules.Violation.class, () -> changed.voidCollection(changed.version(), "again", clock, "manager"));
      assertThrows(Rules.Violation.class, () -> changed.correct(changed.version(), old.weight(), null, "again", clock, "manager"));
    }
  }

  @Test void replacementRequiresVoidedSameExecutionAndNewReference() {
    var old = pending();
    assertThrows(Rules.Violation.class, () -> LatexCollection.create(UUID.randomUUID(),
        record(50, now.minusSeconds(7200)), assignment(Rules.AssignmentStatus.COMPLETED), master("UTC"),
        "LOT-02", old.weight(), Provenance.SIMULATED, old, clock, "manager"));
    var voided = old.voidCollection(0, "wrong observation", clock, "manager");
    var replacement = LatexCollection.create(UUID.randomUUID(), record(50, now.minusSeconds(7200)),
        assignment(Rules.AssignmentStatus.COMPLETED), master("UTC"), "LOT-02", old.weight(),
        Provenance.SIMULATED, voided, clock, "manager");
    assertEquals(old.id(), replacement.replacementOfCollectionId());
    assertThrows(Rules.Violation.class, () -> LatexCollection.create(UUID.randomUUID(), record(50, now.minusSeconds(7200)),
        assignment(Rules.AssignmentStatus.COMPLETED), master("UTC"), "LOT-01", old.weight(), Provenance.SIMULATED, voided, clock, "manager"));
    assertThrows(Rules.Violation.class, () -> LatexCollection.create(UUID.randomUUID(), record(50, now.minusSeconds(7200)),
        assignment(Rules.AssignmentStatus.COMPLETED), master("UTC"), "LOT-02", old.weight(), Provenance.REAL, voided, clock, "manager"));
  }

  @Test void estateTimezoneDateRolloverAndDstAreIndependentOfClockZone() {
    var colombo = LatexCollection.create(collectionId, record(50, now.minusSeconds(7200)),
        assignment(Rules.AssignmentStatus.COMPLETED), master("Asia/Colombo"), "LOT",
        weight("1", now.minusSeconds(1), null), Provenance.SIMULATED, null,
        Clock.fixed(now, ZoneId.of("America/Chicago")), "actor");
    assertEquals(date, colombo.collectionDate());
    var nextDay = colombo.correct(0, weight("1", Instant.parse("2026-10-27T19:00:00Z"), null), null,
        "later collection time", Clock.fixed(Instant.parse("2026-10-27T19:01:00Z"), ZoneOffset.UTC), "actor");
    assertEquals(date.plusDays(1), nextDay.collectionDate());
    assertEquals(LocalDate.of(2026,11,1), observedTime("2026-11-01T01:30:00-05:00").atZone(zone("America/Chicago")).toLocalDate());
    assertEquals(LocalDate.of(2026,11,1), observedTime("2026-11-01T01:30:00-06:00").atZone(zone("America/Chicago")).toLocalDate());
    assertEquals(Duration.ofHours(1), Duration.between(observedTime("2026-11-01T01:30:00-05:00"), observedTime("2026-11-01T01:30:00-06:00")));
  }

  @Test void historicalEntryNeedsReasonAndSnapshotsDoNotChange() {
    var later = Clock.fixed(now.plusSeconds(86400), ZoneOffset.UTC);
    assertThrows(Rules.Violation.class, () -> LatexCollection.create(collectionId,
        record(50, now.minusSeconds(7200)), assignment(Rules.AssignmentStatus.COMPLETED), master("UTC"),
        "LOT", weight("1", now.minusSeconds(3600), null), Provenance.SIMULATED, null, later, "actor"));
    var c = LatexCollection.create(collectionId, record(50, now.minusSeconds(7200)),
        assignment(Rules.AssignmentStatus.COMPLETED), master("UTC"), "LOT",
        weight("1", now.minusSeconds(3600), "historical entry"), Provenance.SIMULATED, null, later, "actor");
    assertEquals(date, c.collectionDate());
    assertEquals("UTC", c.attribution().master().estateTimezone());
  }

  @Test void revisionsAndAdjustmentsBindExactSnapshotsAndConsecutiveVersions() {
    var old = pending();
    var changed = old.voidCollection(0, "duplicate", clock, "manager");
    var correlation = UUID.randomUUID();
    var before = new CollectionRevision(old.id(), 0, CommandKind.CREATE, old, old.updatedAt(), old.updatedBy(), correlation);
    var after = new CollectionRevision(changed.id(), 1, CommandKind.VOID, changed, changed.updatedAt(), changed.updatedBy(), correlation);
    var adjustment = new CollectionAdjustment(UUID.randomUUID(), before, after, "duplicate", now, "manager", correlation);
    assertSame(old, adjustment.before().snapshot());
    assertSame(changed, adjustment.after().snapshot());
    assertThrows(Rules.Violation.class, () -> new CollectionRevision(old.id(), 1, CommandKind.CREATE, old, now, old.updatedBy(), correlation));
    assertThrows(Rules.Violation.class, () -> new CollectionAdjustment(UUID.randomUUID(), before, after, "duplicate", now, "wrong actor", correlation));
  }

  @Test void commandClockCannotMoveBackwards() {
    var old = pending();
    assertThrows(Rules.Violation.class, () -> old.voidCollection(0, "reason",
        Clock.fixed(now.minusSeconds(1), ZoneOffset.UTC), "manager"));
  }
}
