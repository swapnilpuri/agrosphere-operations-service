package com.agrosphere.operations;

import static com.agrosphere.operations.domain.Rules.*;
import static org.junit.jupiter.api.Assertions.*;

import com.agrosphere.operations.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DomainTest {
  LocalDate d = LocalDate.of(2026, 10, 24);
  UUID id = UUID.randomUUID();

  TappingSchedule schedule(int trees, LocalDate end) {
    return new TappingSchedule(
        id,
        id,
        id,
        "Tapping",
        "DAILY",
        trees,
        d,
        end,
        ScheduleStatus.DRAFT,
        Instant.EPOCH,
        Instant.EPOCH,
        0,
        "actor",
        "actor");
  }

  @Test
  void positiveTrees() {
    assertThrows(Violation.class, () -> schedule(0, null));
    assertThrows(Violation.class, () -> schedule(-1, null));
  }

  @Test
  void dates() {
    assertThrows(Violation.class, () -> schedule(1, d.minusDays(1)));
    assertNotNull(schedule(1, null));
  }

  @Test
  void recurrenceAnchorsAtEffectiveStart() {
    assertEquals(
        List.of(d.plusDays(2), d.plusDays(4)),
        occurrences("EVERY_N_DAYS:2", d, null, d.plusDays(1), d.plusDays(5)));
  }

  @Test
  void effectiveEndIsInclusive() {
    assertEquals(
        List.of(d, d.plusDays(1)),
        occurrences("DAILY", d, d.plusDays(1), d.minusDays(2), d.plusDays(10)));
  }

  @Test
  void boundedExpansion() {
    assertThrows(Violation.class, () -> occurrences("DAILY", d, null, d, d.plusDays(367)));
    assertThrows(Violation.class, () -> interval("EVERY_N_DAYS:0"));
    assertThrows(Violation.class, () -> interval("FREQ=MONTHLY"));
  }

  @Test
  void scheduleLifecycle() {
    var s =
        schedule(10, null)
            .transition(ScheduleStatus.ACTIVE, Instant.now(), "actor")
            .transition(ScheduleStatus.SUSPENDED, Instant.now(), "actor")
            .transition(ScheduleStatus.ACTIVE, Instant.now(), "actor")
            .transition(ScheduleStatus.CLOSED, Instant.now(), "actor");
    assertEquals(4, s.version());
    assertThrows(
        Violation.class, () -> s.transition(ScheduleStatus.ACTIVE, Instant.now(), "actor"));
  }

  @Test
  void invalidScheduleTransition() {
    assertThrows(
        Violation.class, () -> scheduleTransition(ScheduleStatus.DRAFT, ScheduleStatus.SUSPENDED));
  }

  @Test
  void validAssignmentStates() {
    assertEquals(
        AssignmentStatus.IN_PROGRESS,
        transition(AssignmentStatus.PLANNED, AssignmentStatus.IN_PROGRESS));
    assertEquals(
        AssignmentStatus.COMPLETED,
        transition(AssignmentStatus.IN_PROGRESS, AssignmentStatus.COMPLETED));
  }

  @Test
  void terminalAssignmentsCannotChange() {
    for (var s :
        List.of(AssignmentStatus.CANCELLED, AssignmentStatus.MISSED, AssignmentStatus.COMPLETED))
      for (var t : AssignmentStatus.values()) assertThrows(Violation.class, () -> transition(s, t));
  }

  @Test
  void cannotCompletePlanned() {
    assertThrows(
        Violation.class, () -> transition(AssignmentStatus.PLANNED, AssignmentStatus.COMPLETED));
  }

  @Test
  void actualTrees() {
    assertDoesNotThrow(() -> actual(10, 0));
    assertDoesNotThrow(() -> actual(10, 10));
    assertThrows(Violation.class, () -> actual(10, 11));
    assertThrows(Violation.class, () -> actual(10, -1));
  }

  @Test
  void timezoneBoundary() {
    assertEquals(
        LocalDate.of(2026, 10, 25),
        businessDate(Instant.parse("2026-10-24T20:00:00Z"), "Asia/Colombo"));
    assertEquals(
        LocalDate.of(2026, 10, 24),
        businessDate(Instant.parse("2026-10-25T02:00:00Z"), "America/Chicago"));
  }

  @Test
  void daylightSaving() {
    assertEquals(
        LocalDate.of(2026, 11, 1),
        businessDate(Instant.parse("2026-11-01T07:30:00Z"), "America/Chicago"));
  }

  @Test
  void zeroAndRoundedPercentage() {
    assertEquals(0, percentage(0, 0));
    assertEquals(33.33, percentage(1, 3));
  }
}
