package com.agrosphere.operations;

import static com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import static org.junit.jupiter.api.Assertions.*;
import com.agrosphere.operations.workforce.application.WorkforcePorts.*;
import com.agrosphere.operations.workforce.domain.*;
import com.agrosphere.operations.workforce.infrastructure.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Generated disposable databases only. No existing stack, private env or real records. */
class WorkforceStoreIT {
  static PostgreSQLContainer postgres;
  JdbcTemplate db; JdbcWorkforceStore store; WorkforceTransactions commands; TransactionTemplate tx;
  final UUID estate=UUID.randomUUID(),correlation=UUID.randomUUID();
  final ReadScope scope=new ReadScope(Set.of(estate));
  final LocalDate date=LocalDate.of(2026,10,29);
  final Instant now=Instant.parse("2026-10-29T12:00:00Z");
  final String actor="SIMULATED-opaque-sub";
  String schema,url;
  @BeforeAll static void start()throws Exception {
    postgres=new PostgreSQLContainer("postgres:17-alpine");postgres.start();
    CollectionTestSupport.provisionNoLoginRuntimeRole(postgres);
  }
  @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
  @BeforeEach void setup() {
    schema="workforce_"+UUID.randomUUID().toString().replace("-","");
    Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
    url=postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema;
    var source=new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword());
    db=new JdbcTemplate(source);store=new JdbcWorkforceStore(new NamedParameterJdbcTemplate(source));commands=new WorkforceTransactions(store);
    tx=new TransactionTemplate(new DataSourceTransactionManager(source));
  }
  Command manager(UUID estateId) {return new Command(new Actor(actor,Set.of("ESTATE_MANAGER"),Set.of(estateId)),Clock.fixed(now,ZoneOffset.UTC),correlation);}
  EstateContext master(UUID estateId){return new EstateContext(estateId,ZoneId.of("Africa/Libreville"));}
  WorkerRegistration draft(UUID estateId,String code) {
    return WorkerRegistration.register(UUID.randomUUID(),UUID.randomUUID(),master(estateId),code,"SIMULATED — Worker",null,date.minusDays(1),Provenance.SIMULATED,manager(estateId));
  }
  void revision(Aggregate root,CommandKind kind,String reason) {
    store.append(new Revision<>(root.id(),root.audit().version(),kind,reason,root.audit().updatedAt(),root.audit().updatedBy(),correlation,root));
  }
  WorkerRegistration persisted(String code) {
    var w=draft(estate,code);tx.executeWithoutResult(s->{store.insertWorker(w);revision(w,CommandKind.REGISTER,null);});return w;
  }
  AttendanceDay record(WorkerRegistration w) {
    var d=AttendanceDay.record(UUID.randomUUID(),w,date,AttendanceStatus.PRESENT,master(w.estateId()),null,manager(w.estateId()));
    tx.executeWithoutResult(s->{store.insertDay(d);revision(d,CommandKind.RECORD,null);});return d;
  }
  Receipt receipt(String who,UUID key,String hash,String body){return new Receipt(who,key,"POST","/api/v1/workers",hash,201,body,"/api/v1/workers/SIMULATED");}

  @Test void roundtripFiniteScopeAndHistoryProtectForeignRecords() {
    var w=persisted("SIMULATED-01");var d=record(w);
    assertEquals(w,store.getWorker(w.id(),scope));assertEquals(d,store.getDay(d.id(),scope));
    assertEquals(estate,store.workerEstate(w.id()));assertEquals(estate,store.attendanceEstate(d.id()));
    assertEquals(1,store.history(w.id(),true,scope,0,20).size());
    assertEquals(1,store.history(d.id(),false,scope,0,20).size());
    assertTrue(store.history(d.id(),false,scope,1,20).isEmpty());
    var empty=new ReadScope(Set.of());var foreign=new ReadScope(Set.of(UUID.randomUUID()));
    for(var denied:List.of(empty,foreign)){
      assertThrows(EmptyResultDataAccessException.class,()->store.getWorker(w.id(),denied));
      assertThrows(EmptyResultDataAccessException.class,()->store.history(d.id(),false,denied,0,20));
      assertTrue(store.findDay(w.id(),date,denied).isEmpty());
    }
    assertThrows(Invalid.class,()->store.history(w.id(),true,scope,Integer.MAX_VALUE,100));
  }
  @Test void codesAndDailyIdentityStayUniqueIncludingVoidedDays() {
    var w=persisted("SIMULATED-01");
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->{var duplicate=draft(estate,"SIMULATED-01");store.insertWorker(duplicate);revision(duplicate,CommandKind.REGISTER,null);}));
    var foreign=draft(UUID.randomUUID(),"SIMULATED-01");
    tx.executeWithoutResult(s->{store.insertWorker(foreign);revision(foreign,CommandKind.REGISTER,null);});
    var d=record(w);var voided=d.voidDay(0,"SIMULATED mistake",manager(estate));
    tx.executeWithoutResult(s->{store.updateDay(voided,0);revision(voided,CommandKind.VOID,"SIMULATED mistake");});
    assertThrows(RuntimeException.class,()->record(w));
    assertEquals(AttendanceStatus.VOIDED,store.findDay(w.id(),date,scope).orElseThrow().status());
  }
  @Test void registrationCloseAndReactivationRetainImmutableHistory() {
    var w=persisted("SIMULATED-01");
    var closed=w.closePeriod(0,w.activePeriods().getFirst().id(),date,"SIMULATED close",Set.of(),master(estate).timezone(),manager(estate));
    tx.executeWithoutResult(s->{store.updateWorker(closed,0);revision(closed,CommandKind.PERIOD_CLOSED,"SIMULATED close");});
    var reopened=closed.openPeriod(1,UUID.randomUUID(),date,"SIMULATED reopen",master(estate),manager(estate));
    tx.executeWithoutResult(s->{store.updateWorker(reopened,1);revision(reopened,CommandKind.PERIOD_OPENED,"SIMULATED reopen");});
    assertEquals(reopened,store.getWorker(w.id(),scope));assertEquals(3,store.history(w.id(),true,scope,0,20).size());
    assertThrows(DataAccessException.class,()->db.update("UPDATE worker_revision SET reason='tamper' WHERE worker_id=?",w.id()));
  }
  @Test void databaseRejectsAClosureThatBypassesRetainedAttendanceCheck() {
    var w=persisted("SIMULATED-01");record(w);
    // Deliberately supply an empty retained-date set; the database must independently reject commit.
    var invalid=w.closePeriod(0,w.activePeriods().getFirst().id(),date,"SIMULATED bypass",Set.of(),master(estate).timezone(),manager(estate));
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->{store.updateWorker(invalid,0);revision(invalid,CommandKind.PERIOD_CLOSED,"SIMULATED bypass");}));
    assertEquals(w,store.getWorker(w.id(),scope));
  }
  @Test void staleConcurrentProfileUpdatesCannotLoseARevision()throws Exception {
    var w=persisted("SIMULATED-01");var start=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
    try{
      List<Future<Boolean>> results=new ArrayList<>();
      for(String name:List.of("SIMULATED A","SIMULATED B"))results.add(pool.submit(()->{
        start.await(5,TimeUnit.SECONDS);var changed=w.edit(0,name,false,null,"SIMULATED concurrent",manager(estate));
        try{tx.executeWithoutResult(s->{store.updateWorker(changed,0);revision(changed,CommandKind.PROFILE,"SIMULATED concurrent");});return true;}
        catch(Conflict expected){return false;}
      }));
      assertEquals(1,(results.get(0).get(10,TimeUnit.SECONDS)?1:0)+(results.get(1).get(10,TimeUnit.SECONDS)?1:0));
      assertEquals(1,store.getWorker(w.id(),scope).audit().version());assertEquals(2,store.history(w.id(),true,scope,0,20).size());
    }finally{pool.shutdownNow();}
  }
  @Test void workerMutexSerializesCloseBeforeNewAttendance()throws Exception {
    var w=persisted("SIMULATED-01");var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
    var pool=Executors.newFixedThreadPool(2);
    try{
      var close=pool.submit(()->tx.executeWithoutResult(s->{
        var current=store.lockWorker(w.id(),scope);locked.countDown();await(release);
        var closed=current.closePeriod(0,current.activePeriods().getFirst().id(),date,"SIMULATED close",store.nonvoidedAttendanceDates(w.id(),scope),master(estate).timezone(),manager(estate));
        store.updateWorker(closed,0);revision(closed,CommandKind.PERIOD_CLOSED,"SIMULATED close");
      }));
      assertTrue(locked.await(5,TimeUnit.SECONDS));
      var observe=pool.submit(()->{attempted.countDown();return assertThrows(Invalid.class,()->tx.executeWithoutResult(s->{
        var current=store.lockWorker(w.id(),scope);
        var d=AttendanceDay.record(UUID.randomUUID(),current,date,AttendanceStatus.PRESENT,master(estate),null,manager(estate));
        store.insertDay(d);revision(d,CommandKind.RECORD,null);
      }));});
      assertTrue(attempted.await(5,TimeUnit.SECONDS));release.countDown();close.get(10,TimeUnit.SECONDS);observe.get(10,TimeUnit.SECONDS);
      assertTrue(store.findDay(w.id(),date,scope).isEmpty());assertTrue(store.getWorker(w.id(),scope).periodOn(date).isEmpty());
    }finally{release.countDown();pool.shutdownNow();}
  }
  private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Disposable test synchronization timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
  @Test void receiptsReplayOriginalSuccessAndNeverInvokeDuplicateWork() {
    var key=UUID.randomUUID();String hash="a".repeat(64);var count=new AtomicInteger();var w=draft(estate,"SIMULATED-01");
    var first=tx.execute(s->commands.execute(actor,key,"POST","/api/v1/workers",hash,(workers,days,evidence)->{
      count.incrementAndGet();workers.insertWorker(w);revision(w,CommandKind.REGISTER,null);return receipt(actor,key,hash,"{\"version\":0}");}));
    var edited=w.edit(0,"SIMULATED changed",false,null,"SIMULATED change",manager(estate));
    tx.executeWithoutResult(s->{store.updateWorker(edited,0);revision(edited,CommandKind.PROFILE,"SIMULATED change");});
    var replay=tx.execute(s->commands.execute(actor,key,"POST","/api/v1/workers",hash,(workers,days,evidence)->{fail("replay ran work");return null;}));
    assertEquals(first,replay);assertEquals(1,count.get());
    assertThrows(Conflict.class,()->tx.execute(s->commands.execute(actor,key,"POST","/api/v1/workers","b".repeat(64),(workers,days,evidence)->null)));
    assertEquals(edited,store.getWorker(w.id(),scope));
  }
  @Test void failureRollsBackRootRevisionReceiptAndOutboxTogether() {
    var key=UUID.randomUUID();String hash="a".repeat(64);var w=draft(estate,"SIMULATED-01");var eventId=UUID.randomUUID();
    var event=WorkforceState.stringify(WorkforceState.fields("eventId",eventId.toString(),"aggregateId",w.id().toString(),
        "eventType","WorkerRegistrationCreated","eventVersion",1,"aggregateVersion",0,"correlationId",correlation.toString(),
        "timestamp",now.toString(),"source","agrosphere-operations-service","estateId",estate.toString(),"dataProvenance","SIMULATED",
        "payload",Map.of("snapshot",Map.of("registrationId",w.id().toString()))));
    assertThrows(IllegalStateException.class,()->tx.execute(s->commands.execute(actor,key,"POST","/api/v1/workers",hash,(workers,days,evidence)->{
      workers.insertWorker(w);revision(w,CommandKind.REGISTER,null);evidence.appendEvent(eventId,"WorkerRegistrationCreated",0,event);
      throw new IllegalStateException("SIMULATED failure after outbox insert");})));
    for(String table:List.of("worker_registration","worker_active_period","worker_revision","workforce_command","outbox_event"))
      assertEquals(0,db.queryForObject("SELECT count(*) FROM "+table,Long.class));
  }
  @Test void missingRevisionAndIncompleteReceiptCannotCommit() {
    var w=draft(estate,"SIMULATED-01");
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->store.insertWorker(w)));
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->store.reserve(actor,UUID.randomUUID(),"POST","/api/v1/workers","a".repeat(64))));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM worker_registration",Long.class));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM workforce_command",Long.class));
  }
  @Test void runtimeCannotChangeEvidenceDeleteRootsOrExecuteDdl()throws Exception {
    var w=persisted("SIMULATED-01");record(w);
    try(var connection=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword())){
      try(var statement=connection.createStatement()){
        statement.execute("SET ROLE agrosphere_operations_app");
        for(String sql:List.of("UPDATE worker_revision SET reason='tamper'","DELETE FROM attendance_revision", "DELETE FROM worker_registration", "CREATE TABLE denied_workforce_ddl(id integer)"))
          assertThrows(SQLException.class,()->statement.execute(sql));
        assertDoesNotThrow(()->statement.execute("SELECT snapshot FROM worker_registration"));
      }
    }
  }
  @Test void realProvenanceIsBlockedEvenIfAnAdapterBypassesDomainCreate() {
    var w=draft(estate,"SIMULATED-01");var invalid=new WorkerRegistration(w.id(),estate,w.workerCode(),w.displayName(),null,Provenance.REAL,w.activePeriods(),w.audit());
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->{store.insertWorker(invalid);revision(invalid,CommandKind.REGISTER,null);}));
  }
  @Test void v3UpgradeRetainsExistingOutboxAndProcessedEvidence() {
    String isolated="upgrade_"+UUID.randomUUID().toString().replace("-","");
    var config=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(isolated).defaultSchema(isolated);
    config.target("3").load().migrate();
    var oldDb=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+isolated,postgres.getUsername(),postgres.getPassword()));
    var event=UUID.randomUUID();oldDb.update("INSERT INTO processed_event(consumer_name,event_id) VALUES('SIMULATED-workforce-upgrade',?)",event);
    config.target("4").load().migrate();
    assertEquals(1,oldDb.queryForObject("SELECT count(*) FROM processed_event WHERE event_id=?",Integer.class,event));
    assertEquals(4,oldDb.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL",Integer.class));
    assertEquals(0,oldDb.queryForObject("SELECT count(*) FROM worker_registration",Integer.class));
  }
  @Test void boundedReservationWaitLeavesOriginalCommandReplayable()throws Exception {
    var key=UUID.randomUUID();String hash="a".repeat(64);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
    var pool=Executors.newSingleThreadExecutor();
    try {
      var holder=pool.submit(()->tx.executeWithoutResult(s->{
        assertTrue(store.reserve(actor,key,"POST","/api/v1/workers",hash).isEmpty());locked.countDown();await(release);
        store.saveReceipt(receipt(actor,key,hash,"{\"SIMULATED\":true}"));
      }));
      assertTrue(locked.await(5,TimeUnit.SECONDS));
      var failure=assertThrows(DataAccessException.class,()->tx.execute(s->store.reserve(actor,key,"POST","/api/v1/workers",hash)));
      Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();
      assertInstanceOf(SQLException.class,cause);assertEquals("55P03",((SQLException)cause).getSQLState());
      release.countDown();holder.get(10,TimeUnit.SECONDS);
      assertTrue(tx.execute(s->store.reserve(actor,key,"POST","/api/v1/workers",hash)).isPresent());
    }finally{release.countDown();pool.shutdownNow();}
  }
  @Test void failedV4MigrationRollsBackItsDdlAndLeavesV3DataIntact()throws Exception {
    String isolated="rollback_"+UUID.randomUUID().toString().replace("-","");
    Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(isolated).defaultSchema(isolated).target("3").load().migrate();
    var isolatedUrl=postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+isolated;
    var retained=new JdbcTemplate(new DriverManagerDataSource(isolatedUrl,postgres.getUsername(),postgres.getPassword()));
    var marker=UUID.randomUUID();retained.update("INSERT INTO processed_event(consumer_name,event_id) VALUES('SIMULATED-rollback',?)",marker);
    Path target=Path.of("target").toAbsolutePath().normalize();Files.createDirectories(target);
    Path directory=Files.createTempDirectory(target,"wa1-failed-ddl-").toAbsolutePath().normalize();
    assertTrue(directory.startsWith(target));
    try {
      Path migrations=Path.of("src/main/resources/db/migration");
      try(var files=Files.list(migrations)){
        for(var file:files.filter(p->p.getFileName().toString().matches("V[1-4]__.*\\.sql")).toList())
          Files.copy(file,directory.resolve(file.getFileName()));
      }
      Files.writeString(directory.resolve("V4__worker_registration_and_attendance.sql"),"\nSELECT 1/0;\n",StandardOpenOption.APPEND);
      assertThrows(RuntimeException.class,()->Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
          .schemas(isolated).defaultSchema(isolated).locations("filesystem:"+directory).load().migrate());
      assertEquals(1,retained.queryForObject("SELECT count(*) FROM processed_event WHERE event_id=?",Integer.class,marker));
      assertNull(retained.queryForObject("SELECT to_regclass('worker_registration')::text",String.class));
      assertEquals(3,retained.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL",Integer.class));
    }finally{
      // Only this generated directory, verified within target, is ever recursively removed.
      assertTrue(directory.startsWith(target));
      try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
  }
  @Test void badProjectionSnapshotCannotBeWrittenThroughDirectSql() {
    var w=persisted("SIMULATED-01");var edited=w.edit(0,"SIMULATED new",false,null,"SIMULATED edit",manager(estate));
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->{
      db.update("UPDATE worker_registration SET display_name=?,version=1,snapshot='{}'::jsonb WHERE id=?",edited.displayName(),w.id());
    }));
    assertEquals(w,store.getWorker(w.id(),scope));
  }
}
