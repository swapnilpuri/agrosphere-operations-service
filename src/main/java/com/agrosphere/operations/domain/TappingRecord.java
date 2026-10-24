package com.agrosphere.operations.domain;

import java.time.*;
import java.util.UUID;

public record TappingRecord(
    UUID id,
    UUID assignmentId,
    String workerId,
    UUID estateId,
    UUID blockId,
    LocalDate tappingDate,
    int plannedTreeCount,
    int actualTreeCount,
    Instant startedAt,
    Instant completedAt,
    String notes,
    String createdBy,
    Instant createdAt,
    Instant updatedAt) {}
