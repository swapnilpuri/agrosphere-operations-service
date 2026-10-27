package com.agrosphere.operations;

import static com.agrosphere.operations.domain.CollectionRules.*;
import static org.junit.jupiter.api.Assertions.*;

import com.agrosphere.operations.application.*;
import com.agrosphere.operations.domain.*;
import com.agrosphere.operations.domain.LatexCollection.*;
import com.agrosphere.operations.infrastructure.persistence.CollectionStore;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

class CollectionStoreIT {
  static PostgreSQLContainer postgres;
  JdbcTemplate db;
  CollectionStore store;
  TransactionTemplate tx;
  final Instant now=Instant.parse("2026-10-27T18:00:00Z");
  final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
  final UUID estate=UUID.randomUUID(),correlation=UUID.randomUUID();
  final CollectionScope global=new CollectionScope(null,null);

  @BeforeAll static void start()throws Exception{
    postgres=new PostgreSQLContainer("postgres:17-alpine");postgres.start();
    CollectionTestSupport.provisionNoLoginRuntimeRole(postgres);
  }
  @AfterAll static void stop(){if(postgres!=null)postgres.stop();}

  @BeforeEach void setup(){
    var schema="store_"+UUID.randomUUID().toString().replace("-","");
    Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
    var url=postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema;
    var source=new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword());
    db=new JdbcTemplate(source);store=new CollectionStore(new NamedParameterJdbcTemplate(source));
    tx=new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  LatexCollection draft(UUID estateId,String worker,String reference){
    var id=UUID.randomUUID();var assignment=UUID.randomUUID();var block=UUID.randomUUID();var record=UUID.randomUUID();
    var date=LocalDate.of(2026,10,27);var started=now.minusSeconds(7200);var completed=now.minusSeconds(3600);
    db.update("INSERT INTO tapping_assignment(id,estate_id,block_id,worker_id,tapping_date,planned_tree_count,status,created_at,updated_at,version,created_by,updated_by) VALUES (?,?,?,?,?,10,'COMPLETED',?,?,2,'actor','actor')",
        assignment,estateId,block,worker,date,Timestamp.from(started),Timestamp.from(completed));
    db.update("INSERT INTO tapping_record(id,assignment_id,worker_id,estate_id,block_id,tapping_date,planned_tree_count,actual_tree_count,started_at,completed_at,created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,10,8,?,?,'actor',?,?)",
        record,assignment,worker,estateId,block,date,Timestamp.from(started),Timestamp.from(completed),Timestamp.from(started),Timestamp.from(completed));
    var a=new TappingAssignment(assignment,null,estateId,block,worker,date,10,Rules.AssignmentStatus.COMPLETED,started,completed,2,"actor","actor");
    var r=new TappingRecord(record,assignment,worker,estateId,block,date,10,8,started,completed,null,"actor",started,completed);
    return LatexCollection.create(id,r,a,new MasterSnapshot(estateId,block,UUID.randomUUID(),"UTC",1,2,now),reference,
        new WeightObservation(now.minusSeconds(1800),new BigDecimal("125.500"),new WeightEvidence(WeightMethod.SCALE,"SIMULATED-SCALE",null),null),
        Provenance.SIMULATED,null,clock,"actor");
  }

  CollectionRevision revision(LatexCollection c,CommandKind kind){return new CollectionRevision(c.id(),c.version(),kind,c,c.updatedAt(),c.updatedBy(),correlation);}

  LatexCollection persist(UUID estateId,String worker,String reference){
    var c=draft(estateId,worker,reference);
    tx.executeWithoutResult(s->{store.insert(c,global);store.append(revision(c,CommandKind.CREATE),global);});
    return c;
  }

  WeightObservation weight(LatexCollection c,String mass){return new WeightObservation(c.weight().collectedAt(),new BigDecimal(mass),c.weight().evidence(),null);}

  @Test void roundtripAndScopeAreAppliedBeforePaginationCountsAndHistory(){
    var a=persist(estate,"worker","A");persist(estate,"worker","B");persist(estate,"other-worker","C");persist(UUID.randomUUID(),"worker","D");
    var scope=new CollectionScope(List.of(estate),"worker");
    assertEquals(a,store.find(a.id(),scope).orElseThrow());assertEquals(2,store.count(scope));
    assertEquals(1,store.list(scope,1,1).size());assertTrue(store.list(scope,2,1).isEmpty());
    for(var c:store.list(scope,0,100)){assertEquals(estate,c.attribution().estateId());assertEquals("worker",c.attribution().workerId());}
    var foreign=new CollectionScope(List.of(UUID.randomUUID()),null);
    assertTrue(store.find(a.id(),foreign).isEmpty());assertTrue(store.history(a.id(),foreign,0,20).isEmpty());assertEquals(0,store.historyCount(a.id(),foreign));
    var empty=new CollectionScope(List.of(),null);assertEquals(0,store.count(empty));assertTrue(store.list(empty,0,20).isEmpty());
    assertEquals(4,store.count(global));
  }

  @Test void measurementCorrectionVoidAndExactImmutableHistoryRoundtrip()throws Exception{
    var old=persist(estate,"worker","AUDIT");var before=revision(old,CommandKind.CREATE);
    var m=new DrcMeasurement(UUID.randomUUID(),old.id(),1,new BigDecimal("32.40"),now.minusSeconds(600),DrcMethod.FIELD_TEST,"SIMULATED-DRC",null,null,Provenance.SIMULATED,now,"actor");
    var measured=old.recordDrc(0,m,clock,"actor");
    tx.executeWithoutResult(s->{store.append(m,global);store.save(0,measured,global);store.append(revision(measured,CommandKind.DRC),global);});
    assertEquals(measured,store.find(old.id(),global).orElseThrow());
    var corrected=measured.correct(1,weight(measured,"100.000"),null,"scale correction",clock,"manager");
    var correctedRevision=revision(corrected,CommandKind.CORRECT);
    tx.executeWithoutResult(s->{store.save(1,corrected,global);store.append(correctedRevision,global);store.append(new CollectionAdjustment(UUID.randomUUID(),revision(measured,CommandKind.DRC),correctedRevision,"scale correction",now,"manager",correlation),global);});
    var voided=corrected.voidCollection(2,"duplicate physical lot",clock,"manager");var voidRevision=revision(voided,CommandKind.VOID);
    tx.executeWithoutResult(s->{store.save(2,voided,global);store.append(voidRevision,global);store.append(new CollectionAdjustment(UUID.randomUUID(),correctedRevision,voidRevision,"duplicate physical lot",now,"manager",correlation),global);});
    assertEquals(voided,store.find(old.id(),global).orElseThrow());assertEquals(4,store.historyCount(old.id(),global));
    var history=store.history(old.id(),global,0,100);
    assertNull(history.get(0).get("dryRubberKg"));assertEquals(new BigDecimal("40.662"),history.get(1).get("dryRubberKg"));
    assertEquals(0,new BigDecimal("32.400").compareTo((BigDecimal)history.get(2).get("dryRubberKg")));
    assertEquals("VOIDED",history.get(3).get("status"));assertEquals(2,store.history(old.id(),global,1,2).size());
    assertNull(before.snapshot().measurement());assertEquals(new BigDecimal("125.500"),before.snapshot().weight().wetLatexKg());
    var folder=Path.of("target/collection-snapshots");Files.createDirectories(folder);
    var json=new ObjectMapper();
    for(var entry:Map.of("created",old,"measured",measured,"corrected",corrected,"voided",voided).entrySet())
      Files.writeString(folder.resolve(entry.getKey()+".json"),json.writeValueAsString(CollectionSnapshots.view(entry.getValue())));
  }

  @Test void writesRequireTransactionAndTrustedScope(){
    var c=draft(estate,"worker","SCOPE");
    assertThrows(IllegalStateException.class,()->store.insert(c,global));
    assertThrows(AccessDeniedException.class,()->tx.executeWithoutResult(s->store.insert(c,new CollectionScope(List.of(UUID.randomUUID()),null))));
    assertEquals(0,store.count(global));
  }

  @Test void failedPersistenceStageRollsBackRootAndRevisionTogether(){
    var c=draft(estate,"worker","ROLLBACK");
    assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{store.insert(c,global);store.append(revision(c,CommandKind.CREATE),global);throw new IllegalStateException("injected after revision");}));
    assertEquals(0,store.count(global));assertEquals(0,db.queryForObject("SELECT count(*) FROM collection_revision",Integer.class));
  }

  @Test void staleWriterRollsBackUnselectedMeasurementEvidence(){
    var old=persist(estate,"worker","STALE");var changed=old.correct(0,weight(old,"126"),null,"fix",clock,"actor");
    tx.executeWithoutResult(s->{store.save(0,changed,global);store.append(revision(changed,CommandKind.CORRECT),global);});
    var m=new DrcMeasurement(UUID.randomUUID(),old.id(),1,new BigDecimal("50"),now,DrcMethod.FIELD_TEST,"SIMULATED-DRC",null,null,Provenance.SIMULATED,now,"actor");
    var stale=old.recordDrc(0,m,clock,"actor");
    assertThrows(OptimisticLockingFailureException.class,()->tx.executeWithoutResult(s->{store.append(m,global);store.save(0,stale,global);store.append(revision(stale,CommandKind.DRC),global);}));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM drc_measurement",Integer.class));assertEquals(changed,store.find(old.id(),global).orElseThrow());
  }

  @Test void concurrentConditionalWritersCommitOneRevision()throws Exception{
    var old=persist(estate,"worker","RACE");var barrier=new CyclicBarrier(2);
    try(var pool=Executors.newFixedThreadPool(2)){
      var jobs=new ArrayList<Callable<Boolean>>();
      for(String mass:List.of("100","110"))jobs.add(()->{
        var changed=old.correct(0,weight(old,mass),null,"concurrent correction",clock,"actor");
        try{return tx.execute(s->{try{barrier.await(5,TimeUnit.SECONDS);}catch(Exception ex){throw new IllegalStateException(ex);}
          store.save(0,changed,global);store.append(revision(changed,CommandKind.CORRECT),global);return true;});}
        catch(OptimisticLockingFailureException ex){return false;}
      });
      var results=pool.invokeAll(jobs);assertEquals(1,results.stream().filter(f->{try{return f.get();}catch(Exception ex){throw new RuntimeException(ex);}}).count());
    }
    assertEquals(1,store.find(old.id(),global).orElseThrow().version());assertEquals(2,store.historyCount(old.id(),global));
  }

  @Test void restrictedRoleCanUseAdapterWithoutEvidenceUpdateOrDdlPrivileges(){
    var c=draft(estate,"worker","RESTRICTED");
    tx.executeWithoutResult(s->{db.execute("SET LOCAL ROLE agrosphere_operations_app");store.insert(c,global);store.append(revision(c,CommandKind.CREATE),global);});
    var m=new DrcMeasurement(UUID.randomUUID(),c.id(),1,new BigDecimal("25.00"),now,DrcMethod.FIELD_TEST,"SIMULATED-DRC",null,null,Provenance.SIMULATED,now,"actor");
    var measured=c.recordDrc(0,m,clock,"actor");
    tx.executeWithoutResult(s->{db.execute("SET LOCAL ROLE agrosphere_operations_app");store.append(m,global);store.save(0,measured,global);store.append(revision(measured,CommandKind.DRC),global);
      assertEquals(measured,store.find(c.id(),global).orElseThrow());assertEquals(2,store.historyCount(c.id(),global));});
    assertEquals(new BigDecimal("31.375"),store.find(c.id(),global).orElseThrow().calculation().dryKg());
  }
}
