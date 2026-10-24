package com.agrosphere.operations.application;

import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class Access {
  public JwtAuthenticationToken authentication() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a instanceof JwtAuthenticationToken j) return j;
    throw new AccessDeniedException("JWT required");
  }

  public String actor() {
    var sub = authentication().getToken().getSubject();
    if (sub == null || sub.isBlank() || sub.length() > 200)
      throw new AccessDeniedException("Valid subject required");
    return sub;
  }

  public boolean role(String... roles) {
    Object realm = authentication().getToken().getClaim("realm_access");
    return realm instanceof Map<?, ?> m
        && m.get("roles") instanceof Collection<?> c
        && Arrays.stream(roles).anyMatch(c::contains);
  }

  public boolean admin() {
    return role("PLATFORM_ADMIN", "PLANTATION_ADMIN");
  }

  public boolean workerOnly() {
    return !admin()
        && !role("ESTATE_ADMIN", "ESTATE_MANAGER", "FIELD_SUPERVISOR", "AUDITOR", "VIEWER")
        && role("FIELD_WORKER");
  }

  public List<UUID> estates() {
    if (admin()) return null;
    Object claim = authentication().getToken().getClaim("estate_ids");
    if (!(claim instanceof Collection<?> ids))
      throw new AccessDeniedException("Estate scope required");
    try {
      return ids.stream().map(Object::toString).map(UUID::fromString).toList();
    } catch (IllegalArgumentException e) {
      throw new AccessDeniedException("Invalid estate scope");
    }
  }

  public void read(UUID estate) {
    actor();
    if (!admin()
        && !role(
            "ESTATE_ADMIN",
            "ESTATE_MANAGER",
            "FIELD_SUPERVISOR",
            "FIELD_WORKER",
            "AUDITOR",
            "VIEWER")) throw new AccessDeniedException("Role required");
    var ids = estates();
    if (ids != null && !ids.contains(estate)) throw new AccessDeniedException("Estate denied");
  }

  public void manage(UUID estate) {
    read(estate);
    if (!admin() && !role("ESTATE_ADMIN", "ESTATE_MANAGER", "FIELD_SUPERVISOR"))
      throw new AccessDeniedException("Management role required");
  }

  public void schedule(UUID estate) {
    manage(estate);
    if (!admin() && !role("ESTATE_ADMIN", "ESTATE_MANAGER"))
      throw new AccessDeniedException("Schedule management role required");
  }

  public void assignment(com.agrosphere.operations.domain.TappingAssignment a, boolean write) {
    read(a.estateId());
    if (write) {
      if (admin() || role("ESTATE_ADMIN", "ESTATE_MANAGER", "FIELD_SUPERVISOR")) return;
      if (!role("FIELD_WORKER") || !actor().equals(a.workerId()))
        throw new AccessDeniedException("Assignment write denied");
    } else if (workerOnly() && !actor().equals(a.workerId())) {
      throw new AccessDeniedException("Worker denied");
    }
  }

  public void collection() {
    actor();
    if (!admin()
        && !role(
            "ESTATE_ADMIN",
            "ESTATE_MANAGER",
            "FIELD_SUPERVISOR",
            "FIELD_WORKER",
            "AUDITOR",
            "VIEWER")) throw new AccessDeniedException("Role required");
    estates();
  }
}
