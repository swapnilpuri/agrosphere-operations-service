package com.agrosphere.operations.domain;

import static com.agrosphere.operations.domain.Rules.*;

import java.time.*;
import java.util.UUID;

public record TappingAssignment(
    UUID id,
    UUID scheduleId,
    UUID estateId,
    UUID blockId,
    String workerId,
    LocalDate tappingDate,
    int plannedTreeCount,
    AssignmentStatus status,
    Instant createdAt,
    Instant updatedAt,
    long version,
    String createdBy,
    String updatedBy) {
  public TappingAssignment {
    require(
        id != null && estateId != null && blockId != null && tappingDate != null,
        "Identifiers and tapping date required");
    require(workerId != null && !workerId.isBlank() && workerId.length() <= 200, "Worker required");
    require(plannedTreeCount > 0, "Planned trees must be positive");
  }

  public TappingAssignment transition(AssignmentStatus target, Instant now, String actor) {
    return new TappingAssignment(
        id,
        scheduleId,
        estateId,
        blockId,
        workerId,
        tappingDate,
        plannedTreeCount,
        Rules.transition(status, target),
        createdAt,
        now,
        version + 1,
        createdBy,
        actor);
  }
}
