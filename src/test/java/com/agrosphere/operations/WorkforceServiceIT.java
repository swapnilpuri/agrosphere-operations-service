package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.agrosphere.operations.infrastructure.messaging.OutboxDispatcher;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

/** Real signed JWT + HTTP + restricted runtime login + PostgreSQL, all disposable. */
class WorkforceServiceIT {
  static PostgreSQLContainer postgres;static GenericContainer<?> broker;static HttpServer master;
  static ConfigurableApplicationContext context;static RSAKey key;static String issuer;static ExecutorService masterThreads;
  static final ObjectMapper json=new ObjectMapper();static JdbcTemplate db;static int port;
  static final UUID estate=UUID.randomUUID(),foreign=UUID.randomUUID(),division=UUID.randomUUID();
  static volatile int masterStatus=200;static volatile String shape="valid",zone="Africa/Libreville";
  static final AtomicInteger redirected=new AtomicInteger();
  final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  String manager;LocalDate today;int serial;
  @BeforeAll static void start()throws Exception {
    postgres=new PostgreSQLContainer("postgres:17-alpine").withPassword(UUID.randomUUID().toString());postgres.start();
    CollectionTestSupport.provisionNoLoginRuntimeRole(postgres);
    String runtime="wa_runtime_"+UUID.randomUUID().toString().replace("-","");String runtimePassword=UUID.randomUUID().toString();
    try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var statement=connection.createStatement()){
      statement.execute("CREATE ROLE "+runtime+" LOGIN PASSWORD '"+runtimePassword+"'");statement.execute("GRANT agrosphere_operations_app TO "+runtime);
    }
    String rabbitPassword=UUID.randomUUID().toString();broker=new GenericContainer<>("rabbitmq:4.3.6-management").withExposedPorts(5672)
        .withEnv("RABBITMQ_DEFAULT_USER","wa_test").withEnv("RABBITMQ_DEFAULT_PASS",rabbitPassword);broker.start();
    key=new RSAKeyGenerator(2048).keyID("SIMULATED-workforce-http").generate();master=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    issuer="http://127.0.0.1:"+master.getAddress().getPort();masterThreads=Executors.newCachedThreadPool();master.setExecutor(masterThreads);
    master.createContext("/jwks",e->{byte[] bytes=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
    master.createContext("/redirect-target",e->{redirected.incrementAndGet();e.sendResponseHeaders(200,0);e.close();});
    master.createContext("/api/v1",e->{
      try{
        String bearer=e.getRequestHeaders().getFirst("Authorization");
        boolean trusted=bearer!=null&&bearer.startsWith("Bearer ")&&SignedJWT.parse(bearer.substring(7)).verify(new RSASSAVerifier(key.toPublicJWK()));
        if(!trusted){e.sendResponseHeaders(403,0);return;}
        if(shape.equals("slow"))Thread.sleep(2000);
        if(shape.equals("redirect")){e.getResponseHeaders().set("Location",issuer+"/redirect-target");e.sendResponseHeaders(302,-1);return;}
        String path=e.getRequestURI().getPath();UUID requested=UUID.fromString(path.substring(path.lastIndexOf('/')+1));
        Object payload=path.contains("/divisions/")?Map.of("id",requested,"estateId",estate,"status","ACTIVE"):
            Map.of("id",requested,"status","ACTIVE","timezone",zone,"version",1);
        if(shape.equals("malformed"))payload=Map.of("id",42,"status","ACTIVE","timezone",zone);
        if(shape.equals("wrong-id"))payload=Map.of("id",UUID.randomUUID(),"status","ACTIVE","timezone",zone);
        if(shape.equals("inactive"))payload=Map.of("id",requested,"status","INACTIVE","timezone",zone);
        byte[] bytes=shape.equals("oversize")?" ".repeat(70000).getBytes(StandardCharsets.UTF_8):json.writeValueAsBytes(payload);
        e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(masterStatus,bytes.length);e.getResponseBody().write(bytes);
      }catch(Exception ignored){/* Fault/timeout fixtures deliberately cancel their response. */}finally{e.close();}
    });master.start();
    context=SpringApplication.run(Application.class,"--server.port=0","--spring.datasource.url="+postgres.getJdbcUrl(),
        "--spring.datasource.username="+runtime,"--spring.datasource.password="+runtimePassword,
        "--spring.flyway.user="+postgres.getUsername(),"--spring.flyway.password="+postgres.getPassword(),
        "--spring.security.oauth2.resourceserver.jwt.issuer-uri="+issuer,"--OIDC_JWK_SET_URI="+issuer+"/jwks",
        "--operations.estate-url="+issuer,"--operations.estate-timeout-seconds=1","--operations.outbox.enabled=false",
        "--spring.rabbitmq.host="+broker.getHost(),"--spring.rabbitmq.port="+broker.getMappedPort(5672),"--spring.rabbitmq.username=wa_test","--spring.rabbitmq.password="+rabbitPassword,
        "--management.otlp.metrics.export.enabled=false","--management.tracing.export.otlp.enabled=false","--logging.level.root=WARN");
    port=context.getEnvironment().getProperty("local.server.port",Integer.class);
    db=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
  }
  @AfterAll static void stop(){if(context!=null)context.close();if(master!=null)master.stop(0);if(masterThreads!=null)masterThreads.shutdownNow();if(broker!=null)broker.stop();if(postgres!=null)postgres.stop();}
  static String token(String sub,List<String> roles,List<String> estates,String audience,Instant expires)throws Exception{
    var claims=new JWTClaimsSet.Builder().issuer(issuer).subject(sub).audience(List.of(audience,"estate-service"))
        .issueTime(java.util.Date.from(Instant.now().minusSeconds(2))).expirationTime(java.util.Date.from(expires))
        .claim("realm_access",Map.of("roles",roles)).claim("estate_ids",estates).build();
    var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(key));return jwt.serialize();
  }
  String token(String sub,String role,List<String> estates)throws Exception{return token(sub,List.of(role),estates,"operations-service",Instant.now().plusSeconds(3600));}
  @BeforeEach void fixture()throws Exception{
    shape="valid";zone="Africa/Libreville";masterStatus=200;redirected.set(0);today=LocalDate.now(ZoneId.of(zone));
    // Exclusively this class's disposable Testcontainer; no existing stack connection is used.
    db.execute("TRUNCATE workforce_command,worker_revision,worker_active_period,attendance_revision,attendance_day,worker_registration,outbox_event CASCADE");
    manager=token("SIMULATED-manager","ESTATE_MANAGER",List.of(estate.toString(),foreign.toString()));
  }
  HttpResponse<String> request(String token,String method,String path,Object body,String key,int expected)throws Exception{
    var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(12));
    if(token!=null)builder.header("Authorization","Bearer "+token);if(key!=null)builder.header("Idempotency-Key",key);
    builder.header("X-Correlation-ID","00000000-0000-0000-0000-000000000099");
    if(body!=null)builder.header("Content-Type","application/json");
    var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body instanceof String s?s:json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(expected,response.statusCode(),method+" "+path+" -> "+response.body());return response;
  }
  HttpResponse<String> write(String token,String method,String path,Object body,int status)throws Exception{return request(token,method,path,body,UUID.randomUUID().toString(),status);}
  String worker(String token,UUID target)throws Exception{
    var body=new HashMap<String,Object>(Map.of("estateId",target,"workerCode","SIMULATED-"+(++serial),"displayName","SIMULATED Worker "+serial,
        "activeFrom",today.minusDays(5).toString(),"dataProvenance","SIMULATED"));if(target.equals(estate))body.put("divisionId",division);
    return json.readTree(write(token,"POST","/api/v1/workers",body,201).body()).path("id").asText();
  }
  String worker()throws Exception{return worker(manager,estate);}
  String day(String worker)throws Exception{return json.readTree(write(manager,"POST","/api/v1/attendance/days",Map.of("workerRegistrationId",worker,"attendanceDate",today.toString(),"status","PRESENT"),201).body()).path("id").asText();}
  String roster(UUID target){return "/api/v1/attendance/roster?estateId="+target+"&date="+today+"&provenance=SIMULATED";}
  long count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}
  String bind(RabbitAdmin admin){
    String queue="wa-proof-"+UUID.randomUUID();admin.declareExchange(new TopicExchange("agrosphere.domain",true,false));admin.declareQueue(new org.springframework.amqp.core.Queue(queue,true));
    for(String name:List.of("WorkerRegistrationCreated","WorkerRegistrationChanged","WorkerAttendanceRecorded","WorkerAttendanceChanged"))
      admin.declareBinding(new Binding(queue,Binding.DestinationType.QUEUE,"agrosphere.domain","agrosphere."+name+".v1",Map.of()));
    return queue;
  }
  void dispatch(boolean failAfterAck){
    var relay=new OutboxDispatcher(context.getBean(JdbcTemplate.class),context.getBean(RabbitTemplate.class),3,100,1,3);
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(s->{relay.dispatch();if(failAfterAck)throw new IllegalStateException("SIMULATED ACK/commit crash");});
  }

  @Test void lifecycleReceiptsSnapshotsAndAllFifteenOperationsWorkOverHttp()throws Exception{
    var w=worker();var d=day(w);
    assertFalse(request(manager,"GET","/api/v1/workers/"+w,null,null,200).body().contains("createdBy"));
    var changed=json.readTree(write(manager,"PATCH","/api/v1/workers/"+w,Map.of("version",0,"displayName","SIMULATED Revised","reason","SIMULATED profile"),200).body());
    assertEquals(1,changed.path("version").asInt());
    write(manager,"POST","/api/v1/attendance/days/"+d+"/corrections",Map.of("version",0,"status","EXCUSED","reason","SIMULATED correction"),200);
    masterStatus=503;
    write(manager,"POST","/api/v1/attendance/days/"+d+"/void",Map.of("version",1,"reason","SIMULATED mistaken"),200);
    masterStatus=200;
    var restored=json.readTree(write(manager,"POST","/api/v1/attendance/days/"+d+"/re-record",Map.of("version",2,"status","ABSENT","reason","SIMULATED restore"),200).body());
    assertEquals(d,restored.path("id").asText());assertEquals(1,restored.path("workerVersionSnapshot").asInt());
    var history=request(manager,"GET","/api/v1/attendance/days/"+d+"/history",null,null,200);assertEquals(4,json.readTree(history.body()).path("totalElements").asInt());
    var workerHistory=request(manager,"GET","/api/v1/workers/"+w+"/history",null,null,200);assertEquals(2,json.readTree(workerHistory.body()).path("totalElements").asInt());
    request(manager,"GET","/api/v1/workers?estateId="+estate,null,null,200);
    request(manager,"GET","/api/v1/attendance/days?estateId="+estate+"&from="+today+"&to="+today,null,null,200);
    request(manager,"GET","/api/v1/attendance/days/"+d,null,null,200);request(manager,"GET",roster(estate),null,null,200);
    var periodWorker=worker();var period=json.readTree(request(manager,"GET","/api/v1/workers/"+periodWorker,null,null,200).body()).path("activePeriods").get(0).path("id").asText();
    write(manager,"POST","/api/v1/workers/"+periodWorker+"/close-period",Map.of("version",0,"activePeriodId",period,"activeTo",today.toString(),"reason","SIMULATED closure"),200);
    write(manager,"POST","/api/v1/workers/"+periodWorker+"/open-period",Map.of("version",1,"activeFrom",today.toString(),"reason","SIMULATED reopen"),200);
    var folder=Path.of("target/workforce-api");Files.createDirectories(folder);Files.writeString(folder.resolve("worker.json"),changed.toString());
    Files.writeString(folder.resolve("attendance.json"),restored.toString());Files.writeString(folder.resolve("attendance-history.json"),history.body());
    Files.writeString(folder.resolve("worker-history.json"),workerHistory.body());Files.writeString(folder.resolve("roster.json"),request(manager,"GET",roster(estate),null,null,200).body());
  }
  @Test void roleMatrixSignedJwtAndMixedRolesDoNotBroadenPersonalDataAccess()throws Exception{
    for(String role:List.of("PLATFORM_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER","FIELD_SUPERVISOR","AUDITOR","VIEWER","FIELD_WORKER","PLANTATION_ADMIN","AGRONOMIST")){
      var t=token("SIMULATED-"+role,role,role.equals("PLATFORM_ADMIN")?List.of():List.of(estate.toString()));
      boolean managerRole=Set.of("PLATFORM_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER").contains(role),read=managerRole||Set.of("FIELD_SUPERVISOR","AUDITOR").contains(role);
      var w=worker();var d=day(w);
      request(t,"GET","/api/v1/workers/"+w,null,null,read?200:403);request(t,"GET",roster(estate),null,null,read?200:403);
      request(t,"GET","/api/v1/workers/"+w+"/history",null,null,managerRole||role.equals("AUDITOR")?200:403);
      write(t,"PATCH","/api/v1/workers/"+w,Map.of("version",0,"displayName","SIMULATED "+role,"reason","SIMULATED matrix"),managerRole?200:403);
      var unmarked=worker();write(t,"POST","/api/v1/attendance/days",Map.of("workerRegistrationId",unmarked,"attendanceDate",today.toString(),"status","PRESENT"),managerRole||role.equals("FIELD_SUPERVISOR")?201:403);
      write(t,"POST","/api/v1/attendance/days/"+d+"/corrections",Map.of("version",0,"status","EXCUSED","reason","SIMULATED matrix"),managerRole?200:403);
      write(t,"POST","/api/v1/attendance/days/"+d+"/void",Map.of("version",managerRole?1:0,"reason","SIMULATED matrix"),managerRole?200:403);
    }
    var workerViewer=token("SIMULATED-mixed",List.of("FIELD_WORKER","VIEWER"),List.of(estate.toString()),"operations-service",Instant.now().plusSeconds(3600));
    request(workerViewer,"GET",roster(estate),null,null,403);
    var scoped=token("SIMULATED-mixed",List.of("ESTATE_MANAGER","PLANTATION_ADMIN"),List.of(estate.toString()),"operations-service",Instant.now().plusSeconds(3600));
    request(scoped,"GET",roster(foreign),null,null,403);
  }
  @Test void scopedQueriesCountsHistoryAndCoverageStayCorrectBeforePagination()throws Exception{
    var a=worker();var b=worker();worker();var day=day(a);day(b);worker(manager,foreign);
    var supervisor=token("SIMULATED-supervisor","FIELD_SUPERVISOR",List.of(estate.toString()));
    var response=request(supervisor,"GET",roster(estate)+"&page=1&size=1",null,null,200);var value=json.readTree(response.body());
    assertEquals(1,value.path("content").size());assertEquals(3,value.path("eligibleCount").asInt());assertEquals(2,value.path("markedCount").asInt());assertEquals(1,value.path("unmarkedCount").asInt());
    write(manager,"POST","/api/v1/attendance/days/"+day+"/void",Map.of("version",0,"reason","SIMULATED void"),200);
    value=json.readTree(request(supervisor,"GET",roster(estate),null,null,200).body());assertEquals(1,value.path("voidedCount").asInt());assertEquals(2,value.path("unmarkedCount").asInt());
    request(supervisor,"GET","/api/v1/attendance/days/"+day+"/history",null,null,403);
    request(supervisor,"GET",roster(foreign),null,null,403);
    for(String bad:List.of("&unexpected=1","&page=-1","&size=101","&page=2147483647&size=100","&estateId="+estate))request(manager,"GET",roster(estate)+bad,null,null,400);
    request(manager,"GET","/api/v1/attendance/days?estateId="+estate+"&from="+today,null,null,400);
    request(manager,"GET","/api/v1/workers/"+a+"?other=1",null,null,400);
    Files.createDirectories(Path.of("target/workforce-api"));Files.writeString(Path.of("target/workforce-api/coverage.json"),value.toString());
  }
  @Test void replayReauthorizesBeforeReceiptAndNewKeysCannotDuplicateIdentity()throws Exception{
    var body=Map.of("estateId",estate,"workerCode","SIMULATED-REPLAY","displayName","SIMULATED Worker","activeFrom",today.toString(),"dataProvenance","SIMULATED");var key=UUID.randomUUID().toString();
    var created=request(manager,"POST","/api/v1/workers",body,key,201);var id=json.readTree(created.body()).path("id").asText();
    write(manager,"PATCH","/api/v1/workers/"+id,Map.of("version",0,"displayName","SIMULATED Revised","reason","SIMULATED edit"),200);
    masterStatus=503;assertEquals(created.body(),request(manager,"POST","/api/v1/workers",body,key,201).body());masterStatus=200;
    var revoked=token("SIMULATED-manager","ESTATE_MANAGER",List.of());request(revoked,"POST","/api/v1/workers",body,key,403);
    var downgraded=token("SIMULATED-manager","VIEWER",List.of(estate.toString()));request(downgraded,"POST","/api/v1/workers",body,key,403);
    write(manager,"POST","/api/v1/workers",body,409);assertEquals(2,count("outbox_event"));
  }
  @Test void badAuthenticationForgeryFutureAndRealInputsFailWithoutPartialWrites()throws Exception{
    request(null,"GET",roster(estate),null,null,401);
    request(token("SIMULATED-user",List.of("ESTATE_MANAGER"),List.of(estate.toString()),"wrong-audience",Instant.now().plusSeconds(3600)),"GET",roster(estate),null,null,401);
    request(token("SIMULATED-user",List.of("ESTATE_MANAGER"),List.of(estate.toString()),"operations-service",Instant.now().minusSeconds(120)),"GET",roster(estate),null,null,401);
    var body=new HashMap<String,Object>(Map.of("estateId",estate,"workerCode","SIMULATED-INVALID","displayName","SIMULATED","activeFrom",today.toString(),"dataProvenance","REAL"));
    write(manager,"POST","/api/v1/workers",body,403);body.put("dataProvenance","SIMULATED");body.put("createdBy","forged");write(manager,"POST","/api/v1/workers",body,400);
    body.remove("createdBy");body.put("activeFrom",today.plusDays(1).toString());write(manager,"POST","/api/v1/workers",body,400);
    body.put("activeFrom",today.toString());request(manager,"POST","/api/v1/workers",body,null,400);
    write(manager,"POST","/api/v1/workers","{\"estateId\":\""+estate+"\",\"estateId\":\""+foreign+"\"}",400);
    assertEquals(0,count("worker_registration"));assertEquals(0,count("workforce_command"));assertEquals(0,count("outbox_event"));
  }
  @Test void masterFailuresAreBoundedAndFailClosedWithNoFallbackOrPartialReceipt()throws Exception{
    var body=Map.of("estateId",estate,"workerCode","SIMULATED-REFERENCE","displayName","SIMULATED","activeFrom",today.toString(),"dataProvenance","SIMULATED");
    for(int status:List.of(403,404,503)){masterStatus=status;write(manager,"POST","/api/v1/workers",body,status==404?422:status);}
    masterStatus=200;
    for(String value:List.of("malformed","wrong-id","inactive","oversize","redirect","slow")){shape=value;write(manager,"POST","/api/v1/workers",body,Set.of("wrong-id","inactive").contains(value)?422:503);}
    shape="valid";zone="Not/A_Zone";write(manager,"POST","/api/v1/workers",body,503);zone="Africa/Libreville";
    assertEquals(0,redirected.get());assertEquals(0,count("worker_registration"));assertEquals(0,count("workforce_command"));
  }
  @Test void runtimeOpenApiAndRealOutboxSnapshotsAreExportedForIndependentValidation()throws Exception{
    var w=worker();var d=day(w);write(manager,"PATCH","/api/v1/workers/"+w,Map.of("version",0,"displayName","SIMULATED Changed","reason","SIMULATED profile"),200);
    write(manager,"POST","/api/v1/attendance/days/"+d+"/void",Map.of("version",0,"reason","SIMULATED mistake"),200);
    var response=request(manager,"GET","/v3/api-docs",null,null,200);var api=json.readTree(response.body());int count=0;for(var path:api.path("paths"))count+=path.size();assertEquals(42,count);
    Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/openapi-runtime.json"),response.body());
    var folder=Path.of("target/workforce-events");Files.createDirectories(folder);
    for(var row:db.queryForList("SELECT event_id,payload::text AS body FROM outbox_event")){
      String event=(String)row.get("body");for(String privateKey:List.of("displayName","workerCode","reason","recordedBy","createdBy"))assertFalse(event.contains("\""+privateKey+"\""));
      Files.writeString(folder.resolve(row.get("event_id")+".json"),event);
    }
    assertEquals(4,count("outbox_event"));
    var rabbit=context.getBean(RabbitTemplate.class);var admin=new RabbitAdmin(rabbit.getConnectionFactory());String queue=bind(admin);
    try{
      dispatch(false);var seen=new HashSet<String>();
      for(int i=0;i<4;i++){
        var message=rabbit.receive(queue,5000);assertNotNull(message);var envelope=json.readTree(message.getBody());seen.add(envelope.path("eventType").asText());
        assertEquals(envelope.path("eventId").asText(),message.getMessageProperties().getMessageId());
        assertEquals("agrosphere."+envelope.path("eventType").asText()+".v1",message.getMessageProperties().getReceivedRoutingKey());
        assertEquals(MessageDeliveryMode.PERSISTENT,message.getMessageProperties().getReceivedDeliveryMode());
      }
      assertEquals(4,seen.size());assertEquals(4,db.queryForObject("SELECT count(*) FROM outbox_event WHERE delivery_status='PUBLISHED'",Integer.class));
    }finally{admin.deleteQueue(queue);}
  }
  @Test void mandatoryReturnAndAckCrashRetainStableEventIdentity()throws Exception{
    worker();String event=db.queryForObject("SELECT event_id::text FROM outbox_event",String.class);
    var rabbit=context.getBean(RabbitTemplate.class);var admin=new RabbitAdmin(rabbit.getConnectionFactory());
    admin.declareExchange(new TopicExchange("agrosphere.domain",true,false));dispatch(false);
    assertEquals("PENDING",db.queryForObject("SELECT delivery_status FROM outbox_event",String.class));
    String queue=bind(admin);
    try{
      db.update("UPDATE outbox_event SET next_attempt_at=now()");
      assertThrows(IllegalStateException.class,()->dispatch(true));var first=rabbit.receive(queue,5000);assertNotNull(first);
      assertEquals(event,first.getMessageProperties().getMessageId());assertEquals("PENDING",db.queryForObject("SELECT delivery_status FROM outbox_event",String.class));
      dispatch(false);var duplicate=rabbit.receive(queue,5000);assertNotNull(duplicate);assertEquals(event,duplicate.getMessageProperties().getMessageId());
      assertArrayEquals(first.getBody(),duplicate.getBody());assertEquals(1,count("outbox_event"));
    }finally{admin.deleteQueue(queue);}
  }
}
