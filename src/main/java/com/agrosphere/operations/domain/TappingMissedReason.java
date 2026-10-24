package com.agrosphere.operations.domain;

import java.time.Instant;
import java.util.UUID;

public record TappingMissedReason(
    UUID assignmentId,
    Rules.MissedReasonCode reasonCode,
    String remarks,
    Instant recordedAt,
    String recordedBy) {}
