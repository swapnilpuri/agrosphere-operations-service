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
class AlertServiceIT {
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
        "--operations.alerts.generated-test-mode=true","--operations.alerts.generated-subjects=SIMULATED-manager,SIMULATED-supervisor,SIMULATED-auditor",
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
      "observedAt",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),"category","TREE_DAMAGE","severity","HIGH",
      "summary","SIMULATED summary","details","SIMULATED details"));}
  JsonNode report()throws Exception{return json.readTree(write(manager,"POST","/api/v1/incidents",reportBody(),201).body());}
  JsonNode inspection()throws Exception{
    var report=reportBody();var body=new HashMap<>(Map.of("estateId",estate,"blockId",block,"dataProvenance","SIMULATED","observedAt",report.get("observedAt"),
        "outcome","FOLLOW_UP","findings","SIMULATED finding","incident",Map.of("category","TREE_DAMAGE","severity","HIGH","summary","SIMULATED summary","details","SIMULATED details")));
    return json.readTree(write(manager,"POST","/api/v1/inspections",body,201).body());
  }

  String alertId()throws Exception{
    var page=json.readTree(request(manager,"GET","/api/v1/alerts?estateId="+estate,null,null,200).body());return page.path("content").get(0).path("id").asText();
  }
  void export(String name,String value)throws Exception{Path out=Path.of("target/alert-api");Files.createDirectories(out);Files.writeString(out.resolve(name+".json"),value);}
  @Test void fullSourceInboxReadAckResolveReopenAndOriginalRetry()throws Exception{
    var source=report();String incident=source.path("id").asText(),id=alertId();
    var page=request(manager,"GET","/api/v1/alerts?estateId="+estate,null,null,200);export("page",page.body());
    var detail=request(manager,"GET","/api/v1/alerts/"+id,null,null,200);export("detail",detail.body());
    assertEquals("no-store",detail.headers().firstValue("Cache-Control").orElse(""));
    assertFalse(detail.body().contains("SIMULATED summary"));assertFalse(detail.body().contains("acknowledgedBy"));
    var counts=request(manager,"GET","/api/v1/alerts/counts?estateId="+estate,null,null,200);export("counts",counts.body());
    assertEquals(1,json.readTree(counts.body()).path("activeUnreadForMe").asInt());
    String supervisor=token("SIMULATED-supervisor","FIELD_SUPERVISOR",List.of(estate.toString()));
    var read=write(supervisor,"POST","/api/v1/alerts/"+id+"/read",Map.of("attentionVersion",1),200);export("read",read.body());
    assertFalse(json.readTree(read.body()).path("unreadForMe").asBoolean());assertFalse(json.readTree(read.body()).path("acknowledged").asBoolean());
    assertTrue(json.readTree(request(manager,"GET","/api/v1/alerts/"+id,null,null,200).body()).path("unreadForMe").asBoolean());
    String key=UUID.randomUUID().toString();var body=Map.of("attentionVersion",1,"expectedAlertVersion",1);
    var ack=request(manager,"POST","/api/v1/alerts/"+id+"/acknowledgements",body,key,200);export("ack",ack.body());
    assertEquals(2,json.readTree(ack.body()).path("alertVersion").asInt());
    write(manager,"POST","/api/v1/incidents/"+incident+"/resolve",Map.of("expectedVersion",0,"reason","SIMULATED closure","resolvedAt",Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),"resolutionNote","SIMULATED resolution"),200);
    assertEquals(ack.body(),request(manager,"POST","/api/v1/alerts/"+id+"/acknowledgements",body,key,200).body());
    write(manager,"POST","/api/v1/alerts/"+id+"/acknowledgements",Map.of("attentionVersion",1,"expectedAlertVersion",3),409);
    write(manager,"POST","/api/v1/incidents/"+incident+"/reopen",Map.of("expectedVersion",1,"reason","SIMULATED revisit"),200);
    var reopened=json.readTree(request(manager,"GET","/api/v1/alerts/"+id,null,null,200).body());
    assertEquals(2,reopened.path("attentionVersion").asInt());assertFalse(reopened.path("acknowledged").asBoolean());assertTrue(reopened.path("unreadForMe").asBoolean());
    write(supervisor,"POST","/api/v1/alerts/"+id+"/read",Map.of("attentionVersion",1),409);
    export("history",request(manager,"GET","/api/v1/alerts/"+id+"/history",null,null,200).body());
    assertEquals(1L,db.queryForObject("SELECT count(*) FROM alert_acknowledgement",Long.class));
    assertEquals(3L,db.queryForObject("SELECT count(*) FROM outbox_event",Long.class));
  }
  @Test void signedPositiveRolesScopeAndReceiptDowngradeAndNoStore()throws Exception{
    report();String id=alertId();String path="/api/v1/alerts/"+id;
    for(String role:List.of("ESTATE_MANAGER","ESTATE_ADMIN","FIELD_SUPERVISOR","AUDITOR","PLATFORM_ADMIN")){
      String who=token("SIMULATED-auditor",role,List.of(estate.toString()));request(who,"GET",path,null,null,200);
    }
    for(String role:List.of("VIEWER","PLANTATION_ADMIN","AGRONOMIST","FIELD_WORKER")){
      var r=request(token("SIMULATED-auditor",role,List.of(estate.toString())),"GET",path,null,null,403);assertEquals("no-store",r.headers().firstValue("Cache-Control").orElse(""));
    }
    String outsider=token("SIMULATED-auditor","ESTATE_MANAGER",List.of(foreign.toString()));
    request(outsider,"GET",path,null,null,404);request(outsider,"GET","/api/v1/alerts?estateId="+estate,null,null,403);
    request(token("not-generated","ESTATE_MANAGER",List.of(estate.toString())),"GET",path,null,null,403);
    String key=UUID.randomUUID().toString();var body=Map.of("expectedAlertVersion",1,"attentionVersion",1);
    request(manager,"POST",path+"/acknowledgements",body,key,200);
    request(token("SIMULATED-manager","AUDITOR",List.of(estate.toString())),"POST",path+"/acknowledgements",body,key,403);
    request(token("SIMULATED-supervisor","FIELD_SUPERVISOR",List.of(estate.toString())),"GET",path+"/history",null,null,403);
    request(token("SIMULATED-auditor","AUDITOR",List.of(estate.toString())),"GET",path+"/history",null,null,200);
    request(null,"GET",path,null,null,401);
    request(token("SIMULATED-manager",List.of("ESTATE_MANAGER"),List.of(estate.toString()),"wrong-audience",Instant.now().plusSeconds(60)),"GET",path,null,null,401);
  }
  @Test void closedShapesKeysQueryBoundsAndReadNoopAreEnforced()throws Exception{
    report();String id=alertId(),path="/api/v1/alerts/"+id+"/read",key=UUID.randomUUID().toString();
    var first=request(manager,"POST",path,"{\"attentionVersion\":1.0}",key,200);
    assertEquals(first.body(),request(manager,"POST",path,Map.of("attentionVersion",1),key,200).body());
    request(manager,"POST",path,Map.of("attentionVersion",2),key,409);
    write(manager,"POST",path,Map.of("attentionVersion",1,"subject","forged"),400);
    write(manager,"POST",path,"{\"attentionVersion\":1,\"attentionVersion\":1}",400);
    write(manager,"POST",path,Map.of("attentionVersion",1.5),400);
    write(manager,"POST",path,Map.of("attentionVersion",1),200);
    assertEquals(1L,db.queryForObject("SELECT count(*) FROM alert_read",Long.class));
    request(manager,"GET","/api/v1/alerts?estateId="+estate+"&page=501&size=20",null,null,400);
    request(manager,"GET","/api/v1/alerts?estateId="+estate+"&estateId="+estate,null,null,400);
    request(manager,"GET","/api/v1/alerts/counts?estateId="+estate+"&subject=forged",null,null,400);
  }
  @Test void linkedFollowUpHasOneAlertAndEscalationResetsOnlyAttention()throws Exception{
    var pair=inspection();String incident=pair.path("incident").path("id").asText(),id=alertId();
    assertEquals(1L,db.queryForObject("SELECT count(*) FROM crop_health_alert",Long.class));
    write(manager,"POST","/api/v1/alerts/"+id+"/acknowledgements",Map.of("attentionVersion",1,"expectedAlertVersion",1),200);
    var edit=reportBody();edit.remove("estateId");edit.remove("blockId");edit.remove("dataProvenance");edit.put("expectedVersion",0);edit.put("inspectionVersion",0);edit.put("reason","SIMULATED amendment");
    write(manager,"POST","/api/v1/incidents/"+incident+"/amendments",edit,200);
    var unchanged=json.readTree(request(manager,"GET","/api/v1/alerts/"+id,null,null,200).body());assertTrue(unchanged.path("acknowledged").asBoolean());
    edit.put("expectedVersion",1);edit.put("severity","CRITICAL");write(manager,"POST","/api/v1/incidents/"+incident+"/amendments",edit,200);
    var escalated=json.readTree(request(manager,"GET","/api/v1/alerts/"+id,null,null,200).body());assertEquals(2,escalated.path("attentionVersion").asInt());assertFalse(escalated.path("acknowledged").asBoolean());
    assertEquals(1L,db.queryForObject("SELECT count(*) FROM crop_health_alert",Long.class));
  }
  @Test void alertFailureRollsBackSourceReceiptRevisionAndOutbox()throws Exception{
    db.execute("CREATE FUNCTION reject_generated_alert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Generated fault'; END $$");
    db.execute("CREATE TRIGGER generated_fault BEFORE INSERT ON crop_health_alert FOR EACH ROW EXECUTE FUNCTION reject_generated_alert()");
    try {write(manager,"POST","/api/v1/incidents",reportBody(),500);}
    finally {db.execute("DROP TRIGGER generated_fault ON crop_health_alert");db.execute("DROP FUNCTION reject_generated_alert()");}
    for(String table:List.of("crop_health_incident","incident_revision","crop_health_command","outbox_event","crop_health_alert"))assertEquals(0L,db.queryForObject("SELECT count(*) FROM "+table,Long.class));
  }
  @Test void realDeniedAndRuntimeCannotRewriteEvidenceOrDdl()throws Exception{
    var body=reportBody();body.put("dataProvenance","REAL");write(manager,"POST","/api/v1/incidents",body,403);
    report();String id=alertId();write(manager,"POST","/api/v1/alerts/"+id+"/acknowledgements",Map.of("attentionVersion",1,"expectedAlertVersion",1),200);
    try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var stmt=connection.createStatement()){
      stmt.execute("SET ROLE agrosphere_operations_app");
      for(String sql:List.of("DELETE FROM alert_transition","UPDATE alert_acknowledgement SET subject='forged'","TRUNCATE crop_health_alert","CREATE TABLE forbidden_alert(id int)","UPDATE alert_command SET response_body='{}'"))assertThrows(java.sql.SQLException.class,()->stmt.execute(sql));
    }
  }
  @Test void concurrentManagersOnlyOneSharedAcknowledgementWins()throws Exception{
    report();String id=alertId(),other=token("SIMULATED-supervisor","ESTATE_MANAGER",List.of(estate.toString()));
    var pool=Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
    try{
      java.util.function.Function<String,java.util.concurrent.Callable<Integer>> task=who->()->{start.await();var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/alerts/"+id+"/acknowledgements")).header("Authorization","Bearer "+who).header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString("{\"expectedAlertVersion\":1,\"attentionVersion\":1}")).build();return http.send(r,HttpResponse.BodyHandlers.ofString()).statusCode();};
      var a=pool.submit(task.apply(manager));var b=pool.submit(task.apply(other));start.countDown();var statuses=new ArrayList<>(List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
      assertEquals(1L,db.queryForObject("SELECT count(*) FROM alert_acknowledgement",Long.class));
    }finally{pool.shutdownNow();}
  }
  @Test void generatedTablesBackupRestoreAndUpgradeNoBackfill()throws Exception{
    report();String id=alertId();write(manager,"POST","/api/v1/alerts/"+id+"/read",Map.of("attentionVersion",1),200);
    write(manager,"POST","/api/v1/alerts/"+id+"/acknowledgements",Map.of("attentionVersion",1,"expectedAlertVersion",1),200);
    String clone="an_restore_"+UUID.randomUUID().toString().replace("-","");
    assertEquals(0,postgres.execInContainer("pg_dump","-U",postgres.getUsername(),"-d",postgres.getDatabaseName(),"--format=custom","-f","/tmp/an.dump").getExitCode());
    assertEquals(0,postgres.execInContainer("psql","-U",postgres.getUsername(),"-d","postgres","-c","CREATE DATABASE "+clone).getExitCode());
    assertEquals(0,postgres.execInContainer("pg_restore","-U",postgres.getUsername(),"--dbname",clone,"--no-owner","/tmp/an.dump").getExitCode());
    var restored=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName()+"?","/"+clone+"?"),postgres.getUsername(),postgres.getPassword()));
    assertEquals(db.queryForObject("SELECT snapshot::text FROM crop_health_alert",String.class),restored.queryForObject("SELECT snapshot::text FROM crop_health_alert",String.class));
    assertEquals(1L,restored.queryForObject("SELECT count(*) FROM alert_read",Long.class));
    assertEquals(1L,restored.queryForObject("SELECT count(*) FROM alert_acknowledgement",Long.class));
    assertEquals(2L,restored.queryForObject("SELECT count(*) FROM alert_command",Long.class));
    String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");
    org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).target("5").load().migrate();
    org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
    var upgraded=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl()+"&currentSchema="+schema,postgres.getUsername(),postgres.getPassword()));assertEquals(0L,upgraded.queryForObject("SELECT count(*) FROM crop_health_alert",Long.class));
  }
  @Test void failedV6RollsBackAndPopulatedV5UpgradeDoesNotBackfill()throws Exception{
    String schema="populated_"+UUID.randomUUID().toString().replace("-","");
    org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).target("5").load().migrate();
    var source=new DriverManagerDataSource(postgres.getJdbcUrl()+"&currentSchema="+schema,postgres.getUsername(),postgres.getPassword());
    var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    var store=new com.agrosphere.operations.crophealth.infrastructure.JdbcCropHealthStore(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(source));
    var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var command=new com.agrosphere.operations.crophealth.domain.CropHealthRules.Command(new com.agrosphere.operations.crophealth.domain.CropHealthRules.Actor("SIMULATED-upgrade",Set.of("PLATFORM_ADMIN"),Set.of()),java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),UUID.randomUUID());
    var change=com.agrosphere.operations.crophealth.application.CropHealthChanges.directReport(UUID.randomUUID(),new com.agrosphere.operations.crophealth.domain.CropHealthRules.Context(estate,block,ZoneId.of("Etc/UTC")),com.agrosphere.operations.crophealth.domain.CropHealthRules.Provenance.SIMULATED,
      new com.agrosphere.operations.crophealth.domain.CropHealthRules.Report(now.minusSeconds(60),com.agrosphere.operations.crophealth.domain.CropHealthRules.Category.TREE_DAMAGE,com.agrosphere.operations.crophealth.domain.CropHealthRules.Severity.HIGH,"SIMULATED","SIMULATED"),null,command);
    tx.executeWithoutResult(t->{store.insert(change.incident());change.revisions().forEach(store::appendRevision);});
    var owner=new JdbcTemplate(source);String before=owner.queryForObject("SELECT snapshot::text FROM crop_health_incident",String.class);
    Path scratch=Files.createTempDirectory(Path.of("target"),"an6-failed-ddl-");
    try{
      try(var paths=Files.list(Path.of("src/main/resources/db/migration"))){for(Path file:paths.toList())Files.copy(file,scratch.resolve(file.getFileName()));}
      Files.writeString(scratch.resolve("V6__in_app_crop_health_alerts.sql"),"\nSELECT 1/0;\n",java.nio.file.StandardOpenOption.APPEND);
      assertThrows(RuntimeException.class,()->org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("filesystem:"+scratch.toAbsolutePath()).load().migrate());
      assertNull(owner.queryForObject("SELECT to_regclass('crop_health_alert')",String.class));assertEquals(before,owner.queryForObject("SELECT snapshot::text FROM crop_health_incident",String.class));
    }finally{try(var paths=Files.list(scratch)){for(Path f:paths.toList())Files.delete(f);}Files.delete(scratch);}
    org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    assertEquals(0L,owner.queryForObject("SELECT count(*) FROM crop_health_alert",Long.class));assertEquals(before,owner.queryForObject("SELECT snapshot::text FROM crop_health_incident",String.class));
  }

}
