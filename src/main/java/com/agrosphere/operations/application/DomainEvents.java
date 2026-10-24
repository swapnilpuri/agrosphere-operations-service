package com.agrosphere.operations.application;

import java.util.UUID;

/** Atomic local outbox persistence; never publishes to the broker on the request thread. */
public interface DomainEvents {
  void emit(String type, UUID id, long version, UUID estate, UUID block, Object payload);
}
