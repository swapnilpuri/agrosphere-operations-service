package com.agrosphere.operations.application;

import static com.agrosphere.operations.application.Requests.*;
import static com.agrosphere.operations.domain.Rules.*;

import com.agrosphere.operations.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TappingService {
  private final TappingRepository store;
  private final Access access;
  private final EstateValidation estates;
  private final DomainEvents events;
  private final Clock clock;

  public TappingService(
      TappingRepository store,
      Access access,
      EstateValidation estates,
      DomainEvents events,
      Clock clock) {
    this.store = store;
    this.access = access;
    this.estates = estates;
    this.events = events;
    this.clock = clock;
  }

  private Instant now() {
    return clock.instant();
  }

  private void version(long actual, Long expected) {
    if (expected == null || actual != expected)
      throw new OptimisticLockingFailureException("Stale resource version");
  }

  public TappingSchedule create(ScheduleWrite r) {
    access.schedule(r.estateId());
    estates.validate(r.estateId(), r.blockId());
    var n = now();
    var s =
        new TappingSchedule(
            UUID.randomUUID(),
            r.estateId(),
            r.blockId(),
            r.name(),
            r.recurrenceRule(),
            r.plannedTreeCount(),
            r.effectiveFrom(),
            r.effectiveTo(),
            ScheduleStatus.DRAFT,
            n,
            n,
            0,
            access.actor(),
            access.actor());
    store.add(s);
    return s;
  }

  public TappingSchedule schedule(UUID id) {
    var s = store.schedule(id);
    access.read(s.estateId());
    if (access.workerOnly())
      throw new org.springframework.security.access.AccessDeniedException(
          "Schedules require read role");
    return s;
  }

  public TappingSchedule edit(UUID id, ScheduleEdit r) {
    var s = schedule(id);
    access.schedule(s.estateId());
    version(s.version(), r.version());
    require(s.status() == ScheduleStatus.DRAFT, "Only drafts can be edited");
    var edited =
        new TappingSchedule(
            id,
            s.estateId(),
            s.blockId(),
            r.name(),
            r.recurrenceRule(),
            r.plannedTreeCount(),
            r.effectiveFrom(),
            r.effectiveTo(),
            s.status(),
            s.createdAt(),
            now(),
            s.version() + 1,
            s.createdBy(),
            access.actor());
    store.save(edited, s.version());
    return edited;
  }

  public TappingSchedule lifecycle(UUID id, Version r, ScheduleStatus target) {
    var s = schedule(id);
    access.schedule(s.estateId());
    if (s.status() == target) return s;
    version(s.version(), r.version());
    if (target == ScheduleStatus.ACTIVE) estates.validate(s.estateId(), s.blockId());
    var changed = s.transition(target, now(), access.actor());
    store.save(changed, s.version());
    if (target == ScheduleStatus.ACTIVE)
      events.emit(
          "TappingScheduleActivated", id, changed.version(), s.estateId(), s.blockId(), changed);
    return changed;
  }

  public List<TappingAssignment> generate(UUID id, Generate r) {
    var initial = schedule(id);
    access.schedule(initial.estateId());
    store.lockSchedule(id);
    var s = store.schedule(id);
    version(s.version(), r.version());
    require(s.status() == ScheduleStatus.ACTIVE, "Only active schedules generate assignments");
    estates.validate(s.estateId(), s.blockId());
    var result = new ArrayList<TappingAssignment>();
    for (var date :
        occurrences(s.recurrenceRule(), s.effectiveFrom(), s.effectiveTo(), r.from(), r.to())) {
      var existing = store.occurrence(id, date);
      if (existing != null) {
        require(
            existing.workerId().equals(r.workerId()),
            "Occurrence already belongs to another worker");
        result.add(existing);
        continue;
      }
      result.add(
          newAssignment(id, s.estateId(), s.blockId(), r.workerId(), date, s.plannedTreeCount()));
    }
    return List.copyOf(result);
  }

  public TappingAssignment createAssignment(AssignmentWrite r) {
    access.manage(r.estateId());
    estates.validate(r.estateId(), r.blockId());
    return newAssignment(
        null, r.estateId(), r.blockId(), r.workerId(), r.tappingDate(), r.plannedTreeCount());
  }

  private TappingAssignment newAssignment(
      UUID schedule, UUID estate, UUID block, String worker, LocalDate date, int trees) {
    var n = now();
    var a =
        new TappingAssignment(
            UUID.randomUUID(),
            schedule,
            estate,
            block,
            worker,
            date,
            trees,
            AssignmentStatus.PLANNED,
            n,
            n,
            0,
            access.actor(),
            access.actor());
    store.add(a);
    emit("TappingAssignmentCreated", a);
    return a;
  }

  private void emit(String type, TappingAssignment a) {
    events.emit(
        type,
        a.id(),
        a.version(),
        a.estateId(),
        a.blockId(),
        new AssignmentView(a, store.record(a.id()), store.missed(a.id())));
  }

  public AssignmentView assignment(UUID id) {
    var a = store.assignment(id);
    access.assignment(a, false);
    return new AssignmentView(a, store.record(id), store.missed(id));
  }

  public TappingAssignment worker(UUID id, WorkerWrite r) {
    var a = store.assignment(id);
    access.manage(a.estateId());
    version(a.version(), r.version());
    require(a.status() == AssignmentStatus.PLANNED, "Only planned work can be reassigned");
    var edited =
        new TappingAssignment(
            a.id(),
            a.scheduleId(),
            a.estateId(),
            a.blockId(),
            r.workerId(),
            a.tappingDate(),
            a.plannedTreeCount(),
            a.status(),
            a.createdAt(),
            now(),
            a.version() + 1,
            a.createdBy(),
            access.actor());
    store.save(edited, a.version());
    return edited;
  }

  private void date(TappingAssignment a, boolean start) {
    String zone = estates.validate(a.estateId(), a.blockId());
    LocalDate today = businessDate(now(), zone);
    require(!a.tappingDate().isAfter(today), "Future work cannot be executed or marked missed");
    if (start)
      require(a.tappingDate().equals(today), "Work must start on the estate-local tapping date");
  }

  public AssignmentView start(UUID id, Version r) {
    var a = store.assignment(id);
    access.assignment(a, true);
    if (a.status() == AssignmentStatus.IN_PROGRESS) return assignment(id);
    version(a.version(), r.version());
    date(a, true);
    var n = now();
    var changed = a.transition(AssignmentStatus.IN_PROGRESS, n, access.actor());
    store.save(changed, a.version());
    store.add(
        new TappingRecord(
            UUID.randomUUID(),
            id,
            a.workerId(),
            a.estateId(),
            a.blockId(),
            a.tappingDate(),
            a.plannedTreeCount(),
            0,
            n,
            null,
            null,
            access.actor(),
            n,
            n));
    emit("TappingAssignmentStarted", changed);
    return assignment(id);
  }

  public AssignmentView complete(UUID id, Complete r) {
    var a = store.assignment(id);
    access.assignment(a, true);
    actual(a.plannedTreeCount(), r.actualTreeCount());
    var record = store.record(id);
    if (a.status() == AssignmentStatus.COMPLETED) {
      require(
          record != null
              && record.actualTreeCount() == r.actualTreeCount()
              && Objects.equals(record.notes(), r.notes()),
          "Completion retry differs from recorded work");
      return assignment(id);
    }
    version(a.version(), r.version());
    require(record != null && record.completedAt() == null, "Started execution record required");
    var n = now();
    require(!n.isBefore(record.startedAt()), "Completion precedes start");
    var changed = a.transition(AssignmentStatus.COMPLETED, n, access.actor());
    store.save(changed, a.version());
    store.completeRecord(id, r.actualTreeCount(), r.notes(), n);
    emit("TappingCompleted", changed);
    return assignment(id);
  }

  public AssignmentView missed(UUID id, Missed r) {
    var a = store.assignment(id);
    access.assignment(a, true);
    require(r.reasonCode() != null, "Missed reason required");
    if (a.status() == AssignmentStatus.MISSED) {
      var old = store.missed(id);
      require(
          old.reasonCode() == r.reasonCode() && Objects.equals(old.remarks(), r.remarks()),
          "Missed retry differs from recorded reason");
      return assignment(id);
    }
    version(a.version(), r.version());
    date(a, false);
    var changed = a.transition(AssignmentStatus.MISSED, now(), access.actor());
    store.save(changed, a.version());
    store.add(new TappingMissedReason(id, r.reasonCode(), r.remarks(), now(), access.actor()));
    emit("TappingMissed", changed);
    return assignment(id);
  }

  public AssignmentView cancel(UUID id, Version r) {
    var a = store.assignment(id);
    access.manage(a.estateId());
    if (a.status() == AssignmentStatus.CANCELLED) return assignment(id);
    version(a.version(), r.version());
    var changed = a.transition(AssignmentStatus.CANCELLED, now(), access.actor());
    store.save(changed, a.version());
    emit("TappingAssignmentCancelled", changed);
    return assignment(id);
  }

  public Page<TappingSchedule> schedules(Filter f) {
    return store.schedules(f);
  }

  public Page<TappingAssignment> assignments(Filter f) {
    return store.assignments(f);
  }

  public Page<TappingRecord> records(Filter f) {
    return store.records(f);
  }

  public Map<String, Object> summary(Filter f, String group) {
    require(
        Set.of("total", "worker", "block").contains(group),
        "groupBy must be total, worker or block");
    require(f.status() == null, "Summary counts all statuses");
    var rows = store.summary(f, group);
    for (var row : rows)
      row.put(
          "completion_percentage",
          percentage(
              ((Number) row.get("completed_assignments")).longValue(),
              ((Number) row.get("planned_assignments")).longValue()));
    return Map.of("groupBy", group, "content", rows);
  }
}
