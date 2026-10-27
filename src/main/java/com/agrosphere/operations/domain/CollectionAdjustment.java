package com.agrosphere.operations.domain;

import static com.agrosphere.operations.domain.CollectionRules.*;
import static com.agrosphere.operations.domain.Rules.require;

import java.time.Instant;
import java.util.UUID;

/** Audit of an applied correction/void, not an external approval workflow. */
public record CollectionAdjustment(UUID id, CollectionRevision before, CollectionRevision after,
    String reason, Instant approvedAt, String approvedBy, UUID correlationId) {
  public CollectionAdjustment {
    require(id != null && before != null && after != null && correlationId != null,
        "Adjustment identity and revisions required");
    reason = text(reason, 2000, "adjustment reason");
    approvedBy = subject(approvedBy);
    approvedAt = observation(approvedAt);
    require(before.collectionId().equals(after.collectionId())
        && before.version() < Long.MAX_VALUE && after.version() == before.version() + 1,
        "Adjustment must link consecutive revisions of one collection");
    require(after.commandKind() == CommandKind.CORRECT || after.commandKind() == CommandKind.VOID,
        "Adjustment requires correction/void revision");
    require(approvedAt.equals(after.recordedAt()) && approvedBy.equals(after.recordedBy())
        && correlationId.equals(after.correlationId()), "Adjustment audit mismatch");
    var old = before.snapshot();
    var current = after.snapshot();
    require(old.status() != Status.VOIDED && old.attribution().equals(current.attribution())
        && old.collectionReference().equals(current.collectionReference())
        && old.provenance() == current.provenance()
        && java.util.Objects.equals(old.replacementOfCollectionId(), current.replacementOfCollectionId())
        && old.createdAt().equals(current.createdAt()) && old.createdBy().equals(current.createdBy()),
        "Adjustment cannot change immutable identity/attribution");
  }
}
