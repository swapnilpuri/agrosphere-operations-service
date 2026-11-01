package com.agrosphere.operations;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import static org.junit.jupiter.api.Assertions.*;
import com.agrosphere.operations.crophealth.domain.*;
import com.agrosphere.operations.crophealth.application.*;
import com.agrosphere.operations.crophealth.application.CropHealthPorts.*;
import com.agrosphere.operations.crophealth.infrastructure.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CropHealthStoreIT {
  static PostgreSQLContainer postgres;JdbcTemplate db;JdbcCropHealthStore store;TransactionTemplate tx;String schema,url;
  final UUID estate=UUID.randomUUID(),block=UUID.randomUUID();final Instant now=Instant.parse("2026-10-31T12:00:00Z");
  final ReadScope scope=new ReadScope(estate);
  @BeforeAll static void start()throws Exception{postgres=new PostgreSQLContainer("postgres:17-alpine");postgres.start();CollectionTestSupport.provisionNoLoginRuntimeRole(postgres);}
  @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
  @BeforeEach void setup(){
    schema="crop_"+UUID.randomUUID().toString().replace("-","");
    Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
    url=postgres.getJdbcUrl()+"&currentSchema="+schema;var ds=new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword());
    db=new JdbcTemplate(ds);store=new JdbcCropHealthStore(new NamedParameterJdbcTemplate(ds));tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  }
  Command c(){return new Command(new Actor("SIMULATED-manager",Set.of("ESTATE_MANAGER"),Set.of(estate)),Clock.fixed(now,ZoneOffset.UTC),UUID.randomUUID());}
  Context context(){return new Context(estate,block,ZoneId.of("Africa/Libreville"));}
  Report report(){return new Report(now.minusSeconds(60),Category.TREE_DAMAGE,Severity.LOW,"SIMULATED summary","SIMULATED details");}
  CropHealthChanges.ChangeSet pair(){return CropHealthChanges.record(UUID.randomUUID(),UUID.randomUUID(),context(),Provenance.SIMULATED,new Observation(report().observedAt(),Outcome.FOLLOW_UP,"SIMULATED findings"),report(),null,c());}
  void save(CropHealthChanges.ChangeSet result){
    for(var revision:result.revisions()){var root=revision.snapshot();if(root.audit().version()==0)store.insert(root);else store.update(root,root.audit().version()-1);}
    result.revisions().forEach(store::appendRevision);
  }
  CropHealthChanges.ChangeSet persisted(){var result=pair();tx.executeWithoutResult(s->save(result));return result;}
  @Test void linkedRoundTripScopedHistoryAndMinimalOutbox(){
    var result=persisted();assertEquals(result.inspection(),store.inspection(result.inspection().id(),scope));assertEquals(result.incident(),store.incident(result.incident().id(),scope));
    assertEquals(1,store.history(result.incident().id(),false,scope,0,25).totalElements());
    assertThrows(org.springframework.dao.EmptyResultDataAccessException.class,()->store.inspection(result.inspection().id(),new ReadScope(UUID.randomUUID())));
    assertEquals(2L,db.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type IN('FieldInspectionRecorded','CropHealthIncidentReported')",Long.class));
    for(String raw:db.queryForList("SELECT payload::text FROM outbox_event",String.class)){
      assertFalse(raw.contains("summary"));assertFalse(raw.contains("findings"));assertFalse(raw.contains("SIMULATED-manager"));
    }
  }
  @Test void rootWithoutRevisionAndPartialPairRollBack(){
    var result=pair();assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->store.insert(result.inspection())));
    assertEquals(0L,db.queryForObject("SELECT count(*) FROM field_inspection",Long.class));
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->{save(result);throw new IllegalStateException("SIMULATED failure before receipt");}));
    assertEquals(0L,db.queryForObject("SELECT count(*) FROM outbox_event",Long.class));
  }
  @Test void pairedVersionsSerializeVoidBeforeClearAndRetainHistoricalLink(){
    var result=persisted();UUID id=result.incident().id();
    tx.executeWithoutResult(s->{var i=store.lockInspection(result.inspection().id(),scope);var incident=store.lockIncident(id,scope);
      save(CropHealthChanges.transition(incident,i,0,0L,Kind.VOID,null,null,"SIMULATED void",c()));});
    tx.executeWithoutResult(s->{var i=store.lockInspection(result.inspection().id(),scope);var incident=store.lockIncident(id,scope);
      assertThrows(Conflict.class,()->CropHealthChanges.correctInspection(i,incident,0,0L,new Observation(i.observation().observedAt(),Outcome.CLEAR,"SIMULATED correction"),null,null,"SIMULATED",c()));
      save(CropHealthChanges.correctInspection(i,incident,0,1L,new Observation(i.observation().observedAt(),Outcome.CLEAR,"SIMULATED correction"),null,null,"SIMULATED",c()));});
    assertEquals(id,store.inspection(result.inspection().id(),scope).linkedIncidentId());
    assertEquals(2,store.history(id,false,scope,0,25).content().size());
  }
  @Test void originalReceiptReplayCannotDuplicateRootsOrEvents(){
    var result=pair();var commands=new CropHealthTransactions(store);UUID key=UUID.randomUUID();String path="/api/v1/inspections",hash="a".repeat(64);var calls=new AtomicInteger();
    java.util.function.Supplier<Receipt> work=()->{calls.incrementAndGet();save(result);return new Receipt("SIMULATED-manager",key,"POST",path,hash,1,201,"{\"SIMULATED\":true}",path+"/"+result.inspection().id());};
    var first=tx.execute(s->commands.execute("SIMULATED-manager",key,path,hash,work));
    var replay=tx.execute(s->commands.execute("SIMULATED-manager",key,path,hash,work));assertEquals(first,replay);assertEquals(1,calls.get());
    assertThrows(Conflict.class,()->tx.execute(s->commands.execute("SIMULATED-manager",key,path,"b".repeat(64),work)));
    assertEquals(2L,db.queryForObject("SELECT count(*) FROM outbox_event",Long.class));
  }
  @Test void incompleteReceiptCannotCommitAndRuntimeCannotRewriteEvidenceOrDdl()throws Exception{
    assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->store.reserve("SIMULATED-manager",UUID.randomUUID(),"/api/v1/incidents","a".repeat(64))));
    persisted();
    try(var connection=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword());var statement=connection.createStatement()){
      statement.execute("SET ROLE agrosphere_operations_app");
      for(String sql:List.of("DELETE FROM field_inspection","UPDATE incident_revision SET recorded_by='forged'","TRUNCATE inspection_revision","CREATE TABLE forbidden_crop(id int)"))
        assertThrows(SQLException.class,()->statement.execute(sql));
    }
  }
  @Test void retainedResolutionBoundaryAndScopedPaginationSurviveReload(){
    var result=persisted();
    tx.executeWithoutResult(s->{var i=store.lockInspection(result.inspection().id(),scope);var incident=store.lockIncident(result.incident().id(),scope);
      save(CropHealthChanges.transition(incident,i,0,0L,Kind.RESOLVE,now,"SIMULATED resolution","SIMULATED closure",c()));});
    var loaded=store.incident(result.incident().id(),scope);assertEquals(now,loaded.earliestRetainedResolutionAt());
    var filter=new Filter(estate,block,report().observedAt(),now,Provenance.SIMULATED,0,1);
    assertEquals(1,store.incidents(new IncidentFilter(filter,null,null,Set.of(Status.RESOLVED)),scope).totalElements());
    assertEquals(0,store.incidents(new IncidentFilter(filter,null,null,null),scope).totalElements());
    assertThrows(Denied.class,()->store.incidents(new IncidentFilter(filter,null,null,null),new ReadScope(UUID.randomUUID())));
  }
  @Test void failedV5DdlLeavesV4AndExistingEvidenceIntact()throws Exception{
    String target="upgrade_"+UUID.randomUUID().toString().replace("-","");
    Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(target).defaultSchema(target).target("4").load().migrate();
    Path scratch=Files.createTempDirectory(Path.of("target"),"crop-migration-");
    try {
      try(var source=Files.list(Path.of("src/main/resources/db/migration"))){for(Path f:source.toList())Files.copy(f,scratch.resolve(f.getFileName()));}
      Files.writeString(scratch.resolve("V5__inspections_and_crop_health.sql"),"\nSELECT 1/0;\n",StandardOpenOption.APPEND);
      assertThrows(RuntimeException.class,()->Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(target).defaultSchema(target).locations("filesystem:"+scratch.toAbsolutePath()).load().migrate());
      var owner=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl()+"&currentSchema="+target,postgres.getUsername(),postgres.getPassword()));
      assertNull(owner.queryForObject("SELECT to_regclass('field_inspection')",String.class));
      assertEquals(4L,owner.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL",Long.class));
    }finally{try(var paths=Files.list(scratch)){for(Path f:paths.toList())Files.delete(f);}Files.delete(scratch);}
  }
  @Test void concurrentLinkedEditsCannotBypassPairedVersion()throws Exception{
    var result=persisted();var ready=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
    try{
      var first=pool.submit(()->tx.executeWithoutResult(s->{var i=store.lockInspection(result.inspection().id(),scope);var incident=store.lockIncident(result.incident().id(),scope);ready.countDown();
        try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        save(CropHealthChanges.transition(incident,i,0,0L,Kind.VOID,null,null,"SIMULATED",c()));}));
      assertTrue(ready.await(5,TimeUnit.SECONDS));var second=pool.submit(()->assertThrows(Conflict.class,()->tx.executeWithoutResult(s->{var i=store.lockInspection(result.inspection().id(),scope);var incident=store.lockIncident(result.incident().id(),scope);
        save(CropHealthChanges.correctInspection(i,incident,0,0L,new Observation(i.observation().observedAt(),Outcome.CLEAR,"SIMULATED"),null,null,"SIMULATED",c()));})));
      release.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
      assertEquals(Outcome.FOLLOW_UP,store.inspection(result.inspection().id(),scope).observation().outcome());
    }finally{release.countDown();pool.shutdownNow();}
  }
  @Test void populatedCropEvidenceBacksUpAndRestoresIntoSeparateDisposableDatabase()throws Exception{
    var original=persisted();String clone="restore_"+UUID.randomUUID().toString().replace("-","");
    var dump=postgres.execInContainer("pg_dump","-U",postgres.getUsername(),"-d",postgres.getDatabaseName(),"--schema",schema,"--format=custom","-f","/tmp/ii1-crop.dump");
    assertEquals(0,dump.getExitCode(),dump.getStderr());
    var create=postgres.execInContainer("psql","-U",postgres.getUsername(),"-d","postgres","-c","CREATE DATABASE "+clone);
    assertEquals(0,create.getExitCode(),create.getStderr());
    var restore=postgres.execInContainer("pg_restore","-U",postgres.getUsername(),"--dbname",clone,"--no-owner","/tmp/ii1-crop.dump");
    assertEquals(0,restore.getExitCode(),restore.getStderr());
    String restoredUrl=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName()+"?","/"+clone+"?")+"&currentSchema="+schema;
    var restored=new JdbcCropHealthStore(new NamedParameterJdbcTemplate(new DriverManagerDataSource(restoredUrl,postgres.getUsername(),postgres.getPassword())));
    assertEquals(original.inspection(),restored.inspection(original.inspection().id(),scope));
    assertEquals(original.incident(),restored.incident(original.incident().id(),scope));
    assertEquals(1,restored.history(original.incident().id(),false,scope,0,25).totalElements());
    assertEquals(original.inspection(),store.inspection(original.inspection().id(),scope));
  }
}
