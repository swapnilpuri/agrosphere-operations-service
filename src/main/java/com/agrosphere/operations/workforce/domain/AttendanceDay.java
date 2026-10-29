package com.agrosphere.operations.workforce.domain;

import static com.agrosphere.operations.workforce.domain.WorkforceRules.*;

import java.time.*;
import java.util.*;

public record AttendanceDay(UUID id, Attribution attribution, AttendanceStatus status, Audit audit) implements Aggregate {
  public record Attribution(UUID estateId, UUID workerRegistrationId, LocalDate attendanceDate,
      String estateTimezone, String workerCodeSnapshot, UUID divisionIdSnapshot,
      long workerVersionSnapshot, UUID activePeriodId, Provenance dataProvenance) {
    public Attribution {
      Objects.requireNonNull(estateId); Objects.requireNonNull(workerRegistrationId); Objects.requireNonNull(attendanceDate);
      estateTimezone = ZoneId.of(estateTimezone).getId(); workerCodeSnapshot = code(workerCodeSnapshot);
      Objects.requireNonNull(activePeriodId); Objects.requireNonNull(dataProvenance);
      require(workerVersionSnapshot >= 0, "Invalid worker snapshot version");
    }
  }

  public AttendanceDay {
    Objects.requireNonNull(id); Objects.requireNonNull(attribution); Objects.requireNonNull(status); Objects.requireNonNull(audit);
    require(status != AttendanceStatus.VOIDED || audit.version() > 0, "Initial observation cannot be voided");
  }

  private static Attribution snapshot(WorkerRegistration worker, LocalDate date, EstateContext estate) {
    require(estate.estateId().equals(worker.estateId()), "Estate identity mismatch");
    var period = worker.periodOn(date).orElseThrow(() -> new Invalid("Registration is not active on attendance date"));
    return new Attribution(worker.estateId(), worker.id(), date, estate.timezone().getId(), worker.workerCode(),
        worker.divisionId(), worker.audit().version(), period.id(), worker.dataProvenance());
  }

  public static AttendanceDay record(UUID id, WorkerRegistration worker, LocalDate date, AttendanceStatus status,
      EstateContext estate, String backdateReason, Command command) {
    developmentOnly(worker.dataProvenance());
    Instant at = command.instant();
    command.actor().requireRecord(worker.estateId(), date, estate.today(at), backdateReason);
    require(status != null && status != AttendanceStatus.VOIDED, "Observation status required");
    return new AttendanceDay(id, snapshot(worker, date, estate), status, Audit.create(command, at));
  }

  public AttendanceDay correct(long version, AttendanceStatus changedStatus, String changeReason, Command command) {
    command.actor().requireManager(attribution.estateId()); developmentOnly(attribution.dataProvenance());
    audit.expect(version); reason(changeReason);
    require(status != AttendanceStatus.VOIDED && changedStatus != null && changedStatus != AttendanceStatus.VOIDED,
        "Only nonvoided observations may be corrected");
    require(changedStatus != status, "No attendance change");
    return new AttendanceDay(id, attribution, changedStatus, audit.next(command, command.instant()));
  }

  public AttendanceDay voidDay(long version, String changeReason, Command command) {
    command.actor().requireManager(attribution.estateId()); developmentOnly(attribution.dataProvenance());
    audit.expect(version); reason(changeReason); require(status != AttendanceStatus.VOIDED, "Day is already voided");
    return new AttendanceDay(id, attribution, AttendanceStatus.VOIDED, audit.next(command, command.instant()));
  }

  public AttendanceDay rerecord(long version, WorkerRegistration currentWorker, AttendanceStatus changedStatus,
      EstateContext estate, String changeReason, Command command) {
    command.actor().requireManager(attribution.estateId()); developmentOnly(attribution.dataProvenance());
    audit.expect(version); reason(changeReason); require(status == AttendanceStatus.VOIDED, "Only voided days may be re-recorded");
    require(currentWorker.id().equals(attribution.workerRegistrationId()) && currentWorker.estateId().equals(attribution.estateId()) &&
        currentWorker.dataProvenance() == attribution.dataProvenance(), "Daily attribution is immutable");
    require(changedStatus != null && changedStatus != AttendanceStatus.VOIDED, "Observation status required");
    Instant at = command.instant();
    command.actor().requireRecord(attribution.estateId(), attribution.attendanceDate(), estate.today(at), changeReason);
    return new AttendanceDay(id, snapshot(currentWorker, attribution.attendanceDate(), estate), changedStatus, audit.next(command, at));
  }
}
