package com.agrosphere.operations.application;

import java.util.List;
import java.util.UUID;

/** Trusted internal query scope. Null estate list denotes explicitly global admin access. */
public record CollectionScope(List<UUID> estateIds, String workerId) {
  public CollectionScope {
    if (estateIds != null) estateIds = List.copyOf(estateIds);
    if (workerId != null && (workerId.isBlank() || workerId.length() > 200))
      throw new IllegalArgumentException("Invalid worker scope");
  }
}
