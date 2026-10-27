package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;

import com.agrosphere.operations.application.*;
import com.agrosphere.operations.domain.*;
import com.agrosphere.operations.domain.CollectionRules.*;
import com.agrosphere.operations.domain.LatexCollection.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CollectionCredentialsIT {
  static Network network;
  static PostgreSQLContainer postgres;
  static ConfigurableApplicationContext context;
  static String jdbcUrl,ownerPassword,runtimePassword;
  static JdbcTemplate runtime;
  static CollectionRepository repository;
  static TransactionTemplate tx;
  static final String OWNER="agrosphere_operations",APP="agrosphere_operations_app";
  static final String PREVIOUS_3A="fbfda7e87dc0f650f013e6082d1f886df888b6c9";

  static void loginRole(Connection c,String name,String password)throws SQLException{
    try(var format=c.prepareStatement("SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',?,?)")){
      format.setString(1,name);format.setString(2,password);
      try(var result=format.executeQuery();var statement=c.createStatement()){assertTrue(result.next());statement.execute(result.getString(1));}
    }
  }

  @BeforeAll static void start()throws Exception{
    ownerPassword=UUID.randomUUID().toString()+UUID.randomUUID();runtimePassword=UUID.randomUUID().toString()+UUID.randomUUID();
    network=Network.newNetwork();
    postgres=new PostgreSQLContainer("postgres:17-alpine").withPassword(UUID.randomUUID().toString())
        .withNetwork(network).withNetworkAliases("postgres");postgres.start();
    try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var statement=connection.createStatement()){
      loginRole(connection,OWNER,ownerPassword);loginRole(connection,APP,runtimePassword);
      statement.execute("CREATE DATABASE agrosphere_operations OWNER agrosphere_operations");
      statement.execute("REVOKE ALL ON DATABASE agrosphere_operations FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE agrosphere_operations TO agrosphere_operations_app");
      statement.execute("CREATE DATABASE credential_foreign");statement.execute("REVOKE ALL ON DATABASE credential_foreign FROM PUBLIC");
    }
    jdbcUrl="jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/agrosphere_operations";
    context=SpringApplication.run(Application.class,"--server.port=0","--spring.datasource.url="+jdbcUrl,
        "--spring.datasource.username="+APP,"--spring.datasource.password="+runtimePassword,
        "--spring.flyway.user="+OWNER,"--spring.flyway.password="+ownerPassword,
        "--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:9/unused",
        "--OIDC_JWK_SET_URI=http://127.0.0.1:9/jwks","--operations.outbox.enabled=false",
        "--spring.rabbitmq.username=SIMULATED","--spring.rabbitmq.password=SIMULATED-UNUSED",
        "--management.otlp.metrics.export.enabled=false","--management.tracing.export.otlp.enabled=false",
        "--logging.level.root=WARN");
    runtime=context.getBean(JdbcTemplate.class);repository=context.getBean(CollectionRepository.class);
    tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  @AfterAll static void stop(){
    if(context!=null)context.close();if(postgres!=null)postgres.stop();if(network!=null)network.close();
  }

  @Test @Order(1) void actualRuntimeAndMigrationConnectionsHaveDistinctUsersAndPrivileges()throws Exception{
    assertEquals(APP,runtime.queryForObject("SELECT current_user",String.class));
    assertEquals(OWNER,runtime.queryForObject("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='latex_collection'::regclass",String.class));
    try(var connection=context.getBean(Flyway.class).getConfiguration().getDataSource().getConnection();var statement=connection.createStatement();var result=statement.executeQuery("SELECT current_user")){
      assertTrue(result.next());assertEquals(OWNER,result.getString(1));
    }
    assertThrows(SQLException.class,()->DriverManager.getConnection(jdbcUrl,APP,ownerPassword));
    assertThrows(SQLException.class,()->DriverManager.getConnection(jdbcUrl.replace("/agrosphere_operations","/credential_foreign"),APP,runtimePassword));
    assertThrows(RuntimeException.class,()->runtime.execute("CREATE TABLE forbidden(id int)"));
    assertThrows(RuntimeException.class,()->runtime.update("UPDATE collection_revision SET recorded_by='forged'"));
    assertThrows(RuntimeException.class,()->runtime.execute("TRUNCATE collection_revision"));
  }

  @Test @Order(2) void actualRuntimeLoginPersistsCollectionAndEvidenceAtomically(){
    var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var started=now.minusSeconds(120);var completed=now.minusSeconds(60);
    var estate=UUID.randomUUID();var block=UUID.randomUUID();var assignment=UUID.randomUUID();var record=UUID.randomUUID();var date=now.atZone(ZoneOffset.UTC).toLocalDate();
    runtime.update("INSERT INTO tapping_assignment(id,estate_id,block_id,worker_id,tapping_date,planned_tree_count,status,created_at,updated_at,version,created_by,updated_by) VALUES (?,?,?,'worker',?,10,'COMPLETED',?,?,2,'actor','actor')",assignment,estate,block,date,Timestamp.from(started),Timestamp.from(completed));
    runtime.update("INSERT INTO tapping_record(id,assignment_id,worker_id,estate_id,block_id,tapping_date,planned_tree_count,actual_tree_count,started_at,completed_at,created_by,created_at,updated_at) VALUES (?,?,'worker',?,?,?,10,8,?,?,'actor',?,?)",record,assignment,estate,block,date,Timestamp.from(started),Timestamp.from(completed),Timestamp.from(started),Timestamp.from(completed));
    var a=new TappingAssignment(assignment,null,estate,block,"worker",date,10,Rules.AssignmentStatus.COMPLETED,started,completed,2,"actor","actor");
    var r=new TappingRecord(record,assignment,"worker",estate,block,date,10,8,started,completed,null,"actor",started,completed);
    var c=LatexCollection.create(UUID.randomUUID(),r,a,new MasterSnapshot(estate,block,UUID.randomUUID(),"UTC",0,0,now),"CREDENTIAL-PROOF",
        new WeightObservation(now,new BigDecimal("1.000"),new WeightEvidence(WeightMethod.SCALE,"SIMULATED",null),null),Provenance.SIMULATED,null,Clock.fixed(now,ZoneOffset.UTC),"actor");
    tx.executeWithoutResult(s->{repository.insert(c,new CollectionScope(List.of(estate),null));repository.append(new CollectionRevision(c.id(),0,CommandKind.CREATE,c,now,"actor",UUID.randomUUID()),new CollectionScope(List.of(estate),null));});
    assertEquals(c,repository.find(c.id(),new CollectionScope(List.of(estate),null)).orElseThrow());
  }

  @Test @Order(3) void previous3aImageRunsWithRestrictedLoginAgainstV3WithoutDeletingEvidence()throws Exception{
    Path directory=Files.createTempDirectory(Path.of("target"),"previous-3a-").toAbsolutePath().normalize();
    var archive=new ProcessBuilder("git","archive","--format=zip",PREVIOUS_3A,"Dockerfile",".dockerignore",".mvn","mvnw","pom.xml","src")
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try(var zip=new ZipInputStream(archive.getInputStream())){
      java.util.zip.ZipEntry entry;
      while((entry=zip.getNextEntry())!=null){var destination=directory.resolve(entry.getName()).normalize();
        assertTrue(destination.startsWith(directory),"Archive must stay in isolated build context");
        if(entry.isDirectory())Files.createDirectories(destination);else{Files.createDirectories(destination.getParent());Files.copy(zip,destination);}}
    }
    assertEquals(0,archive.waitFor(),"Pinned previous 3A commit must be available; CI checkout requires history");
    var before=runtime.queryForObject("SELECT count(*) FROM latex_collection",Long.class);
    assertTrue(before>0,"Retained collection evidence must exist before the rollback drill");
    var key=new RSAKeyGenerator(2048).keyID("rollback-proof").generate();
    var oidc=HttpServer.create(new InetSocketAddress("0.0.0.0",0),0);
    oidc.createContext("/jwks",e->{byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});oidc.start();
    try{
      Testcontainers.exposeHostPorts(oidc.getAddress().getPort());
      var issuer="http://host.testcontainers.internal:"+oidc.getAddress().getPort();
      var image=new ImageFromDockerfile("agrosphere-3a-rollback:"+UUID.randomUUID().toString().replace("-",""),true).withFileFromPath(".",directory);
      try(var previous=new GenericContainer<>(image).withNetwork(network).withExposedPorts(8083)
          .withEnv("DB_URL","jdbc:postgresql://postgres:5432/agrosphere_operations").withEnv("DB_USER",APP).withEnv("DB_PASSWORD",runtimePassword)
          .withEnv("OIDC_ISSUER_URI",issuer).withEnv("OIDC_JWK_SET_URI",issuer+"/jwks")
          .withEnv("RABBITMQ_USER","SIMULATED").withEnv("RABBITMQ_PASSWORD","SIMULATED-UNUSED")
          .withEnv("OUTBOX_PUBLICATION_ENABLED","false").withEnv("SPRING_FLYWAY_ENABLED","false")
          .withEnv("LOGGING_LEVEL_ROOT","WARN").waitingFor(Wait.forHttp("/actuator/health/readiness").forStatusCode(200)).withStartupTimeout(Duration.ofMinutes(2))){
        previous.start();
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject("rollback-admin").audience("operations-service")
            .issueTime(java.util.Date.from(Instant.now())).expirationTime(java.util.Date.from(Instant.now().plusSeconds(3600)))
            .claim("realm_access",Map.of("roles",List.of("PLATFORM_ADMIN"))).build();
        var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);token.sign(new RSASSASigner(key));
        var request=HttpRequest.newBuilder(URI.create("http://"+previous.getHost()+":"+previous.getMappedPort(8083)+"/api/v1/tapping/summary"))
            .header("Authorization","Bearer "+token.serialize()).GET().build();
        assertEquals(200,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(before,runtime.queryForObject("SELECT count(*) FROM latex_collection",Long.class));
        assertEquals(3,runtime.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('collection_revision','drc_measurement','collection_command')",Integer.class));
      }
    }finally{oidc.stop(0);}
  }
}
