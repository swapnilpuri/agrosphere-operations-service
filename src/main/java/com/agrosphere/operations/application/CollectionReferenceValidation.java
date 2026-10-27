package com.agrosphere.operations.application;

import com.agrosphere.operations.domain.LatexCollection.MasterSnapshot;
import java.util.UUID;

/** Additive collection-specific master metadata; the 3A timezone port is unchanged. */
public interface CollectionReferenceValidation {
  MasterSnapshot validateCollection(UUID estateId, UUID blockId);
}
