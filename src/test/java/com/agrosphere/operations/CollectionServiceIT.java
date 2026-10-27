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
        Map.of("id",block,"estateId",estate,"divisionId",division,"version",2,"cropType","RUBBER","status","ACTIVE"):
        Map.of("id",estate,"timezone","UTC","version",1,"status","ACTIVE");
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
    masterStatus=200;manager();sink();admin.purgeQueue(queue);
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

  @Test void sameAndDifferentKeysRaceWithoutDuplicateEffects()throws Exception{
    var key=UUID.randomUUID().toString();var barrier=new CyclicBarrier(2);
    try(var pool=Executors.newFixedThreadPool(2)){
      Callable<Receipt> job=()->{manager();try{barrier.await(5,TimeUnit.SECONDS);return service.mutate("CREATE",null,key,createBody("RACE"));}finally{SecurityContextHolder.clearContext();}};
      var results=pool.invokeAll(List.of(job,job));assertEquals(results.get(0).get(),results.get(1).get());
    }
    assertEquals(1,counts().get("latex_collection"));assertEquals(1,counts().get("collection_command"));assertEquals(1,counts().get("outbox_event"));
    assertEquals(409,assertThrows(ResponseStatusException.class,()->create("RACE")).getStatusCode().value());
  }

  @Test void staleMeasurementAndVoidWritersRollbackTheirReservations()throws Exception{
    var id=id(create("RACE"));var barrier=new CyclicBarrier(2);
    try(var pool=Executors.newFixedThreadPool(2)){
      Callable<Integer> measurement=()->{manager();try{barrier.await(5,TimeUnit.SECONDS);return service.mutate("DRC",id,UUID.randomUUID().toString(),drc(0)).status();}catch(ResponseStatusException ex){return ex.getStatusCode().value();}finally{SecurityContextHolder.clearContext();}};
      var results=pool.invokeAll(List.of(measurement,measurement));var status=results.stream().map(f->{try{return f.get();}catch(Exception ex){throw new RuntimeException(ex);}}).sorted().toList();assertEquals(List.of(200,409),status);
    }
    assertEquals(2,counts().get("collection_command"));assertEquals(1,counts().get("drc_measurement"));assertEquals(2,counts().get("collection_revision"));
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

  @Test void actualV2SnapshotsPublishWithStableIdentityAndNoReplayEvents()throws Exception{
    var c=create("EVENT");var id=id(c);service.mutate("DRC",id,UUID.randomUUID().toString(),drc(0));
    service.mutate("CORRECT",id,UUID.randomUUID().toString(),"{\"version\":1,\"reason\":\"scale fix\",\"wetLatexKg\":100}");
    service.mutate("VOID",id,UUID.randomUUID().toString(),"{\"version\":2,\"reason\":\"duplicate\"}");
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

  @Test void runtimeDocumentsAllActualOperationsAndPinnedCollectionSchemas()throws Exception{
    var response=http("GET","/v3/api-docs",null,null,200);var api=json.readTree(response.body());
    int operations=0;for(var path:api.path("paths")){operations+=path.size();}assertEquals(27,operations);
    Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/openapi-runtime.json"),response.body());
  }
}
