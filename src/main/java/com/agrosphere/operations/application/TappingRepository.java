package com.agrosphere.operations.application;

import com.agrosphere.operations.domain.*;
import java.time.*;
import java.util.*;

/** Persistence port. Every write participates in the caller's local transaction. */
public interface TappingRepository {
  TappingSchedule schedule(UUID id);

  TappingAssignment assignment(UUID id);

  TappingRecord record(UUID assignmentId);

  TappingMissedReason missed(UUID assignmentId);

  TappingAssignment occurrence(UUID scheduleId, LocalDate date);

  void lockSchedule(UUID id);

  void add(TappingSchedule value);

  void add(TappingAssignment value);

  void add(TappingRecord value);

  void add(TappingMissedReason value);

  void save(TappingSchedule value, long expected);

  void save(TappingAssignment value, long expected);

  void completeRecord(UUID id, int actual, String notes, Instant now);

  Requests.Page<TappingSchedule> schedules(Requests.Filter filter);

  Requests.Page<TappingAssignment> assignments(Requests.Filter filter);

  Requests.Page<TappingRecord> records(Requests.Filter filter);

  List<Map<String, Object>> summary(Requests.Filter filter, String group);
}
