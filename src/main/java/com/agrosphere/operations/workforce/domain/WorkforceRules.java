package com.agrosphere.operations.workforce.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pure WA-1 rules. No authentication, database, framework or sibling dependency. */
public final class WorkforceRules {
  private WorkforceRules() {}

  public enum Provenance { REAL, SIMULATED }
  public enum AttendanceStatus { PRESENT, ABSENT, EXCUSED, VOIDED }
  public interface Aggregate {
    UUID id();
    Audit audit();
  }
  public static class Invalid extends RuntimeException {
    public Invalid(String message) { super(message); }
  }
  public static class Conflict extends RuntimeException {
    public Conflict(String message) { super(message); }
  }
  public static class Denied extends RuntimeException {
    public Denied(String message) { super(message); }
  }

  public static void require(boolean condition, String message) {
    if (!condition) throw new Invalid(message);
  }

  private static boolean whitespace(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
  }

  public static String text(String value, int limit) {
    require(value != null, "Text is required");
    int first = 0, last = value.length();
    while (first < last && whitespace(value.codePointAt(first))) first += Character.charCount(value.codePointAt(first));
    while (last > first && whitespace(value.codePointBefore(last))) last -= Character.charCount(value.codePointBefore(last));
    String normalized = value.substring(first, last);
    require(!normalized.isEmpty() && normalized.codePointCount(0, normalized.length()) <= limit, "Invalid text length");
    return normalized;
  }

  public static String reason(String value) {
    require(value != null && value.codePoints().noneMatch(Character::isISOControl), "Reason cannot contain controls");
    return text(value, 1000);
  }

  public static String code(String value) {
    String normalized = text(value, 64);
    require(normalized.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"), "Invalid worker code");
    return normalized.toUpperCase(Locale.ROOT);
  }

  /** Opaque signed identity is validated but never trimmed, case-folded or remapped. */
  public static String subject(String value) {
    require(value != null && !value.isEmpty() && value.codePointCount(0, value.length()) <= 200 &&
        value.codePoints().anyMatch(c -> !whitespace(c)), "Invalid signed subject");
    return value;
  }

  public static Instant now(Clock clock) {
    return Objects.requireNonNull(clock).instant().truncatedTo(ChronoUnit.MICROS);
  }

  /** REAL enablement requires a separately reviewed implemented D09 policy, not a flag. */
  public static void developmentOnly(Provenance provenance) {
    if (provenance != Provenance.SIMULATED) throw new Denied("REAL workforce collection is disabled pending D09");
  }

  /** Built only from a validated JWT by a future adapter; caller request fields never supply it. */
  public record Actor(String subject, Set<String> roles, Set<UUID> estateIds) {
    public Actor {
      subject = WorkforceRules.subject(subject);
      roles = Set.copyOf(roles);
      estateIds = Set.copyOf(estateIds);
    }
    public boolean platform() { return roles.contains("PLATFORM_ADMIN"); }
    public boolean scoped(UUID estateId) { return estateId != null && (platform() || estateIds.contains(estateId)); }
    public boolean manager(UUID estateId) {
      return scoped(estateId) && (platform() || roles.contains("ESTATE_ADMIN") || roles.contains("ESTATE_MANAGER"));
    }
    public void requireManager(UUID estateId) {
      if (!manager(estateId)) throw new Denied("Workforce management denied");
    }
    public void requireRead(UUID estateId) {
      if (!(manager(estateId) || scoped(estateId) && (roles.contains("FIELD_SUPERVISOR") || roles.contains("AUDITOR"))))
        throw new Denied("Workforce read denied");
    }
    public void requireHistory(UUID estateId) {
      if (!(manager(estateId) || scoped(estateId) && roles.contains("AUDITOR"))) throw new Denied("Workforce history denied");
    }
    public void requireRecord(UUID estateId, LocalDate date, LocalDate today, String backdateReason) {
      require(date != null && !date.isAfter(today), "Future attendance is invalid");
      if (!(manager(estateId) || scoped(estateId) && roles.contains("FIELD_SUPERVISOR") && date.equals(today)))
        throw new Denied("Attendance recording denied");
      if (date.isBefore(today)) reason(backdateReason);
      else if (backdateReason != null) reason(backdateReason);
    }
  }

  public record Command(Actor actor, Clock clock, UUID correlationId) {
    public Command {
      Objects.requireNonNull(actor); Objects.requireNonNull(clock); Objects.requireNonNull(correlationId);
    }
    public Instant instant() { return now(clock); }
  }

  /** Active canonical Estate response, validated by the reference port before domain use. */
  public record EstateContext(UUID estateId, ZoneId timezone) {
    public EstateContext { Objects.requireNonNull(estateId); Objects.requireNonNull(timezone); }
    public LocalDate today(Instant instant) { return instant.atZone(timezone).toLocalDate(); }
  }

  public record Audit(long version, Instant createdAt, Instant updatedAt, String createdBy, String updatedBy) {
    public Audit {
      require(version >= 0, "Invalid version");
      Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
      require(!updatedAt.isBefore(createdAt), "Audit clock reversed");
      require(createdAt.equals(createdAt.truncatedTo(ChronoUnit.MICROS)) &&
          updatedAt.equals(updatedAt.truncatedTo(ChronoUnit.MICROS)), "Audit precision exceeds microseconds");
      createdBy = subject(createdBy); updatedBy = subject(updatedBy);
    }
    public static Audit create(Command command, Instant at) {
      return new Audit(0, at, at, command.actor().subject(), command.actor().subject());
    }
    public void expect(long expected) {
      if (version != expected) throw new Conflict("Stale workforce version");
    }
    public Audit next(Command command, Instant at) {
      require(version < Long.MAX_VALUE && !at.isBefore(updatedAt), "Invalid audit advance");
      return new Audit(version + 1, createdAt, at, createdBy, command.actor().subject());
    }
  }
}
