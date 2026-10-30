package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;

import com.agrosphere.operations.application.*;
import com.agrosphere.operations.domain.*;
import com.agrosphere.operations.infrastructure.messaging.*;
import com.agrosphere.operations.infrastructure.persistence.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TappingServiceIT {
  static PostgreSQLContainer postgres;
  static GenericContainer<?> broker;
  static HttpServer oidc;
  static RSAKey key;
  static String issuer, base;
  static ConfigurableApplicationContext context;
  static ObjectMapper json = new ObjectMapper();
  static JdbcTemplate jdbc;
  static UUID estate = UUID.randomUUID(), block = UUID.randomUUID(), schedule, assignment;
  static LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
  static HttpClient http = HttpClient.newHttpClient();
  static volatile int estateStatus = 200;
  static volatile boolean malformedEstate = false;

  @BeforeAll
  static void start() throws Exception {
    postgres = new PostgreSQLContainer("postgres:17-alpine");
    postgres.start();
    CollectionTestSupport.provisionNoLoginRuntimeRole(postgres);
    broker =
        new GenericContainer<>("rabbitmq:4.3.6-management")
            .withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER", "integration")
            .withEnv("RABBITMQ_DEFAULT_PASS", "integration-test-only");
    broker.start();
    key = new RSAKeyGenerator(2048).keyID("test").generate();
    oidc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://127.0.0.1:" + oidc.getAddress().getPort();
    oidc.createContext(
        "/jwks",
        e -> {
          byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(200, body.length);
          e.getResponseBody().write(body);
          e.close();
        });
    oidc.createContext(
        "/api/v1",
        e -> {
          assertTrue(e.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));
          String p = e.getRequestURI().getPath();
          var data =
              p.contains("/blocks/")
                  ? Map.of(
                      "id", block, "estateId", estate, "status", "ACTIVE", "cropType", "RUBBER")
                  : Map.of("id", estate, "status", "ACTIVE", "timezone", "Asia/Colombo");
          byte[] body = malformedEstate ? new byte[] {123} : json.writeValueAsBytes(data);
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(estateStatus, body.length);
          e.getResponseBody().write(body);
          e.close();
        });
    oidc.start();
    context =
        SpringApplication.run(
            Application.class,
            "--server.port=0",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.flyway.user=" + postgres.getUsername(),
            "--spring.flyway.password=" + postgres.getPassword(),
            "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer,
            "--OIDC_JWK_SET_URI=" + issuer + "/jwks",
            "--operations.estate-url=" + issuer,
            "--operations.outbox.enabled=false",
            "--operations.data-provenance=SIMULATED",
            "--management.otlp.metrics.export.enabled=false",
            "--management.tracing.export.otlp.enabled=false",
            "--spring.rabbitmq.host=" + broker.getHost(),
            "--spring.rabbitmq.port=" + broker.getMappedPort(5672),
            "--spring.rabbitmq.username=integration",
            "--spring.rabbitmq.password=integration-test-only");
    base = "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    jdbc = context.getBean(JdbcTemplate.class);
  }

  @AfterAll
  static void stop() {
    if (context != null) context.close();
    if (oidc != null) oidc.stop(0);
    if (broker != null) broker.stop();
    if (postgres != null) postgres.stop();
  }

  static String token(String role, List<String> estates, String subject, String audience)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .audience(audience)
            .issueTime(Date.from(Instant.now().minusSeconds(5)))
            .expirationTime(Date.from(Instant.now().plusSeconds(600)))
            .claim("realm_access", Map.of("roles", List.of(role)))
            .claim("estate_ids", estates)
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  static String admin() throws Exception {
    return token("PLATFORM_ADMIN", List.of(), "admin", "operations-service");
  }

  static String worker(String sub) throws Exception {
    return token("FIELD_WORKER", List.of(estate.toString()), sub, "operations-service");
  }

  static HttpResponse<String> request(String method, String uri, Object body, String token)
      throws Exception {
    var r =
        HttpRequest.newBuilder(URI.create(base + "/api/v1/tapping" + uri))
            .header("Content-Type", "application/json")
            .header("X-Correlation-ID", "00000000-0000-0000-0000-000000000099");
    if (token != null) r.header("Authorization", "Bearer " + token);
    return http.send(
        r.method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static JsonNode body(HttpResponse<String> r, int code) {
    assertEquals(code, r.statusCode(), r.body());
    return json.readTree(r.body());
  }

  static UUID create(String worker, LocalDate date) throws Exception {
    return UUID.fromString(
        body(
                request(
                    "POST",
                    "/assignments",
                    Map.of(
                        "estateId",
                        estate,
                        "blockId",
                        block,
                        "workerId",
                        worker,
                        "tappingDate",
                        date,
                        "plannedTreeCount",
                        10),
                    admin()),
                201)
            .get("id")
            .asText());
  }

  @Test
  @Order(1)
  void migrationsScheduleAndGeneration() throws Exception {
    assertEquals(
        4,
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success and version is not null", Integer.class));
    assertEquals(2, jdbc.queryForObject(
        "select count(*) from flyway_schema_history where success and version in ('1','2')", Integer.class));
    var data =
        Map.of(
            "estateId",
            estate,
            "blockId",
            block,
            "name",
            "Daily",
            "recurrenceRule",
            "DAILY",
            "plannedTreeCount",
            10,
            "effectiveFrom",
            today,
            "effectiveTo",
            today.plusDays(2));
    var response = request("POST", "/schedules", data, admin());
    schedule = UUID.fromString(body(response, 201).get("id").asText());
    assertEquals(
        "00000000-0000-0000-0000-000000000099",
        response.headers().firstValue("X-Correlation-ID").orElseThrow());
    body(
        request(
            "POST",
            "/schedules/" + schedule + "/generate-assignments",
            Map.of("version", 0, "from", today, "to", today, "workerId", "worker"),
            admin()),
        400);
    body(
        request(
            "PATCH",
            "/schedules/" + schedule,
            Map.of(
                "version",
                0,
                "name",
                "Updated",
                "recurrenceRule",
                "DAILY",
                "plannedTreeCount",
                10,
                "effectiveFrom",
                today,
                "effectiveTo",
                today.plusDays(2)),
            admin()),
        200);
    body(
        request("POST", "/schedules/" + schedule + "/activate", Map.of("version", 1), admin()),
        200);
    var gen = Map.of("version", 2, "from", today, "to", today.plusDays(2), "workerId", "worker");
    var a =
        body(
            request("POST", "/schedules/" + schedule + "/generate-assignments", gen, admin()), 200);
    assertEquals(3, a.size());
    assignment = UUID.fromString(a.get(0).get("id").asText());
    body(request("POST", "/schedules/" + schedule + "/generate-assignments", gen, admin()), 200);
    assertEquals(3, jdbc.queryForObject("select count(*) from tapping_assignment", Integer.class));
    assertEquals(4, jdbc.queryForObject("select count(*) from outbox_event", Integer.class));
    body(
        request(
            "POST",
            "/schedules/" + schedule + "/generate-assignments",
            Map.of("version", 2, "from", today, "to", today, "workerId", "different"),
            admin()),
        400);
  }

  @Test
  @Order(2)
  void executionAndIdempotentRetry() throws Exception {
    body(
        request(
            "POST",
            "/assignments/" + assignment + "/complete",
            Map.of("version", 0, "actualTreeCount", 5),
            worker("worker")),
        400);
    body(
        request(
            "POST",
            "/assignments/" + assignment + "/start",
            Map.of("version", 0),
            worker("worker")),
        200);
    body(
        request(
            "POST",
            "/assignments/" + assignment + "/start",
            Map.of("version", 0),
            worker("worker")),
        200);
    body(
        request(
            "POST",
            "/assignments/" + assignment + "/complete",
            Map.of("version", 0, "actualTreeCount", 5),
            worker("worker")),
        409);
    body(
        request(
            "POST",
            "/assignments/" + assignment + "/complete",
            Map.of("version", 1, "actualTreeCount", 11),
            worker("worker")),
        400);
    var complete = Map.of("version", 1, "actualTreeCount", 8, "notes", "Done");
    body(
        request("POST", "/assignments/" + assignment + "/complete", complete, worker("worker")),
        200);
    body(
        request("POST", "/assignments/" + assignment + "/complete", complete, worker("worker")),
        200);
    body(
        request(
            "POST",
            "/assignments/" + assignment + "/complete",
            Map.of("version", 2, "actualTreeCount", 7),
            worker("worker")),
        400);
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from outbox_event where event_type='TappingCompleted'",
            Integer.class));
    var summary =
        body(request("GET", "/summary?estateId=" + estate, null, admin()), 200)
            .get("content")
            .get(0);
    assertEquals(3, summary.get("planned_assignments").asInt());
    assertEquals(8, summary.get("actual_trees").asInt());
    assertEquals(33.33, summary.get("completion_percentage").asDouble());
    assertEquals(
        1,
        body(request("GET", "/summary?groupBy=worker", null, admin()), 200).get("content").size());
    body(request("GET", "/summary?groupBy=block", null, admin()), 200);
  }

  @Test
  @Order(3)
  void missedCancelledAndReassignment() throws Exception {
    UUID a = create("missed", today);
    body(request("POST", "/assignments/" + a + "/mark-missed", Map.of("version", 0), admin()), 400);
    var missed = Map.of("version", 0, "reasonCode", "RAIN", "remarks", "Rain");
    body(request("POST", "/assignments/" + a + "/mark-missed", missed, worker("missed")), 200);
    body(request("POST", "/assignments/" + a + "/mark-missed", missed, worker("missed")), 200);
    body(request("POST", "/assignments/" + a + "/start", Map.of("version", 1), admin()), 400);
    UUID future = create("future", today.plusDays(1));
    body(request("POST", "/assignments/" + future + "/mark-missed", missed, admin()), 400);
    body(request("POST", "/assignments/" + future + "/start", Map.of("version", 0), admin()), 400);
    body(
        request(
            "PATCH",
            "/assignments/" + future + "/worker",
            Map.of("version", 0, "workerId", "replacement"),
            admin()),
        200);
    body(request("POST", "/assignments/" + future + "/cancel", Map.of("version", 1), admin()), 200);
    body(request("POST", "/assignments/" + future + "/start", Map.of("version", 2), admin()), 400);
    assertEquals(
        1, jdbc.queryForObject("select count(*) from tapping_missed_reason", Integer.class));
  }

  @Test
  @Order(4)
  void securityAndFiltering() throws Exception {
    body(request("GET", "/assignments", null, null), 401);
    body(
        request("GET", "/assignments", null, token("PLATFORM_ADMIN", List.of(), "admin", "wrong")),
        401);
    body(request("GET", "/assignments/" + assignment, null, worker("other")), 403);
    body(
        request(
            "POST",
            "/assignments/" + assignment + "/cancel",
            Map.of("version", 2),
            worker("worker")),
        403);
    body(
        request(
            "GET",
            "/assignments/" + assignment,
            null,
            token(
                "ESTATE_MANAGER",
                List.of(UUID.randomUUID().toString()),
                "manager",
                "operations-service")),
        403);
    body(
        request(
            "POST",
            "/schedules/" + schedule + "/close",
            Map.of("version", 2),
            token("AUDITOR", List.of(estate.toString()), "auditor", "operations-service")),
        403);
    assertEquals(
        0,
        body(request("GET", "/assignments", null, worker("other")), 200)
            .get("totalElements")
            .asInt());
    assertEquals(
        3,
        body(request("GET", "/assignments", null, worker("worker")), 200)
            .get("totalElements")
            .asInt());
    body(request("GET", "/assignments?size=101", null, admin()), 400);
    body(request("GET", "/assignments?status=INVALID", null, admin()), 400);
    body(request("GET", "/assignments/not-uuid", null, admin()), 400);
    body(request("GET", "/assignments/" + UUID.randomUUID(), null, admin()), 404);
    assertEquals(
        1,
        body(request("GET", "/records?workerId=worker", null, admin()), 200)
            .get("totalElements")
            .asInt());
  }

  @Test
  @Order(5)
  void uniquenessConcurrencyAndTransactionalRollback() throws Exception {
    body(
        request(
            "POST",
            "/assignments",
            Map.of(
                "estateId",
                estate,
                "blockId",
                block,
                "workerId",
                "worker",
                "tappingDate",
                today,
                "plannedTreeCount",
                10),
            admin()),
        409);
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "update tapping_assignment set planned_tree_count=0 where id=?", assignment));
    var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    int count = jdbc.queryForObject("select count(*) from outbox_event", Integer.class);
    UUID id = UUID.randomUUID();
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.executeWithoutResult(
                s -> {
                  jdbc.update("update tapping_schedule set name='Rollback' where id=?", schedule);
                  context
                      .getBean(Events.class)
                      .emit("TappingScheduleActivated", id, 1, estate, block, Map.of());
                  throw new IllegalStateException();
                }));
    assertEquals(count, jdbc.queryForObject("select count(*) from outbox_event", Integer.class));
    assertEquals(
        "Updated",
        jdbc.queryForObject(
            "select name from tapping_schedule where id=?", String.class, schedule));
    var store = context.getBean(TappingStore.class);
    var old = store.schedule(schedule);
    var next = old.transition(Rules.ScheduleStatus.SUSPENDED, Instant.now(), "test");
    store.save(next, old.version());
    assertThrows(
        org.springframework.dao.OptimisticLockingFailureException.class,
        () -> store.save(next, old.version()));
  }

  @Test
  @Order(6)
  void dependencyFailureDoesNotInventSuccess() throws Exception {
    estateStatus = 503;
    try {
      body(
          request(
              "POST",
              "/assignments",
              Map.of(
                  "estateId",
                  estate,
                  "blockId",
                  block,
                  "workerId",
                  "unavailable",
                  "tappingDate",
                  today,
                  "plannedTreeCount",
                  10),
              admin()),
          503);
    } finally {
      estateStatus = 200;
    }
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from tapping_assignment where worker_id='unavailable'",
            Integer.class));
    for (int status : List.of(403, 404)) {
      estateStatus = status;
      try {
        body(
            request(
                "POST",
                "/assignments",
                Map.of(
                    "estateId",
                    estate,
                    "blockId",
                    block,
                    "workerId",
                    "bad-reference",
                    "tappingDate",
                    today,
                    "plannedTreeCount",
                    10),
                admin()),
            status == 403 ? 403 : 422);
      } finally {
        estateStatus = 200;
      }
    }
    malformedEstate = true;
    try {
      body(
          request(
              "POST",
              "/assignments",
              Map.of(
                  "estateId",
                  estate,
                  "blockId",
                  block,
                  "workerId",
                  "malformed",
                  "tappingDate",
                  today,
                  "plannedTreeCount",
                  10),
              admin()),
          503);
    } finally {
      malformedEstate = false;
    }
  }

  @Test
  @Order(7)
  void realBrokerConfirmsReturnsAndEventExports() throws Exception {
    var connection = context.getBean(CachingConnectionFactory.class);
    var admin = new RabbitAdmin(connection);
    admin.declareExchange(new TopicExchange("agrosphere.domain", true, false));
    var queue = new org.springframework.amqp.core.Queue("operations.it", true);
    admin.declareQueue(queue);
    admin.declareBinding(
        BindingBuilder.bind(queue)
            .to(new TopicExchange("agrosphere.domain"))
            .with("agrosphere.Tapping*.v1"));
    // Rabbit topic wildcards match whole words, so bind all operations event types explicitly.
    for (String type :
        List.of(
            "TappingScheduleActivated",
            "TappingAssignmentCreated",
            "TappingAssignmentStarted",
            "TappingCompleted",
            "TappingMissed",
            "TappingAssignmentCancelled"))
      admin.declareBinding(
          BindingBuilder.bind(queue)
              .to(new TopicExchange("agrosphere.domain"))
              .with("agrosphere." + type + ".v1"));
    var relay = new OutboxDispatcher(jdbc, context.getBean(RabbitTemplate.class), 3, 100, 1, 5);
    var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    tx.executeWithoutResult(s -> relay.dispatch());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from outbox_event where published_at is null", Integer.class));
    assertNotNull(context.getBean(RabbitTemplate.class).receive("operations.it", 5000));
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/events"));
    try (var old = java.nio.file.Files.list(java.nio.file.Path.of("target/events"))) {
      for (var file : old.filter(f -> f.getFileName().toString().endsWith(".json")).toList())
        java.nio.file.Files.delete(file);
    }
    for (var row :
        jdbc.queryForList("select event_id,event_type,payload::text as body from outbox_event"))
      java.nio.file.Files.writeString(
          java.nio.file.Path.of(
              "target/events/" + row.get("event_type") + "-" + row.get("event_id") + ".json"),
          row.get("body").toString());
    var r =
        http.send(
            HttpRequest.newBuilder(URI.create(base + "/v3/api-docs"))
                .header("Authorization", "Bearer " + admin())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, r.statusCode(), r.body());
    java.nio.file.Files.writeString(java.nio.file.Path.of("target/openapi-runtime.json"), r.body());
    assertEquals(
        200,
        http.send(
                HttpRequest.newBuilder(URI.create(base + "/actuator/health/readiness"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  @Order(8)
  void realUnroutableRetryPreservesEventIdentity() throws Exception {
    var rabbitAdmin = new RabbitAdmin(context.getBean(CachingConnectionFactory.class));
    rabbitAdmin.deleteQueue("operations.it");
    UUID id = create("retry-worker", today);
    UUID event =
        jdbc.queryForObject(
            "select event_id from outbox_event where aggregate_id=?", UUID.class, id);
    var relay = new OutboxDispatcher(jdbc, context.getBean(RabbitTemplate.class), 3, 100, 1, 5);
    var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    tx.executeWithoutResult(s -> relay.dispatch());
    assertNull(
        jdbc.queryForObject(
            "select published_at from outbox_event where event_id=?",
            java.sql.Timestamp.class,
            event));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select attempts from outbox_event where event_id=?", Integer.class, event));
    var queue = new org.springframework.amqp.core.Queue("operations.retry", true);
    rabbitAdmin.declareQueue(queue);
    rabbitAdmin.declareBinding(
        BindingBuilder.bind(queue)
            .to(new TopicExchange("agrosphere.domain"))
            .with("agrosphere.TappingAssignmentCreated.v1"));
    jdbc.update("update outbox_event set next_attempt_at=now() where event_id=?", event);
    tx.executeWithoutResult(s -> relay.dispatch());
    var message = context.getBean(RabbitTemplate.class).receive("operations.retry", 5000);
    assertNotNull(message);
    assertEquals(event.toString(), message.getMessageProperties().getMessageId());
    assertEquals(
        "PUBLISHED",
        jdbc.queryForObject(
            "select delivery_status from outbox_event where event_id=?", String.class, event));
    assertEquals(
        2,
        jdbc.queryForObject(
            "select attempts from outbox_event where event_id=?", Integer.class, event));
  }

  @Test
  @Order(9)
  void concurrentGenerationAndMutation() throws Exception {
    UUID s =
        UUID.fromString(
            body(
                    request(
                        "POST",
                        "/schedules",
                        Map.of(
                            "estateId",
                            estate,
                            "blockId",
                            block,
                            "name",
                            "Concurrent",
                            "recurrenceRule",
                            "DAILY",
                            "plannedTreeCount",
                            10,
                            "effectiveFrom",
                            today),
                        admin()),
                    201)
                .get("id")
                .asText());
    body(request("POST", "/schedules/" + s + "/activate", Map.of("version", 0), admin()), 200);
    var generate = Map.of("version", 1, "from", today, "to", today, "workerId", "concurrent");
    java.util.concurrent.Callable<HttpResponse<String>> call =
        () -> request("POST", "/schedules/" + s + "/generate-assignments", generate, admin());
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var results = pool.invokeAll(List.of(call, call));
      for (var result : results) body(result.get(), 200);
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from tapping_assignment where schedule_id=?", Integer.class, s));
    UUID a =
        jdbc.queryForObject("select id from tapping_assignment where schedule_id=?", UUID.class, s);
    java.util.concurrent.Callable<HttpResponse<String>> start =
        () -> request("POST", "/assignments/" + a + "/start", Map.of("version", 0), admin());
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var results = pool.invokeAll(List.of(start, start));
      for (var result : results) assertTrue(Set.of(200, 409).contains(result.get().statusCode()));
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from tapping_record where assignment_id=?", Integer.class, a));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from outbox_event where aggregate_id=? and"
                + " event_type='TappingAssignmentStarted'",
            Integer.class,
            a));
  }

  @Test
  @Order(10)
  void migrationUpgradePreservesPublishedHistory() {
    var first =
        org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("upgrade_check")
            .defaultSchema("upgrade_check")
            .target("1")
            .load();
    first.migrate();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " upgrade_check.outbox_event(event_id,aggregate_id,event_type,event_version,correlation_id,occurred_at,payload,published_at)"
            + " values (?,?,?,1,?,now(),'{}'::jsonb,now())",
        id,
        id,
        "TappingCompleted",
        id);
    org.flywaydb.core.Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas("upgrade_check")
        .defaultSchema("upgrade_check")
        .load()
        .migrate();
    assertEquals(
        "PUBLISHED",
        jdbc.queryForObject(
            "select delivery_status from upgrade_check.outbox_event where event_id=?",
            String.class,
            id));
  }
}
