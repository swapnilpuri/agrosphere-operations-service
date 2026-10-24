package com.agrosphere.operations.infrastructure.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class Events implements com.agrosphere.operations.application.DomainEvents {
  private final JdbcTemplate db;
  private final String provenance;
  private final ObjectMapper json = new ObjectMapper();

  public Events(JdbcTemplate db, @Value("${operations.data-provenance:REAL}") String provenance) {
    if (!Set.of("REAL", "SIMULATED", "ESTIMATED", "PREDICTED").contains(provenance))
      throw new IllegalArgumentException("Invalid provenance");
    this.db = db;
    this.provenance = provenance;
  }

  public void emit(String type, UUID id, long version, UUID estate, UUID block, Object payload) {
    var event = new LinkedHashMap<String, Object>();
    var eventId = UUID.randomUUID();
    var correlation = org.slf4j.MDC.get("correlationId");
    if (correlation == null) correlation = UUID.randomUUID().toString();
    var now = Instant.now();
    event.put("eventId", eventId);
    event.put("eventType", type);
    event.put("eventVersion", 1);
    event.put("timestamp", now.toString());
    event.put("source", "agrosphere-operations-service");
    event.put("correlationId", correlation);
    event.put("estateId", estate);
    event.put("blockId", block);
    event.put("dataProvenance", provenance);
    event.put("aggregateId", id);
    event.put("aggregateVersion", version);
    event.put("payload", payload);
    db.update(
        "INSERT INTO"
            + " outbox_event(event_id,aggregate_id,event_type,event_version,correlation_id,occurred_at,payload)"
            + " VALUES (?,?,?,1,?,?,?::jsonb)",
        eventId,
        id,
        type,
        UUID.fromString(correlation),
        Timestamp.from(now),
        json.writeValueAsString(event));
  }
}
