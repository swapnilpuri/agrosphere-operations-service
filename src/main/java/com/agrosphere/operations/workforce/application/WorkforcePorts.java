package com.agrosphere.operations.workforce.application;

import com.agrosphere.operations.workforce.domain.*;
import com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import java.time.*;
import java.util.*;

/** WA-1 application boundaries. No adapter/controller/bean or runtime route is registered. */
public final class WorkforcePorts {
  private WorkforcePorts() {}

  public interface References {
    /** Authenticated, bounded, fail-closed active Estate validation; no copied database. */
    EstateContext activeEstate(UUID estateId);
    /** Return the canonical active division iff it belongs to this estate. */
    UUID activeDivision(UUID estateId, UUID divisionId);
  }

  public interface Ownership {
    /** Internal minimal lookup only; no personal fields returned before authorization. */
    UUID workerEstate(UUID workerRegistrationId);
    UUID attendanceEstate(UUID attendanceId);
  }

  public record ReadScope(Set<UUID> estateIds) {
    /** Non-null finite scope. Global platform reads resolve explicit requested estate. */
    public ReadScope { estateIds = Set.copyOf(estateIds); }
  }

  public interface Registrations {
    /** Resolve minimal ownership before protected data; authorization precedes this full read. */
    WorkerRegistration get(UUID id, ReadScope scope);
    WorkerRegistration lock(UUID id, ReadScope scope);
    /** Must run under the same root lock as attendance mutation or period closure. */
    Set<LocalDate> nonvoidedAttendanceDates(UUID workerRegistrationId, ReadScope scope);
    void insert(WorkerRegistration registration);
    void update(WorkerRegistration registration, long expectedVersion);
  }

  public interface Attendance {
    AttendanceDay get(UUID id, ReadScope scope);
    Optional<AttendanceDay> findDay(UUID workerRegistrationId, LocalDate date, ReadScope scope);
    void insert(AttendanceDay day);
    void update(AttendanceDay day, long expectedVersion);
  }

  public enum CommandKind { REGISTER, PROFILE, PERIOD_CLOSED, PERIOD_OPENED, RECORD, CORRECT, VOID, RERECORD }

  public record Revision<T extends Aggregate>(UUID aggregateId, long version, CommandKind commandKind, String reason,
      Instant recordedAt, String recordedBy, UUID correlationId, T snapshot) {
    public Revision {
      Objects.requireNonNull(aggregateId); Objects.requireNonNull(commandKind); Objects.requireNonNull(recordedAt);
      Objects.requireNonNull(correlationId); Objects.requireNonNull(snapshot);
      WorkforceRules.require(version >= 0, "Invalid revision version");
      recordedBy = WorkforceRules.subject(recordedBy);
      WorkforceRules.require(aggregateId.equals(snapshot.id()) && version == snapshot.audit().version() &&
          recordedAt.equals(snapshot.audit().updatedAt()) && recordedBy.equals(snapshot.audit().updatedBy()), "Revision/snapshot mismatch");
      if (commandKind != CommandKind.REGISTER && commandKind != CommandKind.RECORD) reason = WorkforceRules.reason(reason);
      else if (reason != null) reason = WorkforceRules.reason(reason);
    }
  }

  public record Receipt(String actor, UUID key, String method, String path, String normalizedRequestHash,
      int status, String body, String location) {
    public Receipt {
      actor = WorkforceRules.subject(actor); Objects.requireNonNull(key);
      Objects.requireNonNull(method); Objects.requireNonNull(path); Objects.requireNonNull(body);
      WorkforceRules.require(normalizedRequestHash != null && normalizedRequestHash.matches("[0-9a-f]{64}"), "Invalid command hash");
      WorkforceRules.require(status == 200 || status == 201, "Only successful receipts may be retained");
      WorkforceRules.require(status != 201 || location != null && !location.isBlank(), "Create receipt requires Location");
    }
  }

  public interface Evidence {
    void append(Revision<?> revision);
    void appendEvent(UUID eventId, String eventType, long aggregateVersion, String minimalSnapshotJson);
    void saveReceipt(Receipt receipt);
  }

  public interface Commands {
    /**
     * Authorize current operation/estate before this method, including receipt replay.
     * Reserve actor/key, compare canonical method/path/body hash, then lock root and
     * run work. One local transaction owns root + revision + receipt + outbox.
     * Replay returns the original receipt without invoking work; conflicts never
     * return another actor's body. No partial commit or cross-service transaction.
     */
    Receipt execute(String actor, UUID key, String method, String path, String normalizedRequestHash, Work work);
  }

  @FunctionalInterface
  public interface Work { Receipt apply(Registrations registrations, Attendance attendance, Evidence evidence); }
}
