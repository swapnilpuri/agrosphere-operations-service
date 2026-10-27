package com.agrosphere.operations.domain;

import static com.agrosphere.operations.domain.CollectionRules.*;
import static com.agrosphere.operations.domain.Rules.require;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An immutable child; selection/replacement is performed only by its collection. */
public record DrcMeasurement(UUID id, UUID collectionId, long introducedVersion,
    BigDecimal percent, Instant measuredAt, DrcMethod method, String sourceReference,
    String sampleReference, String instrumentReference, Provenance provenance,
    Instant recordedAt, String recordedBy) {
  public DrcMeasurement {
    require(id != null && collectionId != null && introducedVersion >= 1,
        "Measurement identity and positive introduced version required");
    percent = drcPercent(percent);
    measuredAt = observation(measuredAt);
    recordedAt = observation(recordedAt);
    require(!measuredAt.isAfter(recordedAt), "Measurement cannot be recorded before observation");
    require(method != null && provenance != null, "Measurement method/provenance required");
    sourceReference = text(sourceReference, 200, "measurement source");
    sampleReference = optionalText(sampleReference, 200, "sample reference");
    instrumentReference = optionalText(instrumentReference, 200, "instrument reference");
    recordedBy = subject(recordedBy);
    require(method != DrcMethod.LAB_REPORTED || sampleReference != null,
        "Reported laboratory measurement requires sample reference");
  }
}
