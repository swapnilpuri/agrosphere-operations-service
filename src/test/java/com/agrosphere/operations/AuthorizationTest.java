package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;

import com.agrosphere.operations.application.Access;
import com.agrosphere.operations.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuthorizationTest {
  Access access = new Access();
  UUID estate = UUID.randomUUID();

  void login(String role, List<String> ids) {
    var jwt =
        Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .subject("worker")
            .claim("realm_access", Map.of("roles", List.of(role)))
            .claim("estate_ids", ids)
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void managerScoped() {
    login("ESTATE_MANAGER", List.of(estate.toString()));
    assertDoesNotThrow(() -> access.schedule(estate));
    assertThrows(AccessDeniedException.class, () -> access.read(UUID.randomUUID()));
  }

  @Test
  void auditorReadOnly() {
    login("AUDITOR", List.of(estate.toString()));
    access.read(estate);
    assertThrows(AccessDeniedException.class, () -> access.manage(estate));
  }

  @Test
  void viewerReadOnly() {
    login("VIEWER", List.of(estate.toString()));
    access.read(estate);
    assertThrows(AccessDeniedException.class, () -> access.manage(estate));
  }

  @Test
  void workerOnlyOwnAssignment() {
    login("FIELD_WORKER", List.of(estate.toString()));
    var a =
        new TappingAssignment(
            UUID.randomUUID(),
            null,
            estate,
            UUID.randomUUID(),
            "other",
            LocalDate.now(),
            10,
            Rules.AssignmentStatus.PLANNED,
            Instant.EPOCH,
            Instant.EPOCH,
            0,
            "actor",
            "actor");
    assertThrows(AccessDeniedException.class, () -> access.assignment(a, true));
  }

  @Test
  void supervisorCannotManageSchedules() {
    login("FIELD_SUPERVISOR", List.of(estate.toString()));
    access.manage(estate);
    assertThrows(AccessDeniedException.class, () -> access.schedule(estate));
  }

  @Test
  void malformedClaim() {
    login("ESTATE_MANAGER", List.of("invalid"));
    assertThrows(AccessDeniedException.class, () -> access.read(estate));
  }

  @Test
  void adminAllEstates() {
    login("PLATFORM_ADMIN", List.of());
    access.schedule(estate);
    assertNull(access.estates());
  }

  @Test
  void unknownRoleDenied() {
    login("other", List.of(estate.toString()));
    assertThrows(AccessDeniedException.class, () -> access.collection());
  }

  @Test
  void additionalReadRoleDoesNotRevokeOwnWorkerExecution() {
    var jwt =
        Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .subject("worker")
            .claim("realm_access", Map.of("roles", List.of("FIELD_WORKER", "VIEWER")))
            .claim("estate_ids", List.of(estate.toString()))
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    var own =
        new TappingAssignment(
            UUID.randomUUID(),
            null,
            estate,
            UUID.randomUUID(),
            "worker",
            LocalDate.now(),
            10,
            Rules.AssignmentStatus.PLANNED,
            Instant.EPOCH,
            Instant.EPOCH,
            0,
            "actor",
            "actor");
    assertDoesNotThrow(() -> access.assignment(own, true));
    var other =
        new TappingAssignment(
            UUID.randomUUID(),
            null,
            estate,
            UUID.randomUUID(),
            "other",
            LocalDate.now(),
            10,
            Rules.AssignmentStatus.PLANNED,
            Instant.EPOCH,
            Instant.EPOCH,
            0,
            "actor",
            "actor");
    assertThrows(AccessDeniedException.class, () -> access.assignment(other, true));
  }
}
