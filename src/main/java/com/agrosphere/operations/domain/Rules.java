package com.agrosphere.operations.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class Rules {
  private Rules() {}

  public enum ScheduleStatus {
    DRAFT,
    ACTIVE,
    SUSPENDED,
    CLOSED
  }

  public enum AssignmentStatus {
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    MISSED,
    CANCELLED
  }

  public enum MissedReasonCode {
    RAIN,
    WORKER_UNAVAILABLE,
    TREE_RESTING,
    ACCESS_RESTRICTED,
    OTHER
  }

  public static void require(boolean valid, String message) {
    if (!valid) throw new Violation(message);
  }

  public static class Violation extends RuntimeException {
    public Violation(String message) {
      super(message);
    }
  }

  public static int interval(String rule) {
    if ("DAILY".equals(rule)) return 1;
    if (rule != null && rule.matches("EVERY_N_DAYS:[1-9][0-9]{0,2}"))
      return Integer.parseInt(rule.substring(13));
    throw new Violation("recurrenceRule must be DAILY or EVERY_N_DAYS:1..999");
  }

  public static List<LocalDate> occurrences(
      String rule, LocalDate anchor, LocalDate end, LocalDate from, LocalDate to) {
    require(
        anchor != null && from != null && to != null && !from.isAfter(to),
        "Invalid generation dates");
    require(ChronoUnit.DAYS.between(from, to) <= 366, "Generation range exceeds 366 days");
    int step = interval(rule);
    var result = new ArrayList<LocalDate>();
    var first = from.isBefore(anchor) ? anchor : from;
    long days = ChronoUnit.DAYS.between(anchor, first);
    first = first.plusDays((step - days % step) % step);
    for (var date = first;
        !date.isAfter(to) && (end == null || !date.isAfter(end));
        date = date.plusDays(step)) result.add(date);
    return List.copyOf(result);
  }

  public static ScheduleStatus scheduleTransition(ScheduleStatus current, ScheduleStatus target) {
    require(current != ScheduleStatus.CLOSED, "Closed schedule is immutable");
    require(
        (target == ScheduleStatus.ACTIVE
                && (current == ScheduleStatus.DRAFT || current == ScheduleStatus.SUSPENDED))
            || (target == ScheduleStatus.SUSPENDED && current == ScheduleStatus.ACTIVE)
            || target == ScheduleStatus.CLOSED,
        "Invalid schedule transition");
    return target;
  }

  public static AssignmentStatus transition(AssignmentStatus current, AssignmentStatus target) {
    require(
        (current == AssignmentStatus.PLANNED
                && Set.of(
                        AssignmentStatus.IN_PROGRESS,
                        AssignmentStatus.MISSED,
                        AssignmentStatus.CANCELLED)
                    .contains(target))
            || (current == AssignmentStatus.IN_PROGRESS
                && Set.of(
                        AssignmentStatus.COMPLETED,
                        AssignmentStatus.MISSED,
                        AssignmentStatus.CANCELLED)
                    .contains(target)),
        "Invalid assignment transition");
    return target;
  }

  public static LocalDate businessDate(Instant now, String timezone) {
    return now.atZone(ZoneId.of(timezone)).toLocalDate();
  }

  public static void actual(int planned, int actual) {
    require(
        actual >= 0 && actual <= planned, "Actual trees must be between zero and planned trees");
  }

  public static double percentage(long completed, long planned) {
    return planned == 0 ? 0 : Math.round(10000.0 * completed / planned) / 100.0;
  }
}
