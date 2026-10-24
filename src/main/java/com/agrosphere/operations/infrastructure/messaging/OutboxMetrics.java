package com.agrosphere.operations.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {
  public OutboxMetrics(JdbcTemplate db, MeterRegistry registry) {
    registry.gauge("operations.outbox.backlog", db, d -> count(d, "PENDING"));
    registry.gauge("operations.outbox.failed", db, d -> count(d, "FAILED"));
  }

  private static double count(JdbcTemplate db, String status) {
    return db.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE delivery_status=?", Long.class, status)
        .doubleValue();
  }
}
