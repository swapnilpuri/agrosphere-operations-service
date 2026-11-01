package com.agrosphere.operations.crophealth.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** II-1 pure domain policy, deliberately independent of workforce and legacy role unions. */
public final class CropHealthRules {
  private CropHealthRules() {}
  public enum Provenance { REAL, SIMULATED, ESTIMATED, PREDICTED }
  public enum Outcome { CLEAR, FOLLOW_UP }
  public enum InspectionState { RECORDED, VOIDED }
  public enum Category { PEST_SIGNS, DISEASE_SIGNS, TREE_DAMAGE, OTHER_CROP_HEALTH }
  public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
  public enum Status { OPEN, IN_REVIEW, RESOLVED, VOIDED }
  public enum Kind { RECORD, REPORT, AMEND, TRIAGE, RESOLVE, REOPEN, VOID, CORRECT }
  public static class Invalid extends RuntimeException { public Invalid(String message) { super(message); } }
  public static class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }
  public static class Denied extends RuntimeException { public Denied(String message) { super(message); } }
  public static void require(boolean value, String message) { if (!value) throw new Invalid(message); }
  public static void conflict(boolean value, String message) { if (!value) throw new Conflict(message); }
  public static void developmentOnly(Provenance value) {
    if (value != Provenance.SIMULATED) throw new Denied("REAL/non-SIMULATED crop evidence is disabled");
  }
  private static boolean space(int c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
  public static String text(String value, int limit) {
    require(value != null && value.codePoints().noneMatch(Character::isISOControl), "Invalid text controls");
    int first = 0, last = value.length();
    while (first < last && space(value.codePointAt(first))) first += Character.charCount(value.codePointAt(first));
    while (last > first && space(value.codePointBefore(last))) last -= Character.charCount(value.codePointBefore(last));
    value = value.substring(first, last);
    require(!value.isEmpty() && value.codePointCount(0, value.length()) <= limit, "Invalid text length");
    return value;
  }
  public static String subject(String value) {
    require(value != null && !value.isEmpty() && value.codePointCount(0, value.length()) <= 200 &&
        value.codePoints().anyMatch(c -> !space(c)), "Valid signed subject required");
    return value; // Opaque signed identity; never trim/case-fold/remap.
  }
  public static Instant precise(Instant at) {
    require(at != null && at.equals(at.truncatedTo(ChronoUnit.MICROS)), "Time precision exceeds microseconds");
    return at;
  }
  public static Instant parseObservation(String value) {
    require(value != null && value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})"),
        "Explicit offset and microsecond precision required");
    try { return precise(OffsetDateTime.parse(value).toInstant()); }
    catch (DateTimeException e) { throw new Invalid("Invalid observation timestamp"); }
  }

  public record Actor(String subject, Set<String> roles, Set<UUID> estates) {
    public Actor { subject = CropHealthRules.subject(subject); roles = Set.copyOf(roles); estates = Set.copyOf(estates); }
    public boolean platform() { return roles.contains("PLATFORM_ADMIN"); }
    public boolean scoped(UUID estate) { return estate != null && (platform() || estates.contains(estate)); }
    public boolean manager(UUID estate) {
      return scoped(estate) && (platform() || roles.contains("ESTATE_ADMIN") || roles.contains("ESTATE_MANAGER"));
    }
    public void read(UUID estate) {
      if (!(manager(estate) || scoped(estate) && (roles.contains("FIELD_SUPERVISOR") || roles.contains("AUDITOR"))))
        throw new Denied("Crop evidence read denied");
    }
    public void manage(UUID estate) { if (!manager(estate)) throw new Denied("Crop evidence management denied"); }
    public void history(UUID estate) {
      if (!(manager(estate) || scoped(estate) && roles.contains("AUDITOR"))) throw new Denied("Crop history denied");
    }
    public void create(UUID estate, ZoneId zone, Instant observed, Instant now, String backdateReason) {
      precise(observed); require(!observed.isAfter(now), "Future observation");
      LocalDate date = observed.atZone(zone).toLocalDate(), today = now.atZone(zone).toLocalDate();
      if (!(manager(estate) || scoped(estate) && roles.contains("FIELD_SUPERVISOR") && date.equals(today)))
        throw new Denied("Crop evidence creation denied");
      if (date.isBefore(today)) text(backdateReason, 1000);
      else if (backdateReason != null) text(backdateReason, 1000);
    }
  }
  public record Command(Actor actor, Clock clock, UUID correlationId) {
    public Command { Objects.requireNonNull(actor); Objects.requireNonNull(clock); Objects.requireNonNull(correlationId); clock=Clock.fixed(clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC); }
    public Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
  }
  public record Context(UUID estateId, UUID blockId, ZoneId timezone) {
    public Context { Objects.requireNonNull(estateId); Objects.requireNonNull(blockId); Objects.requireNonNull(timezone); }
  }
  public record Attribution(UUID estateId, UUID blockId, ZoneId estateTimezone, Provenance dataProvenance) {
    public Attribution { Objects.requireNonNull(estateId); Objects.requireNonNull(blockId); Objects.requireNonNull(estateTimezone); developmentOnly(dataProvenance); }
    public static Attribution from(Context context, Provenance provenance) {
      return new Attribution(context.estateId(), context.blockId(), context.timezone(), provenance);
    }
  }
  public record Audit(long version, Instant createdAt, Instant updatedAt, String createdBy, String updatedBy) {
    public Audit {
      require(version >= 0, "Invalid version"); precise(createdAt); precise(updatedAt);
      require(!updatedAt.isBefore(createdAt), "Reversed audit clock");
      createdBy = subject(createdBy); updatedBy = subject(updatedBy);
    }
    public static Audit create(Command c) { Instant at = c.now(); return new Audit(0, at, at, c.actor().subject(), c.actor().subject()); }
    public void expect(long expected) { conflict(version == expected, "Stale crop evidence version"); }
    public Audit next(Command c) {
      require(version < Long.MAX_VALUE, "Version overflow");
      return new Audit(version+1, createdAt, c.now(), createdBy, c.actor().subject());
    }
  }
  public interface Root { UUID id(); Attribution attribution(); Audit audit(); }
  public record Report(Instant observedAt, Category category, Severity severity, String summary, String details) {
    public Report {
      precise(observedAt); Objects.requireNonNull(category); Objects.requireNonNull(severity);
      summary = text(summary, 200); details = text(details, 2000);
    }
  }
  public record Observation(Instant observedAt, Outcome outcome, String findings) {
    public Observation { precise(observedAt); Objects.requireNonNull(outcome); findings = text(findings, 2000); }
  }
  public record Revision(Root snapshot, Kind commandKind, String reason, Instant recordedAt, String recordedBy, UUID correlationId) {
    public Revision {
      Objects.requireNonNull(snapshot); Objects.requireNonNull(commandKind); Objects.requireNonNull(correlationId);
      precise(recordedAt); recordedBy = subject(recordedBy);
      boolean initial = commandKind == Kind.RECORD || commandKind == Kind.REPORT;
      require(initial ? snapshot.audit().version()==0 : snapshot.audit().version()>0, "Command/revision mismatch");
      require(snapshot instanceof FieldInspection ? Set.of(Kind.RECORD,Kind.CORRECT,Kind.VOID).contains(commandKind)
          : commandKind != Kind.RECORD, "Wrong root command kind");
      require(recordedAt.equals(snapshot.audit().updatedAt()) && recordedBy.equals(snapshot.audit().updatedBy()), "Audit mismatch");
      if (!initial || reason != null) reason = text(reason, 1000);
    }
    public static Revision of(Root root, Kind kind, String reason, Command c) {
      return new Revision(root,kind,reason,c.now(),c.actor().subject(),c.correlationId());
    }
  }
}
