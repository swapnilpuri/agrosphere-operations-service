package com.agrosphere.operations.alerts.domain;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/** AN-1 pure policy; inputs must originate from independently verified JWT/source state. */
public final class AlertRules {
  private AlertRules() {}
  public static final String RULE = "CROP_HEALTH_HIGH_V1";
  public enum State { ACTIVE, INACTIVE }
  public enum InactiveReason { RESOLVED, VOIDED, BELOW_THRESHOLD }
  public enum TransitionKind { RAISED, SOURCE_UPDATED, DEACTIVATED, REACTIVATED, ESCALATED, ACKNOWLEDGED }
  public enum Permission { READ, MANAGER, HISTORY }

  public record Identity(String issuer, String subject) {
    public Identity {
      require(issuer != null && issuer.length() <= 2048, "Trusted issuer required");
      try {
        URI uri = URI.create(issuer);
        require(uri.getScheme() != null && Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null,
            "Invalid trusted issuer");
      } catch (IllegalArgumentException e) { throw new Invalid("Invalid trusted issuer"); }
      subject = com.agrosphere.operations.crophealth.domain.CropHealthRules.subject(subject);
    }
  }
  public record Actor(Identity identity, Set<String> roles, Set<UUID> estates) {
    public Actor { Objects.requireNonNull(identity); roles=Set.copyOf(roles); estates=Set.copyOf(estates); }
    public void authorize(UUID estate, Permission permission) {
      if (estate == null || !(roles.contains("PLATFORM_ADMIN") || estates.contains(estate))) throw new Denied("Alert estate denied");
      boolean manager = roles.contains("PLATFORM_ADMIN") || roles.contains("ESTATE_ADMIN") || roles.contains("ESTATE_MANAGER");
      boolean permitted = switch(permission) {
        case MANAGER -> manager;
        case HISTORY -> manager || roles.contains("AUDITOR");
        case READ -> manager || roles.contains("AUDITOR") || roles.contains("FIELD_SUPERVISOR");
      };
      if (!permitted) throw new Denied("Alert permission denied");
    }
  }
  public record Source(UUID incidentId, UUID estateId, UUID blockId, ZoneId estateTimezone,
      Provenance provenance, long version, Instant observedAt, Category category, Severity severity, Status status) {
    public Source {
      Objects.requireNonNull(incidentId); Objects.requireNonNull(estateId); Objects.requireNonNull(blockId);
      Objects.requireNonNull(estateTimezone); developmentOnly(provenance); require(version>=0,"Invalid source version");
      precise(observedAt); Objects.requireNonNull(category); Objects.requireNonNull(severity); Objects.requireNonNull(status);
    }
    public boolean qualifies() { return (status==Status.OPEN || status==Status.IN_REVIEW) && (severity==Severity.HIGH || severity==Severity.CRITICAL); }
    public InactiveReason inactiveReason() {
      return switch(status) { case RESOLVED -> InactiveReason.RESOLVED; case VOIDED -> InactiveReason.VOIDED;
        default -> InactiveReason.BELOW_THRESHOLD; };
    }
    public void sameIdentity(Source other) {
      conflict(incidentId.equals(other.incidentId) && estateId.equals(other.estateId) && blockId.equals(other.blockId)
          && estateTimezone.equals(other.estateTimezone) && provenance==other.provenance,"Source identity changed");
    }
  }
  public static long next(long value) { conflict(value<Long.MAX_VALUE,"Version exhausted"); return value+1; }
  public static Instant commandTime(Clock clock) { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
}
