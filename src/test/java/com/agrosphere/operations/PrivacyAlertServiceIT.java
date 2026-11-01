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
class PrivacyAlertServiceIT {
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
        "--operations.alerts.generated-test-mode=false","--operations.alerts.privacy-generated-test-mode=true","--operations.alerts.generated-subjects=SIMULATED-manager,SIMULATED-supervisor,SIMULATED-auditor",
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
    db.execute("TRUNCATE privacy_removal,crop_health_command,inspection_revision,incident_revision,field_inspection,crop_health_incident,outbox_event CASCADE");
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
  HttpResponse<String> write(String token,String method,String path,Object body,int status)throws Exception{return request(token,method,path,body,path.startsWith("/api/v2/alerts")?v7(Instant.now()).toString():UUID.randomUUID().toString(),status);}

  Map<String,Object> reportBody(){return new HashMap<>(Map.of("estateId",estate,"blockId",block,"dataProvenance","SIMULATED",
      "observedAt",Instant.now().minus(Duration.ofDays(400)).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),"category","TREE_DAMAGE","severity","HIGH",
      "summary","SIMULATED summary","details","SIMULATED details","backdateReason","SIMULATED historical retention fixture"));}
  JsonNode report()throws Exception{return json.readTree(write(manager,"POST","/api/v1/incidents",reportBody(),201).body());}
  JsonNode inspection()throws Exception{
    var report=reportBody();var body=new HashMap<>(Map.of("estateId",estate,"blockId",block,"dataProvenance","SIMULATED","observedAt",report.get("observedAt"),
        "outcome","FOLLOW_UP","findings","SIMULATED finding","incident",Map.of("category","TREE_DAMAGE","severity","HIGH","summary","SIMULATED summary","details","SIMULATED details")));
    return json.readTree(write(manager,"POST","/api/v1/inspections",body,201).body());
  }

  String alertId()throws Exception{
    var page=json.readTree(request(manager,"GET","/api/v2/alerts?estateId="+estate,null,null,200).body());return page.path("content").get(0).path("id").asText();
  }
  void export(String name,String value)throws Exception{Path out=Path.of("target/privacy-api");Files.createDirectories(out);Files.writeString(out.resolve(name+".json"),value);}
  UUID v7(Instant at){return new UUID((at.toEpochMilli()<<16)|0x7000,0x8000000000000000L|(UUID.randomUUID().getLeastSignificantBits()&0x3fffffffffffffffL));}
  void resolve(String incident,long version)throws Exception{
    write(manager,"POST","/api/v1/incidents/"+incident+"/resolve",Map.of("expectedVersion",version,"reason","SIMULATED closure","resolvedAt",Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),"resolutionNote","SIMULATED resolution"),200);
  }
  void ageFixture(String id,int days){
    // Synthetic fixture mutation in this class's owned disposable database only; never active-stack clock/data.
    db.execute("ALTER TABLE privacy_alert DISABLE TRIGGER privacy_root_guard");
    try{db.update("UPDATE privacy_alert SET inactive_since=now()-(?*interval '1 day'),body=jsonb_set(jsonb_set(jsonb_set(body,'{raisedAt}',to_jsonb(to_char((now()-(?*interval '1 day')) AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'))),'{attentionAt}',to_jsonb(to_char((now()-(?*interval '1 day')) AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'))),'{updatedAt}',to_jsonb(to_char((now()-(?*interval '1 day')) AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'))) WHERE id=?",days,days+1,days+1,days,UUID.fromString(id));}
    finally{db.execute("ALTER TABLE privacy_alert ENABLE TRIGGER privacy_root_guard");}
    // Attribution fixture chronology matches the synthetic historical attention interval.
    db.execute("ALTER TABLE privacy_ack DISABLE TRIGGER privacy_ack_immutable");
    try{db.update("UPDATE privacy_ack SET acknowledged_at=now()-(?*interval '1 day')-interval '1 hour' WHERE alert_id=?",days,UUID.fromString(id));}
    finally{db.execute("ALTER TABLE privacy_ack ENABLE TRIGGER privacy_ack_immutable");}
  }
  @Test void signedHttpLifecycleOwnReadSharedAckAndRetiredV1()throws Exception{
    var source=report();String id=alertId(),path="/api/v2/alerts/"+id;
    export("page",request(manager,"GET","/api/v2/alerts?estateId="+estate,null,null,200).body());
    export("detail",request(manager,"GET",path,null,null,200).body());
    export("counts",request(manager,"GET","/api/v2/alerts/counts?estateId="+estate,null,null,200).body());
    String supervisor=token("SIMULATED-supervisor","FIELD_SUPERVISOR",List.of(estate.toString()));
    var read=write(supervisor,"POST",path+"/read",Map.of("attentionVersion",1),200);export("read",read.body());
    assertFalse(json.readTree(read.body()).path("unreadForMe").asBoolean());
    assertTrue(json.readTree(request(manager,"GET",path,null,null,200).body()).path("unreadForMe").asBoolean());
    String key=v7(Instant.now()).toString();var body=Map.of("attentionVersion",1,"expectedAlertVersion",1);
    var ack=request(manager,"POST",path+"/acknowledgements",body,key,200);export("ack",ack.body());
    resolve(source.path("id").asText(),0);
    assertEquals(ack.body(),request(manager,"POST",path+"/acknowledgements",body,key,200).body());
    write(manager,"POST","/api/v1/incidents/"+source.path("id").asText()+"/reopen",Map.of("expectedVersion",1,"reason","SIMULATED revisit"),200);
    var reopened=json.readTree(request(manager,"GET",path,null,null,200).body());assertEquals(2,reopened.path("attentionVersion").asLong());assertFalse(reopened.path("acknowledged").asBoolean());
    export("history",request(manager,"GET",path+"/history",null,null,200).body());
    request(supervisor,"GET",path+"/history",null,null,403);
    var retired=request(manager,"GET","/api/v1/alerts/"+id,null,null,410);assertEquals("ALERT_API_RETIRED",json.readTree(retired.body()).path("code").asText());
    assertEquals("no-store",retired.headers().firstValue("Cache-Control").orElse(""));
  }
  @Test void boundedKeysAndAuthorizationPrecedeReplay()throws Exception{
    report();String path="/api/v2/alerts/"+alertId()+"/read";var body=Map.of("attentionVersion",1);
    request(manager,"POST",path,body,UUID.randomUUID().toString(),400);
    request(manager,"POST",path,body,v7(Instant.now().minus(Duration.ofDays(31))).toString(),410);
    request(manager,"POST",path,body,v7(Instant.now().minusSeconds(600)).toString(),409);
    request(manager,"POST",path,body,v7(Instant.now().plusSeconds(600)).toString(),400);
    String key=v7(Instant.now()).toString();var first=request(manager,"POST",path,body,key,200);
    assertEquals(first.body(),request(manager,"POST",path,body,key,200).body());
    request(token("SIMULATED-manager","AUDITOR",List.of(foreign.toString())),"POST",path,body,key,404);
    request(token("not-generated","ESTATE_MANAGER",List.of(estate.toString())),"POST",path,body,key,403);
    request(manager,"POST",path,Map.of("attentionVersion",2),key,409);
    assertEquals(1L,db.queryForObject("SELECT count(*) FROM privacy_command",Long.class));
  }
  @Test void expiredReadIsLogicallyHiddenThenPhysicallyDeletedAndDoesNotReturnOnReopen()throws Exception{
    var source=report();String id=alertId(),path="/api/v2/alerts/"+id;
    write(manager,"POST",path+"/read",Map.of("attentionVersion",1),200);resolve(source.path("id").asText(),0);ageFixture(id,31);
    var expired=json.readTree(request(manager,"GET",path,null,null,200).body());assertEquals("EXPIRED",expired.path("readState").asText());assertFalse(expired.path("unreadForMe").asBoolean());
    export("expired-read",expired.toString());write(manager,"POST",path+"/read",Map.of("attentionVersion",1),410);
    assertEquals(1L,db.queryForObject("SELECT count(*) FROM privacy_read",Long.class));
    db.queryForObject("SELECT privacy_cleanup(100)",Integer.class);
    assertEquals(0L,db.queryForObject("SELECT count(*) FROM privacy_read",Long.class));assertEquals(0L,db.queryForObject("SELECT count(*) FROM privacy_command",Long.class));
    write(manager,"POST","/api/v1/incidents/"+source.path("id").asText()+"/reopen",Map.of("expectedVersion",1,"reason","SIMULATED revisit"),200);
    var reopened=json.readTree(request(manager,"GET",path,null,null,200).body());assertEquals("UNREAD",reopened.path("readState").asText());assertTrue(reopened.path("readAt").isNull());
  }
  @Test void historyAttributionExpireWithoutResettingAckAndReopenIsPartial()throws Exception{
    var source=report();String id=alertId(),path="/api/v2/alerts/"+id;
    write(manager,"POST",path+"/acknowledgements",Map.of("attentionVersion",1,"expectedAlertVersion",1),200);
    resolve(source.path("id").asText(),0);ageFixture(id,366);
    var redacted=json.readTree(request(manager,"GET",path,null,null,200).body());assertTrue(redacted.path("acknowledged").asBoolean());assertEquals("REDACTED",redacted.path("acknowledgementState").asText());assertTrue(redacted.path("acknowledgedAt").isNull());export("redacted",redacted.toString());
    var history=json.readTree(request(manager,"GET",path+"/history",null,null,200).body());assertEquals("EXPIRED",history.path("retentionState").asText());assertEquals(0,history.path("totalElements").asInt());export("expired-history",history.toString());
    db.queryForObject("SELECT privacy_cleanup(100)",Integer.class);
    assertEquals(0L,db.queryForObject("SELECT count(*) FROM privacy_ack",Long.class));assertEquals(0L,db.queryForObject("SELECT count(*) FROM privacy_evidence",Long.class));
    write(manager,"POST","/api/v1/incidents/"+source.path("id").asText()+"/reopen",Map.of("expectedVersion",1,"reason","SIMULATED revisit"),200);
    var partial=json.readTree(request(manager,"GET",path+"/history",null,null,200).body());assertEquals("PARTIAL",partial.path("retentionState").asText());assertEquals(1,partial.path("totalElements").asInt());export("partial-history",partial.toString());
  }
  @Test void removalSuppressesReadAndFrozenKeyAndCleanupCompletesWithoutAckPurge()throws Exception{
    report();String id=alertId(),path="/api/v2/alerts/"+id;String key=v7(Instant.now().plusSeconds(240)).toString();var body=Map.of("attentionVersion",1);
    request(manager,"POST",path+"/read",body,key,200);
    db.queryForObject("SELECT privacy_request_removal(?,?)",UUID.class,issuer,"SIMULATED-manager");
    var view=json.readTree(request(manager,"GET",path,null,null,200).body());assertTrue(view.path("readAt").isNull());
    request(manager,"POST",path+"/read",body,key,410);
    db.queryForObject("SELECT privacy_cleanup(100)",Integer.class);
    assertEquals(0L,db.queryForObject("SELECT count(*) FROM privacy_read",Long.class));assertEquals(0L,db.queryForObject("SELECT count(*) FROM privacy_command",Long.class));
    assertEquals(1L,db.queryForObject("SELECT count(*) FROM privacy_removal WHERE completed_at IS NOT NULL AND expires_at=completed_at+interval '66 days'",Long.class));
    request(manager,"POST",path+"/read",body,key,410);
  }
  @Test void appHasNoDeleteOrMaintenanceExecutionAndNoPrivateSnapshots()throws Exception{
    report();String id=alertId(),path="/api/v2/alerts/"+id;
    write(manager,"POST",path+"/acknowledgements",Map.of("attentionVersion",1,"expectedAlertVersion",1),200);
    var runtime=context.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class).getJdbcTemplate();
    assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.update("DELETE FROM privacy_read"));
    assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.queryForObject("SELECT privacy_cleanup(1)",Integer.class));
    assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.queryForObject("SELECT privacy_request_removal('https://example.invalid','generated')",UUID.class));
    assertEquals(0L,db.queryForObject("SELECT count(*) FROM privacy_alert WHERE body ?| ARRAY['issuer','subject','acknowledgedBy','acknowledgedAt']",Long.class));
    assertEquals(0L,db.queryForObject("SELECT count(*) FROM privacy_evidence WHERE evidence ? 'acknowledgedBy'",Long.class));
  }
  @Test void nonemptyLegacyStorageFailsClosedWithoutChangingEitherStore()throws Exception{
    var source=report();String id=alertId();
    var store=context.getBean(com.agrosphere.operations.alerts.infrastructure.JdbcAlertStore.class);
    var src=new com.agrosphere.operations.alerts.domain.AlertRules.Source(UUID.fromString(source.path("id").asText()),estate,block,ZoneId.of("Africa/Libreville"),com.agrosphere.operations.crophealth.domain.CropHealthRules.Provenance.SIMULATED,0,Instant.parse(source.path("observedAt").asText()),com.agrosphere.operations.crophealth.domain.CropHealthRules.Category.TREE_DAMAGE,com.agrosphere.operations.crophealth.domain.CropHealthRules.Severity.HIGH,com.agrosphere.operations.crophealth.domain.CropHealthRules.Status.OPEN);
    var change=com.agrosphere.operations.alerts.domain.CropHealthAlert.evaluate(UUID.randomUUID(),null,null,src,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),UUID.randomUUID());
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->store.save(change.alert(),null,change.transition()));
    String legacy=db.queryForObject("SELECT snapshot::text FROM crop_health_alert",String.class);
    request(manager,"GET","/api/v2/alerts/"+id,null,null,503);
    assertEquals(legacy,db.queryForObject("SELECT snapshot::text FROM crop_health_alert",String.class));
    assertEquals(1L,db.queryForObject("SELECT count(*) FROM privacy_alert",Long.class));
  }
  @Test void concurrentManagersOnlyOneFirstAcknowledgementWins()throws Exception{
    report();String id=alertId();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try{
      java.util.concurrent.Callable<Integer> task=()->{start.await();var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v2/alerts/"+id+"/acknowledgements")).header("Authorization","Bearer "+manager).header("Idempotency-Key",v7(Instant.now()).toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"attentionVersion\":1,\"expectedAlertVersion\":1}")).build();return http.send(req,HttpResponse.BodyHandlers.ofString()).statusCode();};
      var first=pool.submit(task);var second=pool.submit(task);start.countDown();var results=new ArrayList<>(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));Collections.sort(results);assertEquals(List.of(200,409),results);
      assertEquals(1L,db.queryForObject("SELECT count(*) FROM privacy_ack",Long.class));assertEquals(1L,db.queryForObject("SELECT count(*) FROM privacy_command",Long.class));
    }finally{pool.shutdownNow();}
  }
  @Test void isolatedRestoreReappliesCurrentRemovalLedgerBeforeReadAccess()throws Exception{
    report();String id=alertId();write(manager,"POST","/api/v2/alerts/"+id+"/read",Map.of("attentionVersion",1),200);
    String clone="privacy_restore_"+UUID.randomUUID().toString().replace("-","");
    assertEquals(0,postgres.execInContainer("pg_dump","-U",postgres.getUsername(),"-d",postgres.getDatabaseName(),"--format=custom","-f","/tmp/privacy-restore.dump").getExitCode());
    db.queryForObject("SELECT privacy_request_removal(?,?)",UUID.class,issuer,"SIMULATED-manager");db.queryForObject("SELECT privacy_cleanup(100)",Integer.class);
    var ledger=db.queryForMap("SELECT * FROM privacy_removal WHERE issuer=? AND subject=?",issuer,"SIMULATED-manager");
    assertEquals(0,postgres.execInContainer("psql","-U",postgres.getUsername(),"-d","postgres","-c","CREATE DATABASE "+clone).getExitCode());
    assertEquals(0,postgres.execInContainer("pg_restore","-U",postgres.getUsername(),"--dbname",clone,"--no-owner","/tmp/privacy-restore.dump").getExitCode());
    var url=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName()+"?","/"+clone+"?");
    var restored=new JdbcTemplate(new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));
    // Restored database is isolated, without a listening app; snapshot predates the removal request.
    assertEquals(1L,restored.queryForObject("SELECT count(*) FROM privacy_read",Long.class));
    restored.update("INSERT INTO privacy_removal(issuer,subject,request_id,requested_at,key_cutoff,completed_at,expires_at) VALUES(?,?,?,?,?,?,?)",ledger.get("issuer"),ledger.get("subject"),ledger.get("request_id"),ledger.get("requested_at"),ledger.get("key_cutoff"),ledger.get("completed_at"),ledger.get("expires_at"));
    restored.queryForObject("SELECT privacy_cleanup(100)",Integer.class);
    assertEquals(0L,restored.queryForObject("SELECT count(*) FROM privacy_read",Long.class));assertEquals(0L,restored.queryForObject("SELECT count(*) FROM privacy_command",Long.class));
    assertEquals(1L,restored.queryForObject("SELECT count(*) FROM privacy_alert",Long.class));
  }

  @Test void reopeningBeforePhysicalCleanupNeverReplaysExpiredCopies()throws Exception{
    var source=report();String id=alertId(),path="/api/v2/alerts/"+id;String key=v7(Instant.now()).toString();var body=Map.of("attentionVersion",1);
    request(manager,"POST",path+"/read",body,key,200);resolve(source.path("id").asText(),0);ageFixture(id,31);
    write(manager,"POST","/api/v1/incidents/"+source.path("id").asText()+"/reopen",Map.of("expectedVersion",1,"reason","SIMULATED revisit before maintenance"),200);
    assertEquals(1L,db.queryForObject("SELECT count(*) FROM privacy_read",Long.class));
    var current=json.readTree(request(manager,"GET",path,null,null,200).body());assertTrue(current.path("unreadForMe").asBoolean());assertTrue(current.path("readAt").isNull());
    request(manager,"POST",path+"/read",body,key,410);
    db.queryForObject("SELECT privacy_cleanup(100)",Integer.class);
    assertEquals(0L,db.queryForObject("SELECT count(*) FROM privacy_read",Long.class));assertEquals(0L,db.queryForObject("SELECT count(*) FROM privacy_command",Long.class));
  }

  @Test void explicitNewReadReplacesMarkerExactlyAtCompletedRemovalCutoff()throws Exception{
    report();String id=alertId();write(manager,"POST","/api/v2/alerts/"+id+"/read",Map.of("attentionVersion",1),200);
    Instant cutoff=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    db.update("INSERT INTO privacy_removal(issuer,subject,request_id,requested_at,key_cutoff,completed_at,expires_at) VALUES(?,?,?,?::timestamptz-interval '5 minutes',?,?,?::timestamptz+interval '66 days')",issuer,"SIMULATED-manager",UUID.randomUUID(),Timestamp.from(cutoff),Timestamp.from(cutoff),Timestamp.from(cutoff),Timestamp.from(cutoff));
    db.update("UPDATE privacy_read SET read_at=? WHERE alert_id=?",Timestamp.from(cutoff),UUID.fromString(id));
    var store=context.getBean(com.agrosphere.operations.alerts.infrastructure.JdbcPrivacyAlertStore.class);
    var identity=new com.agrosphere.operations.alerts.domain.AlertRules.Identity(issuer,"SIMULATED-manager");
    var actor=new com.agrosphere.operations.alerts.domain.AlertRules.Actor(identity,Set.of("ESTATE_MANAGER"),Set.of(estate));
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{var a=store.get(UUID.fromString(id),actor,true);assertNull(store.ownRead(a,identity,cutoff));store.saveRead(a,identity,cutoff.plusSeconds(1));assertEquals(cutoff.plusSeconds(1),store.ownRead(a,identity,cutoff.plusSeconds(1)).at());});
  }
  @Test void failedV7RollsBackWithoutChangingLegacyMigrationChain()throws Exception{
    String schema="privacy_failed_"+UUID.randomUUID().toString().replace("-","");
    org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).target("6").load().migrate();
    var owner=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl()+"&currentSchema="+schema,postgres.getUsername(),postgres.getPassword()));
    var before=owner.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
    Path temp=Files.createTempDirectory(Path.of("target"),"privacy-v7-rollback-");
    try(var paths=Files.list(Path.of("src/main/resources/db/migration"))){for(var file:paths.toList())Files.copy(file,temp.resolve(file.getFileName()));}
    Files.writeString(temp.resolve("V7__alert_privacy_storage.sql"),"\nSELECT 1/0;\n",StandardOpenOption.APPEND);
    assertThrows(RuntimeException.class,()->org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).schemas(schema).defaultSchema(schema).locations("filesystem:"+temp).load().migrate());
    assertEquals(before,owner.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank"));assertNull(owner.queryForObject("SELECT to_regclass('privacy_alert')",String.class));assertNotNull(owner.queryForObject("SELECT to_regclass('crop_health_alert')",String.class));
  }

}
