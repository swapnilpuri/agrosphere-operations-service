package com.agrosphere.operations.domain;

import static com.agrosphere.operations.domain.CollectionRules.*;
import static com.agrosphere.operations.domain.Rules.require;

import java.time.Instant;
import java.util.UUID;

/** Full immutable snapshot, suitable for append-only persistence by the application. */
public record CollectionRevision(UUID collectionId, long version, CommandKind commandKind,
    LatexCollection snapshot, Instant recordedAt, String recordedBy, UUID correlationId) {
  public CollectionRevision {
    require(collectionId != null && snapshot != null && commandKind != null && correlationId != null,
        "Revision identity/context required");
    recordedBy = subject(recordedBy);
    recordedAt = observation(recordedAt);
    require(collectionId.equals(snapshot.id()) && version == snapshot.version()
        && recordedAt.equals(snapshot.updatedAt()) && recordedBy.equals(snapshot.updatedBy()),
        "Revision snapshot/audit mismatch");
    require((commandKind == CommandKind.CREATE) == (version == 0), "Creation revision must be zero");
    require((commandKind == CommandKind.VOID) == (snapshot.status() == Status.VOIDED), "Void revision state mismatch");
    if (commandKind == CommandKind.DRC) {
      require(snapshot.measurement() != null && snapshot.measurement().introducedVersion() == version,
          "Initial DRC revision must introduce measurement");
    }
  }
}
