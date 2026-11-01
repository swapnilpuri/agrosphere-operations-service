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
class CropHealthServiceIT {
  static PostgreSQLContainer postgres;static GenericContainer<?> broker;static HttpServer master;
  static ConfigurableApplicationContext context;static RSAKey key;static String issuer;static ExecutorService masterThreads;
  static final ObjectMapper json=new ObjectMapper();static JdbcTemplate db;static int port;
  static final UUID estate=UUID.randomUUID(),foreign=UUID.randomUUID(),division=UUID.randomUUID(),block=UUID.randomUUID();
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
        Object payload=(path.contains("/divisions/")||path.contains("/blocks/"))?Map.of("id",requested,"estateId",estate,"status","ACTIVE","cropType","RUBBER"):
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
    db.execute("TRUNCATE crop_health_command,inspection_revision,incident_revision,field_inspection,crop_health_incident,outbox_event CASCADE");
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

  Map<String,Object> reportBody(){return new HashMap<>(Map.of("estateId",estate,"blockId",block,"dataProvenance","SIMULATED",
      "observedAt",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),"category","TREE_DAMAGE","severity","LOW",
      "summary","SIMULATED summary","details","SIMULATED details"));}
  JsonNode report()throws Exception{return json.readTree(write(manager,"POST","/api/v1/incidents",reportBody(),201).body());}
  JsonNode inspection()throws Exception{
    var report=reportBody();var body=new HashMap<>(Map.of("estateId",estate,"blockId",block,"dataProvenance","SIMULATED","observedAt",report.get("observedAt"),
        "outcome","FOLLOW_UP","findings","SIMULATED finding","incident",Map.of("category","TREE_DAMAGE","severity","LOW","summary","SIMULATED summary","details","SIMULATED details")));
    return json.readTree(write(manager,"POST","/api/v1/inspections",body,201).body());
  }
  @Test void fullLinkedLifecycleHistoryAndOriginalReplay()throws Exception{
    var pair=inspection();String i=pair.path("inspection").path("id").asText(),c=pair.path("incident").path("id").asText();
    var triage=Map.of("expectedVersion",0,"inspectionVersion",0,"reason","SIMULATED triage");String key=UUID.randomUUID().toString();
    var first=request(manager,"POST","/api/v1/incidents/"+c+"/triage",triage,key,200);
    var resolve=Map.of("expectedVersion",1,"inspectionVersion",0,"resolvedAt",Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),
        "resolutionNote","SIMULATED resolution","reason","SIMULATED closure");
    write(manager,"POST","/api/v1/incidents/"+c+"/resolve",resolve,200);
    assertEquals(first.body(),request(manager,"POST","/api/v1/incidents/"+c+"/triage",triage,key,200).body());
    write(manager,"POST","/api/v1/incidents/"+c+"/reopen",Map.of("expectedVersion",2,"inspectionVersion",0,"reason","SIMULATED reopen"),200);
    write(manager,"POST","/api/v1/incidents/"+c+"/void",Map.of("expectedVersion",3,"inspectionVersion",0,"reason","SIMULATED void"),200);
    var correction=Map.of("inspectionVersion",0,"incidentVersion",4,"reason","SIMULATED clear correction","observedAt",pair.path("inspection").path("observedAt").asText(),"outcome","CLEAR","findings","SIMULATED clear");
    write(manager,"POST","/api/v1/inspections/"+i+"/corrections",correction,200);
    write(manager,"POST","/api/v1/inspections/"+i+"/void",Map.of("inspectionVersion",1,"incidentVersion",4,"reason","SIMULATED invalid inspection"),200);
    write(manager,"POST","/api/v1/incidents/"+c+"/reopen",Map.of("expectedVersion",4,"inspectionVersion",2,"reason","SIMULATED"),409);
    var history=json.readTree(request(manager,"GET","/api/v1/incidents/"+c+"/history",null,null,200).body());assertEquals(5,history.path("totalElements").asInt());
    assertEquals("SIMULATED-manager",history.path("content").get(0).path("recordedBy").asText());
    Path out=Path.of("target/crop-health-api");Files.createDirectories(out);Files.writeString(out.resolve("inspection-history.json"),request(manager,"GET","/api/v1/inspections/"+i+"/history",null,null,200).body());
    Files.writeString(out.resolve("incident-history.json"),history.toString());
  }
  @Test void signedRolesScopesForeignObjectsAndRevokedReceiptAreEnforced()throws Exception{
    var body=reportBody();String key=UUID.randomUUID().toString();var created=request(manager,"POST","/api/v1/incidents",body,key,201);String id=json.readTree(created.body()).path("id").asText();
    for(String role:List.of("FIELD_WORKER","VIEWER","PLANTATION_ADMIN")){
      String denied=token("SIMULATED-manager",role,List.of(estate.toString()));request(denied,"GET","/api/v1/incidents/"+id,null,null,403);request(denied,"POST","/api/v1/incidents",body,key,403);
    }
    String foreignToken=token("SIMULATED-manager","ESTATE_MANAGER",List.of(foreign.toString()));
    request(foreignToken,"GET","/api/v1/incidents/"+id,null,null,404);
    request(foreignToken,"GET","/api/v1/incidents/"+UUID.randomUUID(),null,null,404);
    request(foreignToken,"GET","/api/v1/incidents?estateId="+estate+"&provenance=SIMULATED",null,null,403);
    request(foreignToken,"POST","/api/v1/incidents",body,key,403);
    String downgraded=token("SIMULATED-manager","FIELD_SUPERVISOR",List.of(estate.toString()));
    request(downgraded,"POST","/api/v1/incidents",body,key,403);
    String supervisor=token("SIMULATED-supervisor","FIELD_SUPERVISOR",List.of(estate.toString()));
    var view=request(supervisor,"GET","/api/v1/incidents/"+id,null,null,200);assertFalse(view.body().contains("createdBy"));
    request(supervisor,"GET","/api/v1/incidents/"+id+"/history",null,null,403);
    write(supervisor,"POST","/api/v1/incidents/"+id+"/triage",Map.of("expectedVersion",0,"reason","SIMULATED"),403);
    String auditor=token("SIMULATED-auditor","AUDITOR",List.of(estate.toString()));request(auditor,"GET","/api/v1/incidents/"+id+"/history",null,null,200);
    request(null,"GET","/api/v1/incidents/"+id,null,null,401);
    request(token("SIMULATED",List.of("ESTATE_MANAGER"),List.of(estate.toString()),"wrong-audience",Instant.now().plusSeconds(60)),"GET","/api/v1/incidents/"+id,null,null,401);
  }
  @Test void closedShapesCanonicalRetryAndRealDenial()throws Exception{
    var body=reportBody();String key=UUID.randomUUID().toString();var first=request(manager,"POST","/api/v1/incidents",body,key,201);
    body.put("summary","  SIMULATED summary  ");body.put("observedAt",OffsetDateTime.parse(body.get("observedAt").toString()).withOffsetSameInstant(ZoneOffset.ofHours(1)).toString());
    assertEquals(first.body(),request(manager,"POST","/api/v1/incidents",body,key,201).body());
    body.put("summary","SIMULATED changed");request(manager,"POST","/api/v1/incidents",body,key,409);
    body.put("dataProvenance","REAL");write(manager,"POST","/api/v1/incidents",body,403);body.put("dataProvenance","SIMULATED");
    body.put("recordedBy","forged");write(manager,"POST","/api/v1/incidents",body,400);body.remove("recordedBy");
    body.put("observedAt","2026-10-31T12:00:00.1234567Z");write(manager,"POST","/api/v1/incidents",body,400);
    request(manager,"GET","/api/v1/incidents?estateId="+estate+"&provenance=SIMULATED&unknown=1",null,null,400);
    request(manager,"GET","/api/v1/incidents?estateId="+estate+"&provenance=SIMULATED&observedFrom=2026-10-30T12:00:00Z",null,null,400);
    request(manager,"GET","/api/v1/incidents?estateId="+estate+"&provenance=SIMULATED&status=OPEN&status=OPEN",null,null,400);
  }
  @Test void backdatesReferenceFailuresAndPairedVersionsFailClosed()throws Exception{
    var body=reportBody();body.put("observedAt",Instant.now().minusSeconds(172800).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
    write(manager,"POST","/api/v1/incidents",body,400);
    String supervisor=token("SIMULATED-supervisor","FIELD_SUPERVISOR",List.of(estate.toString()));write(supervisor,"POST","/api/v1/incidents",body,403);
    body.put("backdateReason","SIMULATED delayed entry");write(manager,"POST","/api/v1/incidents",body,201);
    for(int status:List.of(403,404,503)){masterStatus=status;write(manager,"POST","/api/v1/incidents",reportBody(),status==404?422:status);}masterStatus=200;
    for(String value:List.of("malformed","wrong-id","inactive","oversize","redirect","slow")){shape=value;write(manager,"POST","/api/v1/incidents",reportBody(),Set.of("wrong-id","inactive").contains(value)?422:503);}shape="valid";
    var pair=inspection();String id=pair.path("incident").path("id").asText();
    write(manager,"POST","/api/v1/incidents/"+id+"/triage",Map.of("expectedVersion",0,"reason","SIMULATED"),400);
    write(manager,"POST","/api/v1/incidents/"+id+"/triage",Map.of("expectedVersion",0,"inspectionVersion",1,"reason","SIMULATED"),409);
    assertEquals(0,redirected.get());
  }
  @Test void actualRuntimeAllShapesAndMinimalEventsExported()throws Exception{
    var pair=inspection();String i=pair.path("inspection").path("id").asText(),c=pair.path("incident").path("id").asText();
    write(manager,"POST","/api/v1/incidents/"+c+"/void",Map.of("expectedVersion",0,"inspectionVersion",0,"reason","SIMULATED"),200);
    write(manager,"POST","/api/v1/inspections/"+i+"/void",Map.of("inspectionVersion",0,"incidentVersion",1,"reason","SIMULATED"),200);
    Path out=Path.of("target/crop-health-api");Files.createDirectories(out);
    Files.writeString(out.resolve("created.json"),pair.toString());
    Files.writeString(out.resolve("inspection.json"),request(manager,"GET","/api/v1/inspections/"+i,null,null,200).body());
    Files.writeString(out.resolve("incident.json"),request(manager,"GET","/api/v1/incidents/"+c,null,null,200).body());
    Files.writeString(out.resolve("inspections.json"),request(manager,"GET","/api/v1/inspections?estateId="+estate+"&provenance=SIMULATED",null,null,200).body());
    Files.writeString(out.resolve("incidents.json"),request(manager,"GET","/api/v1/incidents?estateId="+estate+"&provenance=SIMULATED&status=VOIDED",null,null,200).body());
    var api=request(manager,"GET","/v3/api-docs",null,null,200);Path runtime=Path.of("target/openapi-runtime.json");Files.writeString(runtime,api.body());
    int count=0;for(var path:json.readTree(api.body()).path("paths"))count+=path.size();assertEquals(70,count);
    Path events=Path.of("target/crop-health-events");Files.createDirectories(events);
    for(var row:db.queryForList("SELECT event_id,payload::text AS body FROM outbox_event"))Files.writeString(events.resolve(row.get("event_id")+".json"),(String)row.get("body"));
  }
  @Test void brokerOutageRetainsStableEventsAndDeliveryRecovers()throws Exception{
    var rabbit=context.getBean(RabbitTemplate.class);var admin=new RabbitAdmin(rabbit);String queue="crop-proof-"+UUID.randomUUID();
    admin.declareExchange(new TopicExchange("agrosphere.domain",true,false));admin.declareQueue(new org.springframework.amqp.core.Queue(queue,true));
    for(String type:List.of("FieldInspectionRecorded","FieldInspectionChanged","CropHealthIncidentReported","CropHealthIncidentChanged"))
      admin.declareBinding(new Binding(queue,Binding.DestinationType.QUEUE,"agrosphere.domain","agrosphere."+type+".v1",Map.of()));
    inspection();var ids=db.queryForList("SELECT event_id FROM outbox_event",UUID.class);
    var unavailable=new RabbitTemplate(){@Override public void send(String exchange,String key,Message message,org.springframework.amqp.rabbit.connection.CorrelationData data){throw new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("SIMULATED outage"));}};
    new OutboxDispatcher(db,unavailable,10,20,1,1).dispatch();assertEquals(2L,db.queryForObject("SELECT count(*) FROM outbox_event WHERE delivery_status='PENDING'",Long.class));
    db.update("UPDATE outbox_event SET next_attempt_at=now()");new OutboxDispatcher(db,rabbit,10,20,1,2).dispatch();
    assertEquals(2L,db.queryForObject("SELECT count(*) FROM outbox_event WHERE delivery_status='PUBLISHED'",Long.class));
    var seen=new HashSet<UUID>();for(int n=0;n<2;n++){var message=rabbit.receive(queue,3000);assertNotNull(message);seen.add(UUID.fromString(json.readTree(message.getBody()).path("eventId").asText()));}
    assertEquals(new HashSet<>(ids),seen);admin.deleteQueue(queue);
  }
  @Test void alertsRemainDisabledForExistingAccountConfiguration()throws Exception{
    var r=request(manager,"GET","/api/v1/alerts/counts?estateId="+estate,null,null,503);
    assertEquals("no-store",r.headers().firstValue("Cache-Control").orElse(""));
    assertEquals(0L,db.queryForObject("SELECT count(*) FROM alert_read",Long.class));
  }

}
