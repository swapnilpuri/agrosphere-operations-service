package com.agrosphere.operations.workforce.domain;

import static com.agrosphere.operations.workforce.domain.WorkforceRules.*;

import java.time.*;
import java.util.*;

public record WorkerRegistration(UUID id, UUID estateId, String workerCode, String displayName,
    UUID divisionId, Provenance dataProvenance, List<ActivePeriod> activePeriods, Audit audit) implements Aggregate {

  public record ActivePeriod(UUID id, LocalDate activeFrom, LocalDate activeTo,
      long introducedVersion, Long closedVersion) {
    public ActivePeriod {
      Objects.requireNonNull(id); Objects.requireNonNull(activeFrom);
      require(activeTo == null || activeTo.isAfter(activeFrom), "Empty/reversed active period");
      require(introducedVersion >= 0 && (closedVersion == null || closedVersion >= introducedVersion), "Invalid period versions");
      require((activeTo == null) == (closedVersion == null), "Period closure evidence mismatch");
    }
    public boolean includes(LocalDate date) {
      return !date.isBefore(activeFrom) && (activeTo == null || date.isBefore(activeTo));
    }
  }

  public WorkerRegistration {
    Objects.requireNonNull(id); Objects.requireNonNull(estateId); Objects.requireNonNull(dataProvenance);
    Objects.requireNonNull(audit);
    workerCode = code(workerCode); displayName = text(displayName, 200);
    activePeriods = List.copyOf(activePeriods);
    require(!activePeriods.isEmpty(), "An initial active period is required");
    var ordered = activePeriods.stream().sorted(Comparator.comparing(ActivePeriod::activeFrom)).toList();
    var ids = new HashSet<UUID>();
    for (int i = 0; i < ordered.size(); i++) {
      var period = ordered.get(i);
      require(ids.add(period.id()), "Duplicate period id");
      require(period.introducedVersion() <= audit.version() &&
          (period.closedVersion() == null || period.closedVersion() <= audit.version()), "Period after root revision");
      if (i > 0) {
        var end = ordered.get(i - 1).activeTo();
        if (end == null || end.isAfter(period.activeFrom())) throw new Conflict("Overlapping active periods");
      }
    }
  }

  public static WorkerRegistration register(UUID id, UUID firstPeriodId, EstateContext estate,
      String workerCode, String displayName, UUID validatedDivisionId, LocalDate activeFrom,
      Provenance provenance, Command command) {
    command.actor().requireManager(estate.estateId()); developmentOnly(provenance);
    Instant at = command.instant();
    require(activeFrom != null && !activeFrom.isAfter(estate.today(at)), "Future registration is invalid");
    return new WorkerRegistration(id, estate.estateId(), workerCode, displayName, validatedDivisionId, provenance,
        List.of(new ActivePeriod(firstPeriodId, activeFrom, null, 0, null)), Audit.create(command, at));
  }

  public Optional<ActivePeriod> periodOn(LocalDate date) {
    Objects.requireNonNull(date);
    return activePeriods.stream().filter(p -> p.includes(date)).findFirst();
  }

  public WorkerRegistration edit(long version, String newDisplayName, boolean divisionProvided,
      UUID validatedDivisionId, String changeReason, Command command) {
    command.actor().requireManager(estateId); developmentOnly(dataProvenance); audit.expect(version); reason(changeReason);
    String name = newDisplayName == null ? displayName : text(newDisplayName, 200);
    UUID division = divisionProvided ? validatedDivisionId : divisionId;
    require(!name.equals(displayName) || !Objects.equals(division, divisionId), "No profile change");
    return new WorkerRegistration(id, estateId, workerCode, name, division, dataProvenance, activePeriods,
        audit.next(command, command.instant()));
  }

  /** Adapter must lock this registration and read retained dates within the same transaction. */
  public WorkerRegistration closePeriod(long version, UUID periodId, LocalDate activeTo,
      String changeReason, Set<LocalDate> nonvoidedAttendanceDates, ZoneId estateTimezone, Command command) {
    command.actor().requireManager(estateId); developmentOnly(dataProvenance); audit.expect(version); reason(changeReason);
    Objects.requireNonNull(nonvoidedAttendanceDates); Objects.requireNonNull(estateTimezone);
    Instant at = command.instant();
    require(activeTo != null && !activeTo.isAfter(at.atZone(estateTimezone).toLocalDate().plusDays(1)), "Future period closure is invalid");
    var found = activePeriods.stream().filter(p -> p.id().equals(periodId)).findFirst()
        .orElseThrow(() -> new Invalid("Unknown active period"));
    require(found.activeTo() == null, "Period is already closed");
    var next = audit.next(command, at);
    var periods = activePeriods.stream().map(p -> p.id().equals(periodId)
        ? new ActivePeriod(p.id(), p.activeFrom(), activeTo, p.introducedVersion(), next.version()) : p).toList();
    var changed = new WorkerRegistration(id, estateId, workerCode, displayName, divisionId, dataProvenance, periods, next);
    if (nonvoidedAttendanceDates.stream().anyMatch(d -> changed.periodOn(d).isEmpty()))
      throw new Conflict("Closure would invalidate retained attendance");
    return changed;
  }

  public WorkerRegistration openPeriod(long version, UUID periodId, LocalDate activeFrom,
      String changeReason, EstateContext estate, Command command) {
    command.actor().requireManager(estateId); developmentOnly(dataProvenance); audit.expect(version); reason(changeReason);
    require(estate.estateId().equals(estateId), "Estate identity mismatch");
    Instant at = command.instant();
    require(activeFrom != null && !activeFrom.isAfter(estate.today(at)), "Future reactivation is invalid");
    if (activePeriods.stream().anyMatch(p -> p.activeTo() == null)) throw new Conflict("An open period already exists");
    var next = audit.next(command, at);
    var periods = new ArrayList<>(activePeriods);
    periods.add(new ActivePeriod(periodId, activeFrom, null, next.version(), null));
    return new WorkerRegistration(id, estateId, workerCode, displayName, divisionId, dataProvenance, periods, next);
  }
}
