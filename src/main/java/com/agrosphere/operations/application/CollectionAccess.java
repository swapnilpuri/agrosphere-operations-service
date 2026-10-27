package com.agrosphere.operations.application;

import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Positive permissions for collection operations; read-role unions never grant writes. */
@Component
public class CollectionAccess {
  public enum Operation { READ, CREATE, DRC, CORRECT, VOID, REPLACE }
  private final Access access;

  public CollectionAccess(Access access) { this.access = access; }

  private boolean manager() {
    return access.admin() || access.role("ESTATE_ADMIN", "ESTATE_MANAGER");
  }

  private boolean supervisor() { return manager() || access.role("FIELD_SUPERVISOR"); }

  private List<UUID> trustedEstates() {
    access.actor();
    if (!access.admin() && !access.role("ESTATE_ADMIN", "ESTATE_MANAGER", "FIELD_SUPERVISOR",
        "FIELD_WORKER", "AUDITOR", "VIEWER")) throw new AccessDeniedException("Collection role required");
    if (access.admin()) return null;
    Object claim = access.authentication().getToken().getClaim("estate_ids");
    if (!(claim instanceof Collection<?> values)) throw new AccessDeniedException("Trusted estate scope required");
    var result = new ArrayList<UUID>();
    for (Object value : values) {
      if (!(value instanceof String s) || s.length() != 36) throw new AccessDeniedException("Invalid estate scope");
      try {
        var id = UUID.fromString(s);
        if (!id.toString().equalsIgnoreCase(s)) throw new IllegalArgumentException();
        result.add(id);
      } catch (IllegalArgumentException ex) {
        throw new AccessDeniedException("Invalid estate scope");
      }
    }
    return List.copyOf(result);
  }

  public void authorize(Operation operation, UUID estateId, String executionWorker) {
    var estates = trustedEstates();
    if (estateId == null || (estates != null && !estates.contains(estateId)))
      throw new AccessDeniedException("Estate denied");
    boolean ownWorker = access.role("FIELD_WORKER") && access.actor().equals(executionWorker);
    boolean allowed = operation != null && switch (operation) {
      case READ -> !access.workerOnly() || ownWorker;
      case CREATE -> supervisor() || ownWorker;
      case DRC -> supervisor();
      case CORRECT, VOID, REPLACE -> manager();
    };
    if (!allowed) throw new AccessDeniedException("Collection operation denied");
  }

  /** Apply before SQL pagination or aggregation, including omitted-filter requests. */
  public CollectionScope readScope(UUID requestedEstate, String requestedWorker) {
    var estates = trustedEstates();
    if (requestedEstate != null) {
      if (estates != null && !estates.contains(requestedEstate)) throw new AccessDeniedException("Estate filter denied");
      estates = List.of(requestedEstate);
    }
    if (access.workerOnly()) {
      if (requestedWorker != null && !access.actor().equals(requestedWorker))
        throw new AccessDeniedException("Worker filter denied");
      requestedWorker = access.actor();
    }
    return new CollectionScope(estates, requestedWorker);
  }
}
