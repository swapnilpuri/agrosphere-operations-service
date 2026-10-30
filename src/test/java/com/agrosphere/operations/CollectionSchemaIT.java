package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CollectionSchemaIT {
  static PostgreSQLContainer postgres;
  JdbcTemplate db;
  DriverManagerDataSource source;
  TransactionTemplate tx;
  String schema;
  final Instant time = Instant.parse("2026-10-27T18:00:00Z");
  record Seed(UUID estate, UUID block, UUID assignment, UUID record) {}

  @BeforeAll static void start() throws Exception {
    postgres = new PostgreSQLContainer("postgres:17-alpine");
    postgres.start();
    CollectionTestSupport.provisionNoLoginRuntimeRole(postgres);
  }

  @AfterAll static void stop() { if (postgres != null) postgres.stop(); }

  Flyway flyway(String target) {
    var config = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas(schema).defaultSchema(schema);
    if (target != null) config.target(target);
    return config.load();
  }

  @BeforeEach void migrate() {
    schema = "collection_" + UUID.randomUUID().toString().replace("-", "");
    flyway(null).migrate();
    source = new DriverManagerDataSource(postgres.getJdbcUrl()
        + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
        postgres.getUsername(), postgres.getPassword());
    db = new JdbcTemplate(source);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  Seed seed() {
    var s = new Seed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    db.update("""
        INSERT INTO tapping_assignment(id,estate_id,block_id,worker_id,tapping_date,planned_tree_count,status,
         created_at,updated_at,version,created_by,updated_by)
        VALUES (?,?,?,'worker','2026-10-27',10,'COMPLETED',?,?,2,'actor','actor')
        """, s.assignment(), s.estate(), s.block(), Timestamp.from(time.minusSeconds(7200)), Timestamp.from(time.minusSeconds(3600)));
    db.update("""
        INSERT INTO tapping_record(id,assignment_id,worker_id,estate_id,block_id,tapping_date,planned_tree_count,
         actual_tree_count,started_at,completed_at,created_by,created_at,updated_at)
        VALUES (?,?,'worker',?,?,'2026-10-27',10,8,?,?,'actor',?,?)
        """, s.record(), s.assignment(), s.estate(), s.block(), Timestamp.from(time.minusSeconds(7200)),
        Timestamp.from(time.minusSeconds(3600)), Timestamp.from(time.minusSeconds(7200)), Timestamp.from(time.minusSeconds(3600)));
    return s;
  }

  void root(UUID id, Seed s, String reference) {
    db.update("""
        INSERT INTO latex_collection(id,tapping_record_id,assignment_id,estate_id,block_id,division_id_snapshot,
         worker_id,tapping_date,estate_timezone,estate_version_snapshot,block_version_snapshot,master_validated_at,
         collection_reference,collected_at,collection_date,wet_latex_kg,weight_method,weight_source_reference,
         data_provenance,status,version,created_at,updated_at,created_by,updated_by)
        VALUES (?,?,?,?,?,?,'worker','2026-10-27','UTC',0,0,?,?,?,'2026-10-27',125.500,'SCALE','SIMULATED-SCALE',
         'SIMULATED','PENDING_DRC',0,?,?,'actor','actor')
        """, id,s.record(),s.assignment(),s.estate(),s.block(),UUID.randomUUID(),Timestamp.from(time),reference,
        Timestamp.from(time.minusSeconds(1800)),Timestamp.from(time),Timestamp.from(time));
  }

  void revision(UUID id, int version, String kind) {
    db.update("INSERT INTO collection_revision(collection_id,version,command_kind,snapshot,recorded_at,recorded_by,correlation_id) VALUES (?,?,?,'{}',?,'actor',?)",
        id,version,kind,Timestamp.from(time),UUID.randomUUID());
  }

  UUID create(Seed s, String reference) {
    UUID id = UUID.randomUUID();
    tx.executeWithoutResult(status -> {root(id,s,reference); revision(id,0,"CREATE");});
    return id;
  }

  @Test void freshV3AndRestrictedRoleGrants() throws Exception {
    assertEquals("4",db.queryForObject("SELECT max(version) FROM flyway_schema_history",String.class));
    try (var connection=source.getConnection();var statement=connection.createStatement()) {
      statement.execute("SET ROLE agrosphere_operations_app");
      try(var r=statement.executeQuery("SELECT has_table_privilege(current_user,'collection_revision','INSERT'),has_table_privilege(current_user,'collection_revision','UPDATE'),has_table_privilege(current_user,'collection_revision','DELETE'),has_schema_privilege(current_user,current_schema(),'CREATE')")) {
        assertTrue(r.next());assertTrue(r.getBoolean(1));assertFalse(r.getBoolean(2));assertFalse(r.getBoolean(3));assertFalse(r.getBoolean(4));
      }
      assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE collection_revision SET recorded_by='other'"));
      assertThrows(SQLException.class,()->statement.executeUpdate("CREATE TABLE forbidden(id integer)"));
      assertThrows(SQLException.class,()->statement.executeUpdate("TRUNCATE collection_revision"));
    }
  }

  @Test void multipleLotsAndPermanentReferenceUniquenessSurviveVoid() {
    var s=seed();var id=create(s,"LOT-A");create(s,"LOT-B");
    tx.executeWithoutResult(status->{db.update("UPDATE latex_collection SET status='VOIDED',version=1 WHERE id=?",id);revision(id,1,"VOID");});
    assertThrows(RuntimeException.class,()->create(s,"LOT-A"));
    assertEquals(2,db.queryForObject("SELECT count(*) FROM latex_collection",Integer.class));
    assertThrows(RuntimeException.class,()->db.update("UPDATE latex_collection SET version=2 WHERE id=?",id));
  }

  @Test void completedOwnershipAndImmutableAttributionGuard() {
    var s=seed();db.update("UPDATE tapping_assignment SET status='CANCELLED' WHERE id=?",s.assignment());
    assertThrows(RuntimeException.class,()->create(s,"ABORTED"));
    db.update("UPDATE tapping_assignment SET status='COMPLETED' WHERE id=?",s.assignment());
    db.update("UPDATE tapping_record SET actual_tree_count=0 WHERE id=?",s.record());
    assertThrows(RuntimeException.class,()->create(s,"ZERO"));
    db.update("UPDATE tapping_record SET actual_tree_count=8 WHERE id=?",s.record());
    var id=create(s,"OK");
    assertThrows(RuntimeException.class,()->db.update("UPDATE latex_collection SET worker_id='forged',version=1 WHERE id=?",id));
    assertThrows(RuntimeException.class,()->db.update("DELETE FROM latex_collection WHERE id=?",id));
  }

  @Test void deferredCurrentRevisionAndSelectedChildOwnership() {
    var s=seed();var id=UUID.randomUUID();
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(status->root(id,s,"ORPHAN")));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM latex_collection",Integer.class));
    var a=create(s,"A");var b=create(s,"B");var measurement=UUID.randomUUID();
    tx.executeWithoutResult(status->{
      db.update("INSERT INTO drc_measurement(id,collection_id,introduced_version,percent,measured_at,method,source_reference,data_provenance,recorded_at,recorded_by) VALUES (?,?,1,50,?,'FIELD_TEST','SIMULATED','SIMULATED',?,'actor')",measurement,b,Timestamp.from(time),Timestamp.from(time));
      db.update("UPDATE latex_collection SET status='CALCULATED',selected_measurement_id=?,dry_rubber_exact_kg=62.7500000,dry_rubber_kg=62.750,calculation_policy='DRC_KG_V1',version=1 WHERE id=?",measurement,b);
      revision(b,1,"DRC");
    });
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(status->{
      db.update("UPDATE latex_collection SET status='CALCULATED',selected_measurement_id=?,dry_rubber_exact_kg=62.7500000,dry_rubber_kg=62.750,calculation_policy='DRC_KG_V1',version=1 WHERE id=?",measurement,a);
      revision(a,1,"DRC");
    }));
    assertThrows(RuntimeException.class,()->db.update("UPDATE drc_measurement SET percent=40 WHERE id=?",measurement));
    assertThrows(RuntimeException.class,()->db.update("DELETE FROM collection_revision WHERE collection_id=?",a));
  }

  @Test void reservationCannotCommitAndCompletedReceiptCannotChange() {
    var id=create(seed(),"COMMAND");var key=UUID.randomUUID();
    Runnable reserve=()->db.update("INSERT INTO collection_command(actor,idempotency_key,request_hash,normalization_version,method,resource_path,created_at) VALUES ('actor',?,?,1,'POST','/api/v1/tapping/collections',?)",key,"a".repeat(64),Timestamp.from(time));
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(status->reserve.run()));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM collection_command",Integer.class));
    tx.executeWithoutResult(status->{reserve.run();db.update("UPDATE collection_command SET collection_id=?,response_status=201,response_location=?,response_body='{}',completed_at=? WHERE actor='actor' AND idempotency_key=?",id,"/api/v1/tapping/collections/"+id,Timestamp.from(time),key);});
    assertThrows(RuntimeException.class,()->db.update("UPDATE collection_command SET response_body='{\"changed\":true}' WHERE idempotency_key=?",key));
    assertThrows(RuntimeException.class,()->db.update("DELETE FROM collection_command WHERE idempotency_key=?",key));
  }

  @Test void populatedV2UpgradeRetainsPublishedAndPendingOutbox() {
    schema="upgrade_"+UUID.randomUUID().toString().replace("-","");flyway("2").migrate();
    var url=postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema;
    var old=new JdbcTemplate(new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));
    for(String state:List.of("PUBLISHED","PENDING")) {
      var id=UUID.randomUUID();old.update("INSERT INTO outbox_event(event_id,aggregate_id,event_type,event_version,correlation_id,occurred_at,payload,delivery_status) VALUES (?,?,'TappingCompleted',1,?,now(),'{\"preserved\":true}',?)",id,id,id,state);
    }
    var checksum=old.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2') ORDER BY installed_rank");
    flyway(null).migrate();
    assertEquals(checksum,old.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2') ORDER BY installed_rank"));
    assertEquals(2,old.queryForObject("SELECT count(*) FROM outbox_event WHERE payload->>'preserved'='true' AND event_version=1",Integer.class));
  }

  @Test void failedV3DdlRollsBackAtomically(@TempDir Path migrations) throws Exception {
    schema="rollback_"+UUID.randomUUID().toString().replace("-","");flyway("2").migrate();
    var sourceSql=Files.readString(Path.of("src/main/resources/db/migration/V3__latex_collection.sql"));
    Files.writeString(migrations.resolve("V3__latex_collection.sql"),sourceSql+"\nSELECT missing_deliberate_ddl_failure();\n");
    assertThrows(RuntimeException.class,()->Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
        .schemas(schema).defaultSchema(schema).locations("filesystem:"+migrations).ignoreMigrationPatterns("*:missing").load().migrate());
    assertNull(db.queryForObject("SELECT to_regclass(?)::text",String.class,schema+".latex_collection"));
    assertEquals(2,db.queryForObject("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE success AND version IS NOT NULL",Integer.class));
  }

  @Test void migrationRejectsRoleMembershipThatCouldBypassEvidencePrivileges() {
    schema="role_guard_"+UUID.randomUUID().toString().replace("-","");flyway("2").migrate();
    db.execute("CREATE ROLE collection_test_inherited NOLOGIN");
    db.execute("GRANT collection_test_inherited TO agrosphere_operations_app");
    try {
      assertThrows(RuntimeException.class,()->flyway(null).migrate());
      assertNull(db.queryForObject("SELECT to_regclass(?)::text",String.class,schema+".latex_collection"));
    } finally {
      db.execute("REVOKE collection_test_inherited FROM agrosphere_operations_app");
      db.execute("DROP ROLE collection_test_inherited");
    }
  }
}
