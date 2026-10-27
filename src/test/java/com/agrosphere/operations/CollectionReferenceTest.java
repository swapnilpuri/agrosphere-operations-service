package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;

import com.agrosphere.operations.application.Access;
import com.agrosphere.operations.infrastructure.integration.EstateHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class CollectionReferenceTest {
  HttpServer master;
  EstateHttpClient client;
  UUID estate=UUID.randomUUID(),block=UUID.randomUUID(),division=UUID.randomUUID();
  Instant now=Instant.parse("2026-10-27T18:00:00Z");
  volatile Map<String,Object> estateBody,blockBody;
  volatile int responseStatus=200;
  volatile boolean malformedJson;
  volatile String redirect;
  volatile CountDownLatch hold;
  AtomicInteger delegated=new AtomicInteger();

  @BeforeEach void setup() throws Exception {
    estateBody=Map.of("id",estate,"status","ACTIVE","timezone","Asia/Colombo","version",3);
    blockBody=Map.of("id",block,"estateId",estate,"divisionId",division,"status","ACTIVE","cropType","RUBBER","version",4);
    var jwt=Jwt.withTokenValue("SIMULATED-DELEGATED-TOKEN").header("alg","RS256").subject("actor").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    master=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    master.createContext("/api/v1",exchange->{
      if("Bearer SIMULATED-DELEGATED-TOKEN".equals(exchange.getRequestHeaders().getFirst("Authorization"))
          && exchange.getRequestHeaders().getFirst("X-Correlation-ID")!=null)delegated.incrementAndGet();
      try {if(hold!=null)hold.await(3,TimeUnit.SECONDS);} catch(InterruptedException ex){Thread.currentThread().interrupt();}
      byte[] body=malformedJson?new byte[]{123}:new ObjectMapper().writeValueAsBytes(exchange.getRequestURI().getPath().contains("/blocks/")?blockBody:estateBody);
      exchange.getResponseHeaders().set("Content-Type","application/json");
      if(redirect!=null)exchange.getResponseHeaders().set("Location",redirect);
      exchange.sendResponseHeaders(responseStatus,body.length);
      try{exchange.getResponseBody().write(body);}finally{exchange.close();}
    });
    master.start();
    client=new EstateHttpClient(new Access(),"http://127.0.0.1:"+master.getAddress().getPort(),1,Clock.fixed(now,ZoneOffset.UTC));
  }

  @AfterEach void close(){if(hold!=null)hold.countDown();if(master!=null)master.stop(0);SecurityContextHolder.clearContext();}

  void estateField(String name,Object value){var m=new HashMap<>(estateBody);if(value==null)m.remove(name);else m.put(name,value);estateBody=m;}
  void blockField(String name,Object value){var m=new HashMap<>(blockBody);if(value==null)m.remove(name);else m.put(name,value);blockBody=m;}
  void status(int expected){assertEquals(expected,assertThrows(ResponseStatusException.class,()->client.validateCollection(estate,block)).getStatusCode().value());}

  @Test void canonicalTypedSnapshotAndLegacyZoneBothWork() {
    var snapshot=client.validateCollection(estate,block);
    assertEquals(division,snapshot.divisionId());assertEquals(3,snapshot.estateVersion());assertEquals(4,snapshot.blockVersion());
    assertEquals(now,snapshot.validatedAt());assertEquals("Asia/Colombo",snapshot.estateTimezone());
    assertEquals("Asia/Colombo",client.validate(estate,block));assertEquals(4,delegated.get());
  }

  @Test void missingNewMetadataDoesNotChangeLegacy3aPort() {
    blockField("divisionId",null);estateField("version",null);
    assertEquals("Asia/Colombo",client.validate(estate,block));status(503);
  }

  @Test void missingMalformedOrOutOfRangeMetadataIs503() {
    for(Object invalid:Arrays.asList(null,"invalid",123)) {blockField("divisionId",invalid);status(503);}
    blockField("divisionId",division);
    for(Object invalid:Arrays.asList(null,-1,1.25,"3",new java.math.BigInteger("9223372036854775808"))) {estateField("version",invalid);status(503);}
    estateField("version",3);
    estateField("timezone","invalid/zone");status(503);
    estateField("timezone","UTC");blockField("id","invalid");status(503);
  }

  @Test void invalidCanonicalOwnershipInactiveOrNonRubberIs422() {
    blockField("estateId",UUID.randomUUID());status(422);blockField("estateId",estate);
    estateField("status","INACTIVE");status(422);estateField("status","ACTIVE");
    blockField("cropType","TEA");status(422);blockField("cropType","RUBBER");
    blockField("id",UUID.randomUUID());status(422);
  }

  @Test void dependencyStatusAndMalformedPayloadFailClosed() {
    for(int code:List.of(401,403)){responseStatus=code;status(403);}
    responseStatus=404;status(422);
    for(int code:List.of(500,429)){responseStatus=code;status(503);}
    responseStatus=200;malformedJson=true;status(503);
  }

  @Test void redirectsNeverForwardTheBearerToAnotherHost() throws Exception {
    AtomicInteger reached=new AtomicInteger();var other=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    other.createContext("/",e->{reached.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});other.start();
    try {responseStatus=302;redirect="http://127.0.0.1:"+other.getAddress().getPort()+"/";status(503);assertEquals(0,reached.get());}
    finally{other.stop(0);}
  }

  @Test void boundedTimeoutAndUnavailableMasterDoNotInventMetadata() {
    hold=new CountDownLatch(1);status(503);hold.countDown();hold=null;
    master.stop(0);master=null;status(503);
  }
}
