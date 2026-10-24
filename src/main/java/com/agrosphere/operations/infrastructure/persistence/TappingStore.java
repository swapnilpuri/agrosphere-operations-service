package com.agrosphere.operations.infrastructure.persistence;

import static com.agrosphere.operations.domain.Rules.*;

import com.agrosphere.operations.application.*;
import com.agrosphere.operations.domain.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class TappingStore implements TappingRepository {
  private final NamedParameterJdbcTemplate db;
  private final Access access;

  public TappingStore(NamedParameterJdbcTemplate db, Access access) {
    this.db = db;
    this.access = access;
  }

  static Instant time(ResultSet r, String c) throws SQLException {
    return r.getTimestamp(c).toInstant();
  }

  static UUID uuid(ResultSet r, String c) throws SQLException {
    return r.getObject(c, UUID.class);
  }

  static final RowMapper<TappingSchedule> S =
      (r, n) ->
          new TappingSchedule(
              uuid(r, "id"),
              uuid(r, "estate_id"),
              uuid(r, "block_id"),
              r.getString("name"),
              r.getString("recurrence_rule"),
              r.getInt("planned_tree_count"),
              r.getObject("effective_from", LocalDate.class),
              r.getObject("effective_to", LocalDate.class),
              ScheduleStatus.valueOf(r.getString("status")),
              time(r, "created_at"),
              time(r, "updated_at"),
              r.getLong("version"),
              r.getString("created_by"),
              r.getString("updated_by"));
  static final RowMapper<TappingAssignment> A =
      (r, n) ->
          new TappingAssignment(
              uuid(r, "id"),
              uuid(r, "schedule_id"),
              uuid(r, "estate_id"),
              uuid(r, "block_id"),
              r.getString("worker_id"),
              r.getObject("tapping_date", LocalDate.class),
              r.getInt("planned_tree_count"),
              AssignmentStatus.valueOf(r.getString("status")),
              time(r, "created_at"),
              time(r, "updated_at"),
              r.getLong("version"),
              r.getString("created_by"),
              r.getString("updated_by"));
  static final RowMapper<TappingRecord> R =
      (r, n) ->
          new TappingRecord(
              uuid(r, "id"),
              uuid(r, "assignment_id"),
              r.getString("worker_id"),
              uuid(r, "estate_id"),
              uuid(r, "block_id"),
              r.getObject("tapping_date", LocalDate.class),
              r.getInt("planned_tree_count"),
              r.getInt("actual_tree_count"),
              time(r, "started_at"),
              r.getTimestamp("completed_at") == null ? null : time(r, "completed_at"),
              r.getString("notes"),
              r.getString("created_by"),
              time(r, "created_at"),
              time(r, "updated_at"));

  public TappingSchedule schedule(UUID id) {
    return one("tapping_schedule", id, S);
  }

  public TappingAssignment assignment(UUID id) {
    return one("tapping_assignment", id, A);
  }

  private <T> T one(String table, UUID id, RowMapper<T> mapper) {
    var rows = db.query("SELECT * FROM " + table + " WHERE id=:id", Map.of("id", id), mapper);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
    return rows.getFirst();
  }

  public TappingRecord record(UUID id) {
    var rows =
        db.query("SELECT * FROM tapping_record WHERE assignment_id=:id", Map.of("id", id), R);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public TappingMissedReason missed(UUID id) {
    var rows =
        db.query(
            "SELECT * FROM tapping_missed_reason WHERE assignment_id=:id",
            Map.of("id", id),
            (r, n) ->
                new TappingMissedReason(
                    uuid(r, "assignment_id"),
                    MissedReasonCode.valueOf(r.getString("reason_code")),
                    r.getString("remarks"),
                    time(r, "recorded_at"),
                    r.getString("recorded_by")));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public TappingAssignment occurrence(UUID id, LocalDate date) {
    var rows =
        db.query(
            "SELECT * FROM tapping_assignment WHERE schedule_id=:id AND tapping_date=:date",
            Map.of("id", id, "date", date),
            A);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void lockSchedule(UUID id) {
    db.queryForObject(
        "SELECT id FROM tapping_schedule WHERE id=:id FOR UPDATE", Map.of("id", id), UUID.class);
  }

  public void add(TappingSchedule value) {
    insert("tapping_schedule", value);
  }

  public void add(TappingAssignment value) {
    insert("tapping_assignment", value);
  }

  public void add(TappingRecord value) {
    insert("tapping_record", value);
  }

  public void add(TappingMissedReason value) {
    insert("tapping_missed_reason", value);
  }

  private void insert(String table, Record row) {
    var values = new LinkedHashMap<String, Object>();
    try {
      for (var field : row.getClass().getRecordComponents()) {
        var value = field.getAccessor().invoke(row);
        values.put(
            field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT),
            value instanceof Enum<?> e
                ? e.name()
                : value instanceof Instant i ? Timestamp.from(i) : value);
      }
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    db.update(
        "INSERT INTO "
            + table
            + " ("
            + String.join(",", values.keySet())
            + ") VALUES ("
            + values.keySet().stream()
                .map(k -> ":" + k)
                .collect(java.util.stream.Collectors.joining(","))
            + ")",
        values);
  }

  public void save(TappingSchedule s, long expected) {
    var p =
        new MapSqlParameterSource()
            .addValue("id", s.id())
            .addValue("name", s.name())
            .addValue("rule", s.recurrenceRule())
            .addValue("trees", s.plannedTreeCount())
            .addValue("from", s.effectiveFrom())
            .addValue("to", s.effectiveTo())
            .addValue("status", s.status().name())
            .addValue("updated", Timestamp.from(s.updatedAt()))
            .addValue("actor", s.updatedBy())
            .addValue("v", expected)
            .addValue("next", s.version());
    check(
        db.update(
            "UPDATE tapping_schedule SET"
                + " name=:name,recurrence_rule=:rule,planned_tree_count=:trees,effective_from=:from,effective_to=:to,status=:status,updated_at=:updated,updated_by=:actor,version=:next"
                + " WHERE id=:id AND version=:v",
            p));
  }

  public void save(TappingAssignment a, long expected) {
    check(
        db.update(
            "UPDATE tapping_assignment SET"
                + " status=:status,worker_id=:worker,updated_at=:at,updated_by=:actor,version=:next"
                + " WHERE id=:id AND version=:v",
            Map.of(
                "status",
                a.status().name(),
                "worker",
                a.workerId(),
                "at",
                Timestamp.from(a.updatedAt()),
                "actor",
                a.updatedBy(),
                "next",
                a.version(),
                "id",
                a.id(),
                "v",
                expected)));
  }

  private static void check(int count) {
    if (count != 1) throw new OptimisticLockingFailureException("Stale resource version");
  }

  public void completeRecord(UUID id, int actual, String notes, Instant now) {
    db.update(
        "UPDATE tapping_record SET"
            + " actual_tree_count=:actual,notes=:notes,completed_at=:now,updated_at=:now WHERE"
            + " assignment_id=:id AND completed_at IS NULL",
        new MapSqlParameterSource()
            .addValue("actual", actual)
            .addValue("notes", notes)
            .addValue("now", Timestamp.from(now))
            .addValue("id", id));
  }

  public record Query(String where, MapSqlParameterSource params) {}

  public Query scope(Requests.Filter f, boolean schedule) {
    access.collection();
    var sql = new StringBuilder(" WHERE 1=1");
    var p = new MapSqlParameterSource();
    var estates = access.estates();
    if (estates != null) {
      if (estates.isEmpty()) sql.append(" AND false");
      else {
        sql.append(" AND estate_id IN (:estates)");
        p.addValue("estates", estates);
      }
    }
    if (f.estateId() != null) {
      access.read(f.estateId());
      sql.append(" AND estate_id=:estate");
      p.addValue("estate", f.estateId());
    }
    if (f.blockId() != null) {
      sql.append(" AND block_id=:block");
      p.addValue("block", f.blockId());
    }
    if (access.workerOnly()) {
      if (schedule)
        throw new org.springframework.security.access.AccessDeniedException(
            "Schedules require management/read role");
      sql.append(" AND worker_id=:actor");
      p.addValue("actor", access.actor());
    }
    if (!schedule) {
      if (f.workerId() != null) {
        sql.append(" AND worker_id=:worker");
        p.addValue("worker", f.workerId());
      }
      if (f.from() != null) {
        sql.append(" AND tapping_date>=:from");
        p.addValue("from", f.from());
      }
      if (f.to() != null) {
        sql.append(" AND tapping_date<=:to");
        p.addValue("to", f.to());
      }
    }
    if (f.status() != null) {
      try {
        if (schedule) ScheduleStatus.valueOf(f.status());
        else AssignmentStatus.valueOf(f.status());
      } catch (IllegalArgumentException e) {
        throw new Violation("Invalid status");
      }
      sql.append(" AND status=:status");
      p.addValue("status", f.status());
    }
    return new Query(sql.toString(), p);
  }

  private <T> Requests.Page<T> page(
      String table, Requests.Filter f, boolean schedule, RowMapper<T> mapper) {
    var q = scope(f, schedule);
    var total =
        db.queryForObject("SELECT count(*) FROM " + table + q.where(), q.params(), Long.class);
    q.params().addValue("limit", f.size()).addValue("offset", (long) f.page() * f.size());
    var content =
        db.query(
            "SELECT * FROM "
                + table
                + q.where()
                + " ORDER BY created_at,id LIMIT :limit OFFSET :offset",
            q.params(),
            mapper);
    return new Requests.Page<>(content, f.page(), f.size(), total);
  }

  public Requests.Page<TappingSchedule> schedules(Requests.Filter f) {
    return page("tapping_schedule", f, true, S);
  }

  public Requests.Page<TappingAssignment> assignments(Requests.Filter f) {
    return page("tapping_assignment", f, false, A);
  }

  public Requests.Page<TappingRecord> records(Requests.Filter f) {
    require(f.status() == null, "Record status filter is unsupported");
    return page("tapping_record", f, false, R);
  }

  private void pAdd(Query q, Requests.Filter f) {
    q.params()
        .addValue("summary_limit", f.size())
        .addValue("summary_offset", (long) f.page() * f.size());
  }

  public List<Map<String, Object>> summary(Requests.Filter f, String group) {
    var q = scope(f, false);
    String by =
        switch (group) {
          case "worker" -> "a.worker_id";
          case "block" -> "a.block_id";
          default -> null;
        };
    String select = by == null ? "" : by + " AS key,";
    String grouping =
        by == null
            ? ""
            : " GROUP BY "
                + by
                + " ORDER BY "
                + by
                + " LIMIT :summary_limit OFFSET :summary_offset";
    if (by != null) pAdd(q, f);
    return db.queryForList(
        "WITH scoped AS (SELECT * FROM tapping_assignment"
            + q.where()
            + ") SELECT "
            + select
            + "count(*) AS planned_assignments,count(*) FILTER(WHERE a.status='COMPLETED') AS"
            + " completed_assignments,count(*) FILTER(WHERE a.status='MISSED') AS"
            + " missed_assignments,coalesce(sum(a.planned_tree_count),0) AS"
            + " planned_trees,coalesce(sum(r.actual_tree_count) FILTER(WHERE a.status='COMPLETED'"
            + " AND r.completed_at IS NOT NULL),0) AS actual_trees FROM scoped a LEFT JOIN"
            + " tapping_record r ON r.assignment_id=a.id"
            + grouping,
        q.params());
  }
}
