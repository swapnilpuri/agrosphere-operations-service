package com.agrosphere.operations.domain;

import static com.agrosphere.operations.domain.CollectionRules.*;
import static com.agrosphere.operations.domain.Rules.require;

import java.math.BigDecimal;
import java.time.*;
import java.util.Objects;
import java.util.UUID;

/** Immutable aggregate. Application transactions own authorization, uniqueness and replay. */
public record LatexCollection(UUID id, Attribution attribution, String collectionReference,
    UUID replacementOfCollectionId, WeightObservation weight, Provenance provenance,
    Status status, DrcMeasurement measurement, Calculation calculation, long version,
    Instant createdAt, Instant updatedAt, String createdBy, String updatedBy) {

  public record MasterSnapshot(UUID estateId, UUID blockId, UUID divisionId,
      String estateTimezone, long estateVersion, long blockVersion, Instant validatedAt) {
    public MasterSnapshot {
      require(estateId != null && blockId != null && divisionId != null,
          "Canonical master identifiers required");
      estateTimezone = zone(estateTimezone).getId();
      require(estateVersion >= 0 && blockVersion >= 0, "Master versions must be nonnegative");
      validatedAt = observation(validatedAt);
    }
  }

  public record Attribution(UUID tappingRecordId, UUID assignmentId, UUID estateId,
      UUID blockId, String workerId, LocalDate tappingDate, Instant completedAt,
      MasterSnapshot master) {
    public Attribution {
      require(tappingRecordId != null && assignmentId != null && estateId != null
          && blockId != null && tappingDate != null && completedAt != null && master != null,
          "Execution attribution required");
      workerId = subject(workerId);
      require(estateId.equals(master.estateId()) && blockId.equals(master.blockId()),
          "Master snapshot must match execution ownership");
    }
  }

  public record WeightEvidence(WeightMethod method, String sourceReference,
      String instrumentReference) {
    public WeightEvidence {
      require(method != null, "Weight method required");
      sourceReference = text(sourceReference, 200, "weight source");
      instrumentReference = optionalText(instrumentReference, 200, "weight instrument");
    }
  }

  public record WeightObservation(Instant collectedAt, BigDecimal wetLatexKg,
      WeightEvidence evidence, String backdateReason) {
    public WeightObservation {
      collectedAt = observation(collectedAt);
      wetLatexKg = wetKg(wetLatexKg);
      require(evidence != null, "Weight evidence required");
      backdateReason = optionalText(backdateReason, 2000, "backdate reason");
    }
  }

  public LatexCollection {
    require(id != null && attribution != null && weight != null && provenance != null
        && status != null && version >= 0, "Collection identity/state required");
    collectionReference = reference(collectionReference);
    require(!id.equals(replacementOfCollectionId), "Collection cannot replace itself");
    createdAt = observation(createdAt);
    updatedAt = observation(updatedAt);
    require(!createdAt.isAfter(updatedAt), "Audit times out of order");
    createdBy = subject(createdBy);
    updatedBy = subject(updatedBy);
    require(status != Status.VOIDED || version > 0, "Void requires a later revision");
    require(!attribution.master().validatedAt().isAfter(createdAt), "Master validation is future");
    require(!weight.collectedAt().isBefore(attribution.completedAt())
        && !weight.collectedAt().isAfter(updatedAt), "Collection must follow completion and precede receipt");
    var date = weight.collectedAt().atZone(zone(attribution.master().estateTimezone())).toLocalDate();
    require(!date.isBefore(attribution.tappingDate()), "Collection date before tapping date");
    require(!date.isBefore(createdAt.atZone(zone(attribution.master().estateTimezone())).toLocalDate())
        || weight.backdateReason() != null, "Historical collection requires backdate reason");
    if (measurement != null) {
      require(measurement.collectionId().equals(id) && measurement.provenance() == provenance,
          "Selected measurement ownership/provenance mismatch");
      require(measurement.introducedVersion() <= version
          && !measurement.measuredAt().isBefore(weight.collectedAt())
          && !measurement.recordedAt().isAfter(updatedAt), "Selected measurement chronology/version invalid");
    }
    require(Objects.equals(calculation, calculate(weight.wetLatexKg(),
        measurement == null ? null : measurement.percent())), "Derived calculation mismatch");
    require(status == Status.VOIDED || (status == Status.PENDING_DRC && measurement == null)
        || (status == Status.CALCULATED && measurement != null), "Invalid collection lifecycle shape");
  }

  public LocalDate collectionDate() {
    return weight.collectedAt().atZone(zone(attribution.master().estateTimezone())).toLocalDate();
  }

  public static LatexCollection create(UUID id, TappingRecord record, TappingAssignment assignment,
      MasterSnapshot master, String reference, WeightObservation weight, Provenance provenance,
      LatexCollection replaces, Clock clock, String actor) {
    require(record != null && assignment != null, "Local completed execution required");
    require(assignment.status() == Rules.AssignmentStatus.COMPLETED && record.completedAt() != null
        && record.actualTreeCount() > 0, "Only completed nonzero executions may receive collections");
    require(record.assignmentId().equals(assignment.id()) && record.estateId().equals(assignment.estateId())
        && record.blockId().equals(assignment.blockId()) && record.workerId().equals(assignment.workerId())
        && record.tappingDate().equals(assignment.tappingDate()), "Execution/assignment attribution mismatch");
    var context = new Attribution(record.id(), record.assignmentId(), record.estateId(), record.blockId(),
        record.workerId(), record.tappingDate(), record.completedAt(), master);
    if (replaces != null) {
      require(replaces.status() == Status.VOIDED && replaces.attribution().tappingRecordId().equals(record.id())
          && replaces.provenance() == provenance, "Replacement requires voided same-execution/provenance collection");
      require(!replaces.collectionReference().equals(CollectionRules.reference(reference)),
          "Replacement needs a new reference");
    }
    var now = now(clock);
    return new LatexCollection(id, context, reference, replaces == null ? null : replaces.id(), weight,
        provenance, Status.PENDING_DRC, null, null, 0, now, now, actor, actor);
  }

  private long nextVersion(long expectedVersion) {
    require(status != Status.VOIDED, "Voided collection is terminal");
    require(version == expectedVersion, "Stale collection version");
    require(version < Long.MAX_VALUE, "Collection version exhausted");
    return version + 1;
  }

  private void measurementForCommand(DrcMeasurement candidate, long nextVersion,
      Instant now, String actor) {
    require(candidate != null && candidate.introducedVersion() == nextVersion,
        "Measurement must be introduced at this revision");
    require(candidate.recordedAt().equals(now) && candidate.recordedBy().equals(actor),
        "Measurement audit must match command actor/time");
    require(measurement == null || !candidate.id().equals(measurement.id()),
        "Replacement must append new measurement evidence");
  }

  public LatexCollection recordDrc(long expectedVersion, DrcMeasurement candidate,
      Clock clock, String actor) {
    var next = nextVersion(expectedVersion);
    require(status == Status.PENDING_DRC, "Initial DRC only allowed while pending");
    var now = now(clock);
    actor = subject(actor);
    require(!now.isBefore(updatedAt), "Command audit time moved backwards");
    measurementForCommand(candidate, next, now, actor);
    return new LatexCollection(id, attribution, collectionReference, replacementOfCollectionId, weight,
        provenance, Status.CALCULATED, candidate, calculate(weight.wetLatexKg(), candidate.percent()),
        next, createdAt, now, createdBy, actor);
  }

  public LatexCollection correct(long expectedVersion, WeightObservation correctedWeight,
      DrcMeasurement replacement, String reason, Clock clock, String actor) {
    var next = nextVersion(expectedVersion);
    text(reason, 2000, "correction reason");
    var now = now(clock);
    actor = subject(actor);
    require(!now.isBefore(updatedAt), "Command audit time moved backwards");
    var nextWeight = correctedWeight == null ? weight : correctedWeight;
    require(!nextWeight.equals(weight) || replacement != null, "Empty/no-op correction");
    if (replacement != null) measurementForCommand(replacement, next, now, actor);
    var selected = replacement == null ? measurement : replacement;
    var date = nextWeight.collectedAt().atZone(zone(attribution.master().estateTimezone())).toLocalDate();
    require(!date.isBefore(now.atZone(zone(attribution.master().estateTimezone())).toLocalDate())
        || nextWeight.backdateReason() != null, "Historical correction requires backdate reason");
    return new LatexCollection(id, attribution, collectionReference, replacementOfCollectionId, nextWeight,
        provenance, selected == null ? Status.PENDING_DRC : Status.CALCULATED, selected,
        calculate(nextWeight.wetLatexKg(), selected == null ? null : selected.percent()),
        next, createdAt, now, createdBy, actor);
  }

  public LatexCollection voidCollection(long expectedVersion, String reason, Clock clock, String actor) {
    var next = nextVersion(expectedVersion);
    text(reason, 2000, "void reason");
    var now = now(clock);
    require(!now.isBefore(updatedAt), "Command audit time moved backwards");
    return new LatexCollection(id, attribution, collectionReference, replacementOfCollectionId, weight,
        provenance, Status.VOIDED, measurement, calculation, next, createdAt, now, createdBy, actor);
  }
}
