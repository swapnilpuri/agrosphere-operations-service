package com.agrosphere.operations.domain;

import static com.agrosphere.operations.domain.Rules.*;

import java.time.*;
import java.util.UUID;

public record TappingSchedule(
    UUID id,
    UUID estateId,
    UUID blockId,
    String name,
    String recurrenceRule,
    int plannedTreeCount,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    ScheduleStatus status,
    Instant createdAt,
    Instant updatedAt,
    long version,
    String createdBy,
    String updatedBy) {
  public TappingSchedule {
    require(id != null && estateId != null && blockId != null, "Identifiers required");
    require(name != null && !name.isBlank() && name.length() <= 200, "Invalid name");
    interval(recurrenceRule);
    require(plannedTreeCount > 0, "Planned trees must be positive");
    require(
        effectiveFrom != null && (effectiveTo == null || !effectiveTo.isBefore(effectiveFrom)),
        "Invalid effective dates");
  }

  public TappingSchedule transition(ScheduleStatus target, Instant now, String actor) {
    return new TappingSchedule(
        id,
        estateId,
        blockId,
        name,
        recurrenceRule,
        plannedTreeCount,
        effectiveFrom,
        effectiveTo,
        scheduleTransition(status, target),
        createdAt,
        now,
        version + 1,
        createdBy,
        actor);
  }
}
