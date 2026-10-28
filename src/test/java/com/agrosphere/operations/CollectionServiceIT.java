package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;
import com.agrosphere.operations.application.*;
import com.agrosphere.operations.application.CollectionReplayStore.Receipt;
import com.agrosphere.operations.domain.*;
import com.agrosphere.operations.infrastructure.messaging.*;
import com.agrosphere.operations.infrastructure.persistence.CollectionStore;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

class CollectionServiceIT {
  static PostgreSQLContainer postgres;
  static GenericContainer<?> broker;
  static HttpServer master;
  static RSAKey key;
  static ConfigurableApplicationContext context;
  static JdbcTemplate db;
  static CollectionService service;
  static RabbitTemplate rabbit;
  static RabbitAdmin admin;
  static String issuer,queue;
  static final UUID estate=UUID.randomUUID(),block=UUID.randomUUID(),division=UUID.randomUUID();
  static volatile int masterStatus=200;
  static volatile UUID masterDivision=division;
  static volatile String masterZone="UTC";
  static final ObjectMapper json=new ObjectMapper();
  UUID record;
  String collected;

  static void sink(){
    admin.declareExchange(new TopicExchange("agrosphere.domain",true,false));
    admin.declareQueue(new org.springframework.amqp.core.Queue(queue,true,false,false,Map.of("x-max-length",1000)));
    for(String name:List.of("LatexCollectionRecorded","DrcMeasurementRecorded","LatexCollectionAdjusted","LatexCollectionVoided"))
      admin.declareBinding(new Binding(queue,Binding.DestinationType.QUEUE,"agrosphere.domain","agrosphere."+name+".v2",Map.of()));
  }

  @BeforeAll static void start()throws Exception{
    postgres=new PostgreSQLContainer("postgres:17-alpine").withPassword(UUID.randomUUID().toString());postgres.start();CollectionTestSupport.provisionNoLoginRuntimeRole(postgres);
    var password=UUID.randomUUID().toString();broker=new GenericContainer<>("rabbitmq:4.3.6-management").withExposedPorts(5672).withEnv("RABBITMQ_DEFAULT_USER","integration").withEnv("RABBITMQ_DEFAULT_PASS",password);broker.start();
    key=new RSAKeyGenerator(2048).keyID("collection-service-test").generate();master=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);issuer="http://127.0.0.1:"+master.getAddress().getPort();
    master.createContext("/jwks",e->{byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
    master.createContext("/api/v1",e->{var data=e.getRequestURI().getPath().contains("/blocks/")?
        Map.of("id",block,"estateId",estate,"divisionId",masterDivision,"version",2,"cropType","RUBBER","status","ACTIVE"):
        Map.of("id",estate,"timezone",masterZone,"version",1,"status","ACTIVE");
      byte[] body=json.writeValueAsBytes(data);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(masterStatus,body.length);e.getResponseBody().write(body);e.close();});master.start();
    context=SpringApplication.run(Application.class,"--server.port=0","--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
        "--spring.flyway.user="+postgres.getUsername(),"--spring.flyway.password="+postgres.getPassword(),"--spring.security.oauth2.resourceserver.jwt.issuer-uri="+issuer,"--OIDC_JWK_SET_URI="+issuer+"/jwks", "--operations.estate-url="+issuer,"--operations.outbox.enabled=false",
        "--spring.rabbitmq.host="+broker.getHost(),"--spring.rabbitmq.port="+broker.getMappedPort(5672),"--spring.rabbitmq.username=integration","--spring.rabbitmq.password="+password,
        "--management.otlp.metrics.export.enabled=false","--management.tracing.export.otlp.enabled=false","--logging.level.root=WARN");
    db=context.getBean(JdbcTemplate.class);service=context.getBean(CollectionService.class);rabbit=context.getBean(RabbitTemplate.class);admin=new RabbitAdmin(rabbit.getConnectionFactory());queue="collection-proof-"+UUID.randomUUID();sink();
  }
  @AfterAll static void stop(){if(context!=null)context.close();if(master!=null)master.stop(0);if(broker!=null)broker.stop();if(postgres!=null)postgres.stop();}
  static String token(String sub,List<String> roles,List<String> estates)throws Exception{
    var claims=new JWTClaimsSet.Builder().issuer(issuer).subject(sub).audience("operations-service").issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(3600)))
        .claim("realm_access",Map.of("roles",roles)).claim("estate_ids",estates).build();
    var signed=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);signed.sign(new RSASSASigner(key));return signed.serialize();
  }
  static void login(String sub,List<String> roles,List<String> estates)throws Exception{SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(context.getBean(JwtDecoder.class).decode(token(sub,roles,estates))));}
  void manager()throws Exception{login("manager",List.of("ESTATE_MANAGER"),List.of(estate.toString()));}
  @BeforeEach void fixture()throws Exception{
    masterStatus=200;masterDivision=division;masterZone="UTC";manager();sink();admin.purgeQueue(queue);
    // These are exclusively this class's disposable Testcontainer tables.
    db.execute("TRUNCATE collection_command,collection_adjustment,drc_measurement,collection_revision,latex_collection,outbox_event,tapping_record,tapping_assignment,tapping_schedule CASCADE");
    var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var assignment=UUID.randomUUID();record=UUID.randomUUID();collected=now.minusSeconds(30).toString();
    db.update("INSERT INTO tapping_assignment(id,estate_id,block_id,worker_id,tapping_date,planned_tree_count,status,created_at,updated_at,version,created_by,updated_by) VALUES (?,?,?,'worker',?,10,'COMPLETED',?,?,2,'actor','actor')",assignment,estate,block,now.atZone(ZoneOffset.UTC).toLocalDate(),Timestamp.from(now.minusSeconds(120)),Timestamp.from(now.minusSeconds(60)));
    db.update("INSERT INTO tapping_record(id,assignment_id,worker_id,estate_id,block_id,tapping_date,planned_tree_count,actual_tree_count,started_at,completed_at,created_by,created_at,updated_at) VALUES (?,?,'worker',?,?,?,10,8,?,?,'actor',?,?)",record,assignment,estate,block,now.atZone(ZoneOffset.UTC).toLocalDate(),Timestamp.from(now.minusSeconds(120)),Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.minusSeconds(120)),Timestamp.from(now.minusSeconds(60)));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();masterStatus=200;}
  String createBody(String reference){return json.writeValueAsString(Map.of("tappingRecordId",record,"collectionReference",reference,"collectedAt",collected,"wetLatexKg",new BigDecimal("125.500"),"weightEvidence",Map.of("method","SCALE","sourceReference","SIMULATED-SCALE"),"dataProvenance","SIMULATED"));}
  Receipt create(String reference){return service.mutate("CREATE",null,UUID.randomUUID().toString(),createBody(reference));}
  UUID id(Receipt r){return UUID.fromString(json.readTree(r.body()).path("id").asText());}
  String drc(long version){return json.writeValueAsString(Map.of("version",version,"measurement",Map.of("drcPercent",new BigDecimal("32.40"),"measuredAt",Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS),"method","FIELD_TEST","sourceReference","SIMULATED-DRC","dataProvenance","SIMULATED")));}
  Map<String,Long> counts(){var result=new TreeMap<String,Long>();for(String table:List.of("latex_collection","drc_measurement","collection_revision","collection_adjustment","collection_command","outbox_event"))result.put(table,db.queryForObject("SELECT count(*) FROM "+table,Long.class));return result;}

  @Test void receiptReplaysOriginalBodyAfterLaterVersionsAndMasterOutage(){
    var key=UUID.randomUUID().toString();var original=service.mutate("CREATE",null,key,createBody("LOT"));var id=id(original);
    service.mutate("DRC",id,UUID.randomUUID().toString(),drc(0));
    service.mutate("CORRECT",id,UUID.randomUUID().toString(),"{\"version\":1,\"reason\":\"scale fix\",\"wetLatexKg\":100.000}");
    masterStatus=503;
    var replay=service.mutate("CREATE",null,key,createBody(" lot ").replace("125.500","125.5000"));
    assertEquals(original,replay);assertEquals(2,service.read(id).version());assertEquals(3,counts().get("outbox_event"));
    assertEquals(409,assertThrows(ResponseStatusException.class,()->service.mutate("CREATE",null,key,createBody("OTHER"))).getStatusCode().value());
  }

  @Test void existingCommandsNeedNoLiveMasterAndRevokedScopeCannotReplay()throws Exception{
    var key=UUID.randomUUID().toString();var c=service.mutate("CREATE",null,key,createBody("LOT"));var id=id(c);masterStatus=503;
    service.mutate("DRC",id,UUID.randomUUID().toString(),drc(0));
    service.mutate("VOID",id,UUID.randomUUID().toString(),"{\"version\":1,\"reason\":\"duplicate\"}");
    var before=counts();assertEquals(503,assertThrows(ResponseStatusException.class,()->create("NEW")).getStatusCode().value());assertEquals(before,counts());
    login("manager",List.of("ESTATE_MANAGER"),List.of());
    assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.mutate("CREATE",null,key,createBody("LOT")));
  }

  @Test void invalidExecutionStatesRollbackReservationWithoutChangingTappingRows(){
    for(String state:List.of("PLANNED","IN_PROGRESS","MISSED","CANCELLED")){
      db.update("UPDATE tapping_assignment SET status=?",state);
      var before=counts();var tapping=db.queryForList("SELECT row_to_json(r)::text AS body FROM tapping_record r");
      assertThrows(Rules.Violation.class,()->create("INVALID-"+state));assertEquals(before,counts());assertEquals(tapping,db.queryForList("SELECT row_to_json(r)::text AS body FROM tapping_record r"));
    }
    db.update("UPDATE tapping_assignment SET status='COMPLETED'");
    db.update("UPDATE tapping_record SET actual_tree_count=0");var before=counts();
    assertThrows(Rules.Violation.class,()->create("ZERO"));assertEquals(before,counts());
    db.update("UPDATE tapping_record SET actual_tree_count=8,completed_at=NULL");
    assertThrows(Rules.Violation.class,()->create("ABORTED"));assertEquals(before,counts());
    record=UUID.randomUUID();assertEquals(404,assertThrows(ResponseStatusException.class,()->create("MISSING")).getStatusCode().value());assertEquals(before,counts());
  }

  @Test void canonicalReparentAndTimezoneChangesCannotRewriteSnapshotGroups()throws Exception{
    var id=id(create("SNAPSHOT"));var before=service.read(id);masterDivision=UUID.randomUUID();masterZone="Asia/Colombo";
    assertEquals(before,service.read(id));var date=before.collectionDate();
    var response=http("GET","/summary?from="+date+"&to="+date+"&groupBy=division&dataProvenance=SIMULATED",null,null,200);
    assertTrue(response.body().contains(division.toString()));assertFalse(response.body().contains(masterDivision.toString()));
    var history=http("GET","/"+id+"/history",null,null,200);assertTrue(history.body().contains("UTC"));assertFalse(history.body().contains("Asia/Colombo"));
  }

  @Test void sameAndDifferentKeysRaceWithoutDuplicateEffects()throws Exception{
    var key=UUID.randomUUID().toString();var barrier=new CyclicBarrier(2);
    try(var pool=Executors.newFixedThreadPool(2)){
      Callable<Receipt> job=()->{manager();try{barrier.await(5,TimeUnit.SECONDS);return service.mutate("CREATE",null,key,createBody("RACE"));}finally{SecurityContextHolder.clearContext();}};
      var results=pool.invokeAll(List.of(job,job));assertEquals(results.get(0).get(),results.get(1).get());
    }
    assertEquals(1,counts().get("latex_collection"));assertEquals(1,counts().get("collection_command"));assertEquals(1,counts().get("outbox_event"));
    assertEquals(409,assertThrows(ResponseStatusException.class,()->create("RACE")).getStatusCode().value());
    var mismatchKey=UUID.randomUUID().toString();
    try(var pool=Executors.newFixedThreadPool(2)){
      var jobs=new ArrayList<Callable<Integer>>();
      for(String reference:List.of("BODY-A","BODY-B"))jobs.add(()->{manager();try{barrier.await(5,TimeUnit.SECONDS);return service.mutate("CREATE",null,mismatchKey,createBody(reference)).status();}catch(ResponseStatusException ex){return ex.getStatusCode().value();}finally{SecurityContextHolder.clearContext();}});
      var results=pool.invokeAll(jobs);assertEquals(List.of(201,409),results.stream().map(f->{try{return f.get();}catch(Exception ex){throw new RuntimeException(ex);}}).sorted().toList());
    }
    assertEquals(2,counts().get("latex_collection"));assertEquals(2,counts().get("collection_command"));assertEquals(2,counts().get("outbox_event"));
  }

  @Test void concurrentMeasurementsRollbackLosingReservation()throws Exception{
    var id=id(create("RACE"));var barrier=new CyclicBarrier(2);
    try(var pool=Executors.newFixedThreadPool(2)){
      Callable<Integer> measurement=()->{manager();try{barrier.await(5,TimeUnit.SECONDS);return service.mutate("DRC",id,UUID.randomUUID().toString(),drc(0)).status();}catch(ResponseStatusException ex){return ex.getStatusCode().value();}finally{SecurityContextHolder.clearContext();}};
      var results=pool.invokeAll(List.of(measurement,measurement));var status=results.stream().map(f->{try{return f.get();}catch(Exception ex){throw new RuntimeException(ex);}}).sorted().toList();assertEquals(List.of(200,409),status);
    }
    assertEquals(2,counts().get("collection_command"));assertEquals(1,counts().get("drc_measurement"));assertEquals(2,counts().get("collection_revision"));
  }

  @Test void differentKeysSameReferenceAndCorrectionVersusVoidRace()throws Exception{
    var barrier=new CyclicBarrier(2);
    try(var pool=Executors.newFixedThreadPool(2)){
      Callable<Integer> job=()->{manager();try{barrier.await(5,TimeUnit.SECONDS);return create(" UNIQUE ").status();}catch(ResponseStatusException ex){return ex.getStatusCode().value();}finally{SecurityContextHolder.clearContext();}};
      var results=pool.invokeAll(List.of(job,job));assertEquals(List.of(201,409),results.stream().map(f->{try{return f.get();}catch(Exception ex){throw new RuntimeException(ex);}}).sorted().toList());
    }
    assertEquals(1,counts().get("latex_collection"));assertEquals(1,counts().get("collection_command"));
    var id=db.queryForObject("SELECT id FROM latex_collection",UUID.class);
    try(var pool=Executors.newFixedThreadPool(2)){
      var jobs=List.<Callable<Integer>>of(()->raceMutation(barrier,"CORRECT",id,"{\"version\":0,\"reason\":\"scale\",\"wetLatexKg\":100}"),()->raceMutation(barrier,"VOID",id,"{\"version\":0,\"reason\":\"duplicate\"}"));
      var results=pool.invokeAll(jobs);assertEquals(List.of(200,409),results.stream().map(f->{try{return f.get();}catch(Exception ex){throw new RuntimeException(ex);}}).sorted().toList());
    }
    assertEquals(2,counts().get("collection_command"));assertEquals(2,counts().get("collection_revision"));assertEquals(1,counts().get("collection_adjustment"));assertEquals(2,counts().get("outbox_event"));
  }

  int raceMutation(CyclicBarrier barrier,String command,UUID id,String body)throws Exception{
    manager();try{barrier.await(5,TimeUnit.SECONDS);return service.mutate(command,id,UUID.randomUUID().toString(),body).status();}catch(ResponseStatusException ex){return ex.getStatusCode().value();}finally{SecurityContextHolder.clearContext();}
  }

  @Test void evidenceAndAdjustmentFailuresRollbackAndKeyCanBeRetried(){
    var id=id(create("FAULT"));var named=new NamedParameterJdbcTemplate(db.getDataSource());
    for(String phase:List.of("measurement","adjustment")){
      var store=new CollectionStore(named){
        @Override public void append(DrcMeasurement m,CollectionScope scope){super.append(m,scope);if(phase.equals("measurement"))throw new IllegalStateException("injected after evidence");}
        @Override public void append(CollectionAdjustment a,CollectionScope scope){super.append(a,scope);if(phase.equals("adjustment"))throw new IllegalStateException("injected after adjustment");}
      };
      var testService=new CollectionService(store,context.getBean(CollectionReplayStore.class),context.getBean(CollectionReferenceValidation.class),context.getBean(CollectionEventPort.class),context.getBean(CollectionAccess.class),context.getBean(Access.class),context.getBean(Clock.class),context.getBean(PlatformTransactionManager.class));
      var command=phase.equals("measurement")?"DRC":"CORRECT";
      var body=phase.equals("measurement")?drc(0):"{\"version\":0,\"reason\":\"scale\",\"wetLatexKg\":100}";
      var key=UUID.randomUUID().toString();var before=counts();
      assertThrows(IllegalStateException.class,()->testService.mutate(command,id,key,body));assertEquals(before,counts());
      assertTrue(context.getBean(CollectionReplayStore.class).replay("manager",UUID.fromString(key),CollectionCommands.hash(CollectionCommands.BASE+"/"+id+(command.equals("DRC")?"/drc-measurements":"/corrections"),CollectionCommands.parse(command,body))).isEmpty());
    }
  }

  @Test void dateCorrectionMovesCurrentContributionAndRetainsOldHistory()throws Exception{
    var previous=Instant.now().minusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var oldDate=previous.atZone(ZoneOffset.UTC).toLocalDate();var newDate=LocalDate.now(ZoneOffset.UTC);
    db.update("UPDATE tapping_record SET tapping_date=?,started_at=?,completed_at=? WHERE id=?",oldDate,Timestamp.from(previous.minusSeconds(120)),Timestamp.from(previous.minusSeconds(60)),record);
    db.update("UPDATE tapping_assignment SET tapping_date=? WHERE id=(SELECT assignment_id FROM tapping_record WHERE id=?)",oldDate,record);
    collected=previous.toString();
    var raw=json.readValue(createBody("DATE"),Map.class);raw.put("backdateReason","SIMULATED prior-day observation");
    var created=service.mutate("CREATE",null,UUID.randomUUID().toString(),json.writeValueAsString(raw));var id=id(created);
    service.mutate("CORRECT",id,UUID.randomUUID().toString(),json.writeValueAsString(new LinkedHashMap<String,Object>(){{put("version",0);put("reason","SIMULATED date correction");put("collectedAt",Instant.now().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS));put("backdateReason",null);}}));
    assertEquals(newDate,service.read(id).collectionDate());
    var summary=json.readTree(http("GET","/summary?from="+oldDate+"&to="+oldDate+"&groupBy=date",null,null,200).body());assertEquals(0,summary.path("totalElements").asInt());
    var history=json.readTree(http("GET","/"+id+"/history",null,null,200).body());assertTrue(history.toString().contains(oldDate.toString()));assertTrue(history.toString().contains(newDate.toString()));
    var folder=Path.of("target/collection-date-events");Files.createDirectories(folder);
    for(var event:db.queryForList("SELECT event_type,payload::text AS body FROM outbox_event"))Files.writeString(folder.resolve(event.get("event_type")+".json"),(String)event.get("body"));
  }

  @Test void failureAfterRootRevisionOutboxOrReceiptRollsBackEverything(){
    var named=new NamedParameterJdbcTemplate(db.getDataSource());
    for(String phase:List.of("root","revision","outbox","receipt")){
      var store=new CollectionStore(named){
        @Override public void insert(LatexCollection c,CollectionScope scope){super.insert(c,scope);if(phase.equals("root"))throw new IllegalStateException("injected");}
        @Override public void append(CollectionRevision r,CollectionScope scope){super.append(r,scope);if(phase.equals("revision"))throw new IllegalStateException("injected");}
      };
      var replay=context.getBean(CollectionReplayStore.class);
      var intercepted=(CollectionReplayStore)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{CollectionReplayStore.class},(proxy,method,args)->{
        try{var result=method.invoke(replay,args);if(phase.equals("receipt")&&method.getName().equals("complete"))throw new IllegalStateException("injected");return result;}
        catch(java.lang.reflect.InvocationTargetException ex){throw ex.getCause();}
      });
      CollectionEventPort writer=(command,c,adjustment,correlation)->{context.getBean(CollectionEventPort.class).append(command,c,adjustment,correlation);if(phase.equals("outbox"))throw new IllegalStateException("injected");};
      var testService=new CollectionService(store,intercepted,context.getBean(CollectionReferenceValidation.class),writer,context.getBean(CollectionAccess.class),context.getBean(Access.class),context.getBean(Clock.class),context.getBean(PlatformTransactionManager.class));
      var before=counts();assertThrows(IllegalStateException.class,()->testService.mutate("CREATE",null,UUID.randomUUID().toString(),createBody("FAULT-"+phase)));assertEquals(before,counts());
    }
  }

  @Test void responseSerializationFailureRollsBackAllStages()throws Exception{
    var testService=new CollectionService(context.getBean(CollectionRepository.class),context.getBean(CollectionReplayStore.class),context.getBean(CollectionReferenceValidation.class),context.getBean(CollectionEventPort.class),context.getBean(CollectionAccess.class),context.getBean(Access.class),context.getBean(Clock.class),context.getBean(PlatformTransactionManager.class));
    var failing=org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(failing.writeValueAsString(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("injected serialization failure"));
    var field=CollectionService.class.getDeclaredField("json");field.setAccessible(true);field.set(testService,failing);
    var before=counts();assertThrows(IllegalStateException.class,()->testService.mutate("CREATE",null,UUID.randomUUID().toString(),createBody("SERIALIZE")));assertEquals(before,counts());
  }

  @Test void boundedReservationWaitReturnsConflictAndRolledBackKeyCanRetry()throws Exception{
    var key=UUID.randomUUID();var body=createBody("LOCK");var hash=CollectionCommands.hash(CollectionCommands.BASE,CollectionCommands.parse("CREATE",body));
    var held=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var pool=Executors.newSingleThreadExecutor()){
      var holder=pool.submit(()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(s->{
        context.getBean(CollectionReplayStore.class).reserve("manager",key,hash,CollectionCommands.BASE,Instant.now());held.countDown();
        try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new RuntimeException(ex);}s.setRollbackOnly();
      }));
      assertTrue(held.await(5,TimeUnit.SECONDS));
      try{assertEquals(409,assertThrows(ResponseStatusException.class,()->service.mutate("CREATE",null,key.toString(),body)).getStatusCode().value());}
      finally{release.countDown();}holder.get();
    }
    assertEquals(201,service.mutate("CREATE",null,key.toString(),body).status());assertEquals(1,counts().get("collection_command"));
  }

  @Test void actualBrokerNackAndConfirmTimeoutRetainCommittedEvent()throws Exception{
    create("BROKER-FAULT");var transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    admin.deleteQueue(queue);
    admin.declareQueue(new org.springframework.amqp.core.Queue(queue,true,false,false,Map.of("x-max-length",1,"x-overflow","reject-publish")));
    admin.declareBinding(new Binding(queue,Binding.DestinationType.QUEUE,"agrosphere.domain","agrosphere.LatexCollectionRecorded.v2",Map.of()));
    rabbit.convertAndSend("",queue,"SIMULATED capacity filler");
    try{
      transaction.executeWithoutResult(s->new OutboxDispatcher(db,rabbit,1,20,1,2).dispatch());
      assertEquals("FAILED",db.queryForObject("SELECT delivery_status FROM outbox_event",String.class));
      assertNull(db.queryForObject("SELECT published_at FROM outbox_event",Timestamp.class));
    }finally{admin.deleteQueue(queue);sink();}
    db.update("UPDATE outbox_event SET delivery_status='PENDING',next_attempt_at=now()");
    broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
    try{
      transaction.executeWithoutResult(s->new OutboxDispatcher(db,rabbit,2,20,1,1).dispatch());
      assertEquals("FAILED",db.queryForObject("SELECT delivery_status FROM outbox_event",String.class));
      assertNull(db.queryForObject("SELECT published_at FROM outbox_event",Timestamp.class));
    }finally{broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec();}
    assertEquals(1,counts().get("collection_command"));assertEquals(1,counts().get("outbox_event"));
  }

  @Test void actualV2SnapshotsPublishWithStableIdentityAndNoReplayEvents()throws Exception{
    var createKey=UUID.randomUUID().toString();var c=service.mutate("CREATE",null,createKey,createBody("EVENT"));var id=id(c);
    var drcKey=UUID.randomUUID().toString();var drcBody=drc(0);var measured=service.mutate("DRC",id,drcKey,drcBody);
    var correctionKey=UUID.randomUUID().toString();var correctionBody=json.writeValueAsString(Map.of("version",1,"reason","scale fix","wetLatexKg",new BigDecimal("100.000"),"replacementMeasurement",json.readTree(drc(1)).path("measurement")));
    var corrected=service.mutate("CORRECT",id,correctionKey,correctionBody);
    var voidKey=UUID.randomUUID().toString();var voidBody="{\"version\":2,\"reason\":\"duplicate\"}";var voided=service.mutate("VOID",id,voidKey,voidBody);
    assertEquals(c,service.mutate("CREATE",null,createKey,createBody("EVENT")));assertEquals(measured,service.mutate("DRC",id,drcKey,drcBody));
    assertEquals(corrected,service.mutate("CORRECT",id,correctionKey,correctionBody));assertEquals(voided,service.mutate("VOID",id,voidKey,voidBody));
    assertEquals(4,counts().get("collection_command"));assertEquals(4,counts().get("collection_revision"));
    assertEquals(2,counts().get("drc_measurement"));
    var folder=Path.of("target/collection-events");Files.createDirectories(folder);
    for(var event:db.queryForList("SELECT event_type,payload::text AS body FROM outbox_event"))Files.writeString(folder.resolve(event.get("event_type")+".json"),(String)event.get("body"));
    var relay=new OutboxDispatcher(db,rabbit,3,20,1,5);new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(s->relay.dispatch());
    assertEquals(4,db.queryForObject("SELECT count(*) FROM outbox_event WHERE delivery_status='PUBLISHED'",Integer.class));
    for(int i=0;i<4;i++){var message=rabbit.receive(queue,5000);assertNotNull(message);var event=json.readTree(message.getBody());assertEquals(2,event.path("eventVersion").asInt());assertEquals(event.path("eventId").asText(),message.getMessageProperties().getMessageId());}
    assertNull(rabbit.receive(queue));
  }

  @Test void unroutableV2IsRetainedAndOperatorReplayPreservesId(){
    create("UNROUTABLE");var id=db.queryForObject("SELECT event_id FROM outbox_event",UUID.class);
    var payload=db.queryForObject("SELECT payload::text FROM outbox_event",String.class);
    admin.deleteQueue(queue);
    var relay=new OutboxDispatcher(db,rabbit,1,20,1,5);var transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    transaction.executeWithoutResult(s->relay.dispatch());
    assertEquals("FAILED",db.queryForObject("SELECT delivery_status FROM outbox_event",String.class));
    assertNull(db.queryForObject("SELECT published_at FROM outbox_event",java.sql.Timestamp.class));
    sink();db.update("UPDATE outbox_event SET delivery_status='PENDING',next_attempt_at=now() WHERE event_id=?",id);
    transaction.executeWithoutResult(s->relay.dispatch());
    var message=rabbit.receive(queue,5000);assertNotNull(message);assertEquals(id.toString(),message.getMessageProperties().getMessageId());
    assertEquals(payload,db.queryForObject("SELECT payload::text FROM outbox_event",String.class));assertEquals(1,counts().get("collection_revision"));
  }

  @Test void crashAfterBrokerAckRedeliversSameIdWithNoBusinessMutation(){
    create("CRASH");var id=db.queryForObject("SELECT event_id FROM outbox_event",UUID.class);
    var failing=new JdbcTemplate(db.getDataSource()){
      @Override public int update(String sql,Object...args){if(sql.contains("delivery_status='PUBLISHED'"))throw new org.springframework.dao.DataAccessResourceFailureException("injected after broker ACK");return super.update(sql,args);}
    };
    var transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->transaction.executeWithoutResult(s->new OutboxDispatcher(failing,rabbit,3,20,1,5).dispatch()));
    assertEquals("PENDING",db.queryForObject("SELECT delivery_status FROM outbox_event",String.class));
    transaction.executeWithoutResult(s->new OutboxDispatcher(db,rabbit,3,20,1,5).dispatch());
    var first=rabbit.receive(queue,5000);var second=rabbit.receive(queue,5000);assertNotNull(first);assertNotNull(second);
    assertEquals(id.toString(),first.getMessageProperties().getMessageId());assertEquals(first.getMessageProperties().getMessageId(),second.getMessageProperties().getMessageId());
    assertArrayEquals(first.getBody(),second.getBody());assertEquals(1,counts().get("collection_command"));
  }

  HttpResponse<String> http(String method,String path,String body,String key,String sub,List<String> roles,List<String> estates,int expected)throws Exception{
    int port=((org.springframework.boot.web.server.context.WebServerApplicationContext)context).getWebServer().getPort();
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+(path.startsWith("/v3/")?path:CollectionCommands.BASE+path)))
        .header("Authorization","Bearer "+token(sub,roles,estates)).header("X-Correlation-ID","00000000-0000-0000-0000-000000000099");
    if(key!=null)request.header("Idempotency-Key",key);
    if(body!=null)request.header("Content-Type","application/json");
    var response=HttpClient.newHttpClient().send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(expected,response.statusCode(),response.body());return response;
  }
  HttpResponse<String> http(String method,String path,String body,String key,int expected)throws Exception{return http(method,path,body,key,"manager",List.of("ESTATE_MANAGER"),List.of(estate.toString()),expected);}

  @Test void actualHttpRolesHistoryReceiptAndProblemDetails()throws Exception{
    var key=UUID.randomUUID().toString();var created=http("POST","",createBody("HTTP"),key,"worker",List.of("FIELD_WORKER"),List.of(estate.toString()),201);
    var id=UUID.fromString(json.readTree(created.body()).path("id").asText());
    http("POST","/"+id+"/drc-measurements",drc(0),UUID.randomUUID().toString(),"worker",List.of("FIELD_WORKER"),List.of(estate.toString()),403);
    http("POST","/"+id+"/drc-measurements",drc(0),UUID.randomUUID().toString(),"supervisor",List.of("FIELD_SUPERVISOR"),List.of(estate.toString()),200);
    http("POST","/"+id+"/corrections","{\"version\":1,\"reason\":\"fix\",\"wetLatexKg\":100}",UUID.randomUUID().toString(),"supervisor",List.of("FIELD_SUPERVISOR"),List.of(estate.toString()),403);
    http("POST","/"+id+"/corrections","{\"version\":1,\"reason\":\"fix\",\"wetLatexKg\":100}",UUID.randomUUID().toString(),200);
    var replay=http("POST","",createBody("HTTP"),key,"worker",List.of("FIELD_WORKER"),List.of(estate.toString()),201);
    assertEquals(created.body(),replay.body());assertEquals(created.headers().firstValue("Location"),replay.headers().firstValue("Location"));
    var history=http("GET","/"+id+"/history",null,null,200);assertEquals(3,json.readTree(history.body()).path("totalElements").asInt());
    var measurements=http("GET","/"+id+"/drc-measurements",null,null,200);assertEquals(1,json.readTree(measurements.body()).path("totalElements").asInt());
    http("GET","/"+id+"/history",null,null,"other-worker",List.of("FIELD_WORKER"),List.of(estate.toString()),403);
    var error=http("POST","",createBody("NO-KEY"),null,400);assertTrue(error.body().contains("correlationId"));assertFalse(error.body().contains("SQLException"));
    var folder=Path.of("target/collection-api");Files.createDirectories(folder);Files.writeString(folder.resolve("history.json"),history.body());Files.writeString(folder.resolve("measurements.json"),measurements.body());
  }

  @Test void everySignedRoleIsEnforcedAcrossAllNineHttpOperations()throws Exception{
    var date=LocalDate.now(ZoneOffset.UTC);
    for(String role:List.of("PLATFORM_ADMIN","PLANTATION_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER","FIELD_SUPERVISOR","FIELD_WORKER","AUDITOR","VIEWER")){
      var roles=List.of(role);var estates=List.of(estate.toString());var id=id(create("READ-"+role));
      for(String path:List.of("","/"+id,"/"+id+"/history","/"+id+"/drc-measurements","/summary?from="+date+"&to="+date))http("GET",path,null,null,"worker",roles,estates,200);
      boolean manager=Set.of("PLATFORM_ADMIN","PLANTATION_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER").contains(role);
      boolean supervisor=manager||role.equals("FIELD_SUPERVISOR");
      http("POST","",createBody("CREATE-"+role),UUID.randomUUID().toString(),"worker",roles,estates,supervisor||role.equals("FIELD_WORKER")?201:403);
      http("POST","/"+id+"/drc-measurements",drc(0),UUID.randomUUID().toString(),"worker",roles,estates,supervisor?200:403);
      long version=supervisor?1:0;
      http("POST","/"+id+"/corrections","{\"version\":"+version+",\"reason\":\"scale\",\"wetLatexKg\":100}",UUID.randomUUID().toString(),"worker",roles,estates,manager?200:403);
      http("POST","/"+id+"/void","{\"version\":"+(manager?version+1:version)+",\"reason\":\"duplicate\"}",UUID.randomUUID().toString(),"worker",roles,estates,manager?200:403);
    }
  }

  @Test void summaryUsesRoundedLotsExactWeightedRatioPendingCoverageAndProvenance()throws Exception{
    for(String reference:List.of("TINY-A","TINY-B")){
      var c=service.mutate("CREATE",null,UUID.randomUUID().toString(),createBody(reference).replace("125.500","0.001"));
      service.mutate("DRC",id(c),UUID.randomUUID().toString(),drc(0).replace("32.40","50.00"));
    }
    service.mutate("CREATE",null,UUID.randomUUID().toString(),createBody("PENDING").replace("125.500","10.000"));
    var zero=service.mutate("CREATE",null,UUID.randomUUID().toString(),createBody("ZERO").replace("125.500","1.000"));
    service.mutate("DRC",id(zero),UUID.randomUUID().toString(),drc(0).replace("32.40","0.00"));
    var voided=create("VOID");service.mutate("VOID",id(voided),UUID.randomUUID().toString(),"{\"version\":0,\"reason\":\"duplicate\"}");
    service.mutate("CREATE",null,UUID.randomUUID().toString(),createBody("LOGICAL-REAL").replace("SIMULATED\"","REAL\""));
    var date=LocalDate.now(ZoneOffset.UTC);var path="/summary?from="+date+"&to="+date+"&dataProvenance=SIMULATED";
    var response=http("GET",path,null,null,200);var row=json.readTree(response.body()).path("content").get(0);
    assertEquals(0,new BigDecimal("0.002").compareTo(row.path("knownDryRubberKg").decimalValue()));
    assertEquals(0,new BigDecimal("11.002").compareTo(row.path("totalWetLatexKg").decimalValue()));
    assertEquals(0,new BigDecimal("10.000").compareTo(row.path("pendingWetLatexKg").decimalValue()));
    assertEquals(0,new BigDecimal("0.10").compareTo(row.path("weightedDrcPercent").decimalValue()));
    assertEquals(1,row.path("pendingDrcCount").asInt());assertEquals(1,row.path("voidedCollectionCount").asInt());assertFalse(row.path("isComplete").asBoolean());
    for(String group:List.of("estate","division","block","worker","date"))assertEquals(1,json.readTree(http("GET",path+"&groupBy="+group,null,null,200).body()).path("totalElements").asInt());
    assertEquals(2,json.readTree(http("GET","/summary?from="+date+"&to="+date,null,null,200).body()).path("totalElements").asInt());
    Files.createDirectories(Path.of("target/collection-api"));Files.writeString(Path.of("target/collection-api/summary.json"),response.body());
  }

  @Test void boundedQueriesAndScopeAreEnforcedAtHttpBoundary()throws Exception{
    create("QUERY");var date=LocalDate.now(ZoneOffset.UTC);
    http("GET","",null,null,200);http("GET","?from="+date,null,null,400);http("GET","/summary",null,null,400);
    http("GET","/summary?from="+date+"&to="+date.plusDays(367),null,null,400);
    http("GET","?unknown=value",null,null,400);http("GET","?page=0&page=1",null,null,400);
    http("GET","/summary?from="+date+"&to="+date+"&status=VOIDED",null,null,400);
    http("GET","?workerId=other",null,null,"worker",List.of("FIELD_WORKER"),List.of(estate.toString()),403);
    http("GET","?estateId="+UUID.randomUUID(),null,null,403);
    var empty=http("GET","",null,null,"another",List.of("FIELD_WORKER"),List.of(estate.toString()),200);assertEquals(0,json.readTree(empty.body()).path("totalElements").asInt());
  }

  @Test void sqlScopePrecedesSecondPageCountsAndSummaryAggregation()throws Exception{
    create("OWN-A");create("OWN-B");var ownRecord=record;
    var assignment=UUID.randomUUID();record=UUID.randomUUID();
    db.update("INSERT INTO tapping_assignment(id,estate_id,block_id,worker_id,tapping_date,planned_tree_count,status,created_at,updated_at,version,created_by,updated_by) SELECT ?,estate_id,block_id,'other',tapping_date,planned_tree_count,status,created_at,updated_at,version,created_by,updated_by FROM tapping_assignment WHERE id=(SELECT assignment_id FROM tapping_record WHERE id=?)",assignment,ownRecord);
    db.update("INSERT INTO tapping_record(id,assignment_id,worker_id,estate_id,block_id,tapping_date,planned_tree_count,actual_tree_count,started_at,completed_at,created_by,created_at,updated_at) SELECT ?,?,'other',estate_id,block_id,tapping_date,planned_tree_count,actual_tree_count,started_at,completed_at,created_by,created_at,updated_at FROM tapping_record WHERE id=?",record,assignment,ownRecord);
    var foreign=id(service.mutate("CREATE",null,UUID.randomUUID().toString(),createBody("OTHER").replace("125.500","1000.000")));
    var workerRoles=List.of("FIELD_WORKER");var estates=List.of(estate.toString());
    var page=http("GET","?page=1&size=1",null,null,"worker",workerRoles,estates,200);var value=json.readTree(page.body());
    assertEquals(2,value.path("totalElements").asInt());assertEquals(1,value.path("content").size());assertFalse(page.body().contains(foreign.toString()));
    var date=LocalDate.now(ZoneOffset.UTC);var summary=json.readTree(http("GET","/summary?from="+date+"&to="+date+"&dataProvenance=SIMULATED&groupBy=worker",null,null,"worker",workerRoles,estates,200).body());
    assertEquals(1,summary.path("totalElements").asInt());assertEquals(0,new BigDecimal("251.000").compareTo(summary.path("content").get(0).path("totalWetLatexKg").decimalValue()));
    for(String path:List.of("/"+foreign,"/"+foreign+"/history","/"+foreign+"/drc-measurements"))http("GET",path,null,null,"worker",workerRoles,estates,403);
    http("GET","/summary?from="+date+"&to="+date.plusDays(366),null,null,200);
    Files.createDirectories(Path.of("target/collection-api"));Files.writeString(Path.of("target/collection-api/list.json"),page.body());
  }

  @Test void httpErrorsAreCorrelatedSanitizedAndRejectedCommandsLeaveNoRows()throws Exception{
    var before=counts();
    for(int code:List.of(404,503)){
      masterStatus=code;var response=http("POST","",createBody("DEPENDENCY-"+code),UUID.randomUUID().toString(),code==404?422:503);
      assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
      assertTrue(response.body().contains("00000000-0000-0000-0000-000000000099"));assertFalse(response.body().contains("SQLException"));assertEquals(before,counts());
    }
    masterStatus=200;
    http("GET","/"+UUID.randomUUID(),null,null,404);http("GET","/bad-uuid",null,null,400);
    http("POST","",createBody("FORGED").replaceFirst("^\\{","{\"workerId\":\"forged\","),UUID.randomUUID().toString(),400);
    var created=http("POST","",createBody("CONFLICT"),UUID.randomUUID().toString(),201);
    http("POST","",createBody(" conflict "),UUID.randomUUID().toString(),409);
    Files.createDirectories(Path.of("target/collection-api"));Files.writeString(Path.of("target/collection-api/created.json"),created.body());
  }

  @Test void runtimeDocumentsAllActualOperationsAndPinnedCollectionSchemas()throws Exception{
    var response=http("GET","/v3/api-docs",null,null,200);var api=json.readTree(response.body());
    int operations=0;for(var path:api.path("paths")){operations+=path.size();}assertEquals(27,operations);
    Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/openapi-runtime.json"),response.body());
  }
}
