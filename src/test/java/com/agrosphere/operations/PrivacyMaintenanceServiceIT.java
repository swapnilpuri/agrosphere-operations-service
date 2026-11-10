package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.crophealth.application.CropHealthChanges;
import com.agrosphere.operations.crophealth.infrastructure.JdbcCropHealthStore;
import com.agrosphere.operations.alerts.domain.AlertRules;
import com.agrosphere.operations.alerts.domain.PrivacyAlert;
import com.agrosphere.operations.alerts.infrastructure.JdbcPrivacyAlertStore;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

/** Candidate SQL only, installed in unique schemas in this class's owned disposable container. */
class PrivacyMaintenanceServiceIT {
  static PostgreSQLContainer postgres;
  static final ObjectMapper json=new ObjectMapper();
  JdbcTemplate db;TransactionTemplate tx;JdbcCropHealthStore crops;JdbcPrivacyAlertStore alerts;
  String schema,url,monitor,cleanup,remover,runtime;
  final UUID estate=UUID.randomUUID(),block=UUID.randomUUID();
  final Instant past=Instant.now().minus(Duration.ofDays(500)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

  @BeforeAll static void start()throws Exception {
    postgres=new PostgreSQLContainer("postgres:17-alpine").withPassword(UUID.randomUUID().toString());
    postgres.start();CollectionTestSupport.provisionNoLoginRuntimeRole(postgres);
  }
  @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
  @BeforeEach void setup()throws Exception {
    schema="pm_"+UUID.randomUUID().toString().replace("-","");
    Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
      .schemas(schema).defaultSchema(schema).load().migrate();
    url=postgres.getJdbcUrl()+"&currentSchema="+schema+"&preferQueryMode=simple";
    var ds=new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword());db=new JdbcTemplate(ds);
    tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    crops=new JdbcCropHealthStore(new NamedParameterJdbcTemplate(ds));alerts=new JdbcPrivacyAlertStore(new NamedParameterJdbcTemplate(ds));
    // Reviewed before application: docs/ALERTS_PRIVACY_MAINTENANCE.md. Not in runtime migration locations.
    db.execute(Files.readString(Path.of("docs/privacy-maintenance/privacy_observe_candidate.sql")));
    monitor=role("monitor");cleanup=role("cleanup");remover=role("remover");runtime=role("runtime");
    db.execute("GRANT agrosphere_operations_app TO "+runtime);
    for(String r:List.of(monitor,cleanup,remover,runtime))db.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+r);
    db.execute("GRANT EXECUTE ON FUNCTION privacy_observe() TO "+monitor+","+cleanup);
    db.execute("GRANT EXECUTE ON FUNCTION privacy_cleanup(integer) TO "+cleanup);
    db.execute("GRANT EXECUTE ON FUNCTION privacy_request_removal(text,text) TO "+remover);
    db.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
  }
  String role(String prefix){String r="pm_"+prefix+"_"+UUID.randomUUID().toString().replace("-","");
    db.execute("CREATE ROLE "+r+" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");return r;}
  Command command(Instant at){return new Command(new Actor("SIMULATED-manager",Set.of("ESTATE_MANAGER"),Set.of(estate)),Clock.fixed(at,ZoneOffset.UTC),UUID.randomUUID());}
  AlertRules.Source source(com.agrosphere.operations.crophealth.domain.CropIncident i){return new AlertRules.Source(i.id(),estate,block,ZoneOffset.UTC,Provenance.SIMULATED,i.audit().version(),i.report().observedAt(),i.report().category(),i.report().severity(),i.status());}
  UUID root(boolean inactive){
    var c=CropHealthChanges.directReport(UUID.randomUUID(),new Context(estate,block,ZoneOffset.UTC),Provenance.SIMULATED,
      new Report(past.minusSeconds(60),Category.TREE_DAMAGE,Severity.HIGH,"SIMULATED summary","SIMULATED details"),null,command(past));
    UUID id=UUID.randomUUID();var first=PrivacyAlert.evaluate(id,null,null,source(c.incident()),past,UUID.randomUUID());
    tx.executeWithoutResult(t->{crops.insert(c.incident());c.revisions().forEach(crops::appendRevision);alerts.save(first,null);});
    if(inactive){
      Instant at=past.plus(Duration.ofDays(1));var closed=CropHealthChanges.transition(c.incident(),null,0,null,Kind.RESOLVE,at,"SIMULATED resolution","SIMULATED closure",command(at));
      var change=PrivacyAlert.evaluate(id,first.alert(),source(c.incident()),source(closed.incident()),at,UUID.randomUUID());
      tx.executeWithoutResult(t->{crops.update(closed.incident(),0);closed.revisions().forEach(crops::appendRevision);alerts.save(change,first.alert());});
    }return id;
  }
  void read(UUID id,String subject){db.update("INSERT INTO privacy_read VALUES(?,?,?,1,statement_timestamp()-interval '1 hour')",id,"SIMULATED-issuer",subject);}
  void receipt(UUID id,String subject){db.update("INSERT INTO privacy_command VALUES(?,?,?,?,? ,?,1,?,statement_timestamp()-interval '1 day',statement_timestamp()+interval '1 day')",
    "SIMULATED-issuer",subject,UUID.randomUUID(),id,"SIMULATED-path","a".repeat(64),"SIMULATED-body");}
  JsonNode observe(){return json.readTree(db.queryForObject("SELECT privacy_observe()::text",String.class));}
  long count(JsonNode o,String kind){return o.path("copies").path(kind).path("count").asLong();}
  String checksums(){return db.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank").toString();}
  String sourceDigest(){return db.queryForObject("SELECT md5(string_agg(row_to_json(i)::text,'|' ORDER BY id)) FROM crop_health_incident i",String.class);}
  // Simple-query multi-command message has one statement timestamp. Only generated temporal fixtures are changed.
  JsonNode atDeadline(UUID id,String update)throws Exception {
    try(var c=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var s=c.createStatement()){
      boolean result=s.execute("SET TIME ZONE 'UTC'; ALTER TABLE privacy_alert DISABLE TRIGGER privacy_root_guard; "+update+" WHERE id='"+id+"'; ALTER TABLE privacy_alert ENABLE TRIGGER privacy_root_guard; SELECT privacy_observe()::text");
      while(!result&&s.getUpdateCount()!=-1)result=s.getMoreResults();
      assertTrue(result);try(var rs=s.getResultSet()){assertTrue(rs.next());return json.readTree(rs.getString(1));}
    }
  }

  @Test void effectiveRoleBoundariesAndSafeOwnerConfiguration()throws Exception {
    for(String r:List.of(monitor,cleanup,remover,runtime)){
      try(var c=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var s=c.createStatement()){
        s.execute("SET ROLE "+r);
        if(r.equals(monitor)||r.equals(cleanup))assertTrue(s.execute("SELECT privacy_observe()"));
        else assertThrows(SQLException.class,()->s.execute("SELECT privacy_observe()"));
        if(!r.equals(cleanup))assertThrows(SQLException.class,()->s.execute("SELECT privacy_cleanup(1)"));
        if(!r.equals(remover))assertThrows(SQLException.class,()->s.execute("SELECT privacy_request_removal('SIMULATED-i','SIMULATED-s')"));
        if(!r.equals(runtime))assertThrows(SQLException.class,()->s.execute("SELECT * FROM privacy_read"));
        for(String sql:List.of("DELETE FROM privacy_read","CREATE TABLE "+schema+".forbidden(id int)"))assertThrows(SQLException.class,()->s.execute(sql));
      }
    }
    assertFalse(db.queryForObject("SELECT has_function_privilege('agrosphere_operations_app','privacy_observe()','EXECUTE')",Boolean.class));
    var config=db.queryForMap("SELECT prosecdef,provolatile,proconfig::text,pg_get_userbyid(proowner) AS owner FROM pg_proc WHERE oid='privacy_observe()'::regprocedure");
    assertEquals(true,config.get("prosecdef"));assertEquals("s",config.get("provolatile").toString());
    assertTrue(config.get("proconfig").toString().contains("search_path=pg_catalog, pg_temp"));assertTrue(config.get("proconfig").toString().contains("TimeZone=UTC"));
    assertEquals(postgres.getUsername(),config.get("owner"));
  }
  @Test void readOnlyAggregateResistsTempAndCallerSearchPathAndDoesNotLeakIdentity()throws Exception {
    UUID id=root(false);read(id,"SIMULATED-sensitive-subject");db.update("UPDATE privacy_read SET attention_version=1 WHERE alert_id=?",id);
    String before=sourceDigest(),migrations=checksums();
    try(var c=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var s=c.createStatement()){
      s.execute("SET ROLE "+monitor);s.execute("CREATE TEMP TABLE privacy_read(secret text)");s.execute("INSERT INTO privacy_read VALUES('SIMULATED-shadow')");
      s.execute("SET search_path=pg_temp,public");s.execute("SET timezone='America/New_York'");s.execute("BEGIN READ ONLY");
      try(var rs=s.executeQuery("SELECT "+schema+".privacy_observe()::text")){rs.next();var value=json.readTree(rs.getString(1));assertEquals(0,value.path("eligibleRootCount").asInt());
        assertEquals(Set.of("version","observerRevision","observedAt","eligibleRootCount","copies","pendingRemoval"),value.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        for(String secret:List.of("SIMULATED-sensitive-subject","SIMULATED-shadow",id.toString(),estate.toString()))assertFalse(rs.getString(1).contains(secret));
        assertTrue(value.path("observedAt").asText().endsWith("Z"));}
      s.execute("ROLLBACK");
    }assertEquals(before,sourceDigest());assertEquals(migrations,checksums());
  }
  @ParameterizedTest @CsvSource({"30,1,0","30,0,1","30,-1,1","365,1,0","365,0,2","365,-1,2"})
  void inclusiveInactiveBoundariesAtDatabaseStatementTime(int days,int delta,int expected)throws Exception {
    UUID id=root(true);if(days==30)read(id,"SIMULATED-reader");
    var o=atDeadline(id,"UPDATE privacy_alert SET inactive_since=statement_timestamp()-interval '"+days+" days'+interval '"+delta+" seconds'");
    assertEquals(expected,count(o,days==30?"read":"evidence"));
    if(expected>0)assertFalse(o.path("copies").path(days==30?"read":"evidence").path("oldestEligibleAt").isNull());
  }
  @Test void receiptDeadlineAndRemovalCutoffIncludeEqualityWithoutResettingAges()throws Exception {
    UUID id=root(false);receipt(id,"SIMULATED-receipt");read(id,"SIMULATED-reader");
    db.queryForObject("SELECT privacy_request_removal('SIMULATED-issuer','SIMULATED-reader')",UUID.class);
    db.update("UPDATE privacy_read SET read_at=(SELECT key_cutoff FROM privacy_removal) WHERE alert_id=?",id);
    var o=observe();assertEquals(1,count(o,"read"));assertEquals(0,count(o,"receipt"));assertEquals(1,o.path("pendingRemoval").path("count").asInt());
    db.update("UPDATE privacy_read SET read_at=read_at+interval '1 microsecond' WHERE alert_id=?",id);assertEquals(0,count(observe(),"read"));
    // Guard is immutable, so set original expiry only by replacing this generated receipt fixture.
    db.update("DELETE FROM privacy_command WHERE alert_id=?",id);
    db.update("INSERT INTO privacy_command VALUES('SIMULATED-issuer','SIMULATED-receipt',?,?,? ,?,1,?,statement_timestamp()-interval '1 day',statement_timestamp()-interval '1 hour')",UUID.randomUUID(),id,"SIMULATED-path","a".repeat(64),"SIMULATED-body");
    o=observe();assertEquals(1,count(o,"receipt"));assertEquals(Instant.parse(o.path("copies").path("receipt").path("oldestEligibleAt").asText()),db.queryForObject("SELECT expires_at FROM privacy_command",Timestamp.class).toInstant());
  }
  @Test void watermarkAgeUnknownOverridesKnownRowsAndModelsPostDeletionOrphanAck()throws Exception {
    UUID id=root(false);read(id,"SIMULATED-reader");receipt(id,"SIMULATED-receipt");
    db.update("INSERT INTO privacy_ack VALUES(?,1,'SIMULATED-issuer','SIMULATED-ack',?)",id,Timestamp.from(past));
    atDeadline(id,"UPDATE privacy_alert SET read_expired_through=1,history_expired_through=1,last_history_expired_at=statement_timestamp()-interval '1 day'");
    UUID known=root(true);read(known,"SIMULATED-known");
    var o=observe();assertEquals(2,count(o,"read"));
    for(String k:List.of("read","receipt","ack","evidence")){assertTrue(count(o,k)>0);assertTrue(o.path("copies").path(k).path("oldestEligibleAt").isNull(),k);}
    String before=sourceDigest();assertEquals(2,db.queryForObject("SELECT privacy_cleanup(200)",Integer.class));
    var after=observe();assertEquals(0,after.path("eligibleRootCount").asInt());assertEquals(0,db.queryForObject("SELECT count(*) FROM privacy_ack",Integer.class));assertEquals(before,sourceDigest());
  }
  @Test void lockedEligibleRootRemainsVisibleWhenCleanupReturnsZero()throws Exception {
    UUID id=root(true);read(id,"SIMULATED-locked");
    try(var lock=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var s=lock.createStatement()){
      lock.setAutoCommit(false);s.execute("SELECT id FROM privacy_alert WHERE id='"+id+"' FOR UPDATE");
      assertEquals(0,db.queryForObject("SELECT privacy_cleanup(200)",Integer.class));assertEquals(1,observe().path("eligibleRootCount").asInt());assertEquals(1,count(observe(),"read"));lock.rollback();
    }
    assertEquals(1,db.queryForObject("SELECT privacy_cleanup(200)",Integer.class));assertEquals(0,count(observe(),"read"));
  }
  @Test void pendingRemovalAndExpiredLedgerAreVisibleWithoutRootWork() {
    db.queryForObject("SELECT privacy_request_removal('SIMULATED-issuer','SIMULATED-empty')",UUID.class);
    var o=observe();assertEquals(0,o.path("eligibleRootCount").asInt());assertEquals(1,o.path("pendingRemoval").path("count").asInt());
    db.queryForObject("SELECT privacy_cleanup(200)",Integer.class);assertEquals(0,observe().path("pendingRemoval").path("count").asInt());
    db.update("UPDATE privacy_removal SET completed_at=statement_timestamp()-interval '67 days',expires_at=statement_timestamp()-interval '1 day'");
    o=observe();assertEquals(1,count(o,"ledger"));assertEquals(0,o.path("eligibleRootCount").asInt());
    assertEquals(0,db.queryForObject("SELECT privacy_cleanup(200)",Integer.class));assertEquals(0,count(observe(),"ledger"));
  }
  @Test void broaderInactiveReceiptDeleteCoverageDoesNotBecomeFalseAllClear()throws Exception {
    UUID id=root(true);db.update("DELETE FROM privacy_evidence WHERE alert_id=?",id);receipt(id,"SIMULATED-synthetic-uncapped");
    // Deliberately broader stored fixture than application writes: receipt expiry is not capped to inactive+30d.
    assertEquals(1,count(observe(),"receipt"));assertEquals(1,observe().path("eligibleRootCount").asInt());
    assertEquals(0,db.queryForObject("SELECT privacy_cleanup(200)",Integer.class));assertEquals(1,count(observe(),"receipt"));
  }
}
