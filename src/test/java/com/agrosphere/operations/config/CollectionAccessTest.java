package com.agrosphere.operations.config;

import static org.junit.jupiter.api.Assertions.*;

import com.agrosphere.operations.application.*;
import com.agrosphere.operations.application.CollectionAccess.Operation;
import com.agrosphere.operations.config.JwtValidationConfiguration;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CollectionAccessTest {
  static RSAKey key;
  static HttpServer oidc;
  static String issuer;
  static JwtDecoder decoder;
  final UUID estate=UUID.randomUUID();
  final CollectionAccess access=new CollectionAccess(new Access());

  @BeforeAll static void setup() throws Exception {
    key=new RSAKeyGenerator(2048).keyID("collection-test").generate();
    oidc=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    issuer="http://127.0.0.1:"+oidc.getAddress().getPort();
    oidc.createContext("/jwks",e->{
      byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);
      e.getResponseBody().write(body);e.close();
    });
    oidc.start();
    decoder=new JwtValidationConfiguration().jwtDecoder(issuer,"operations-service",issuer+"/jwks");
  }

  @AfterAll static void stop(){if(oidc!=null)oidc.stop(0);}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  String token(List<String> roles,Object estates,String sub,String iss,String audience,Instant expiry,RSAKey signingKey) throws Exception {
    var claims=new JWTClaimsSet.Builder().issuer(iss).subject(sub).audience(audience)
        .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(expiry))
        .claim("realm_access",Map.of("roles",roles));
    if(estates!=null)claims.claim("estate_ids",estates);
    var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims.build());
    jwt.sign(new RSASSASigner(signingKey));return jwt.serialize();
  }

  void login(List<String> roles,Object estates,String sub) throws Exception {
    var signed=token(roles,estates,sub,issuer,"operations-service",Instant.now().plusSeconds(3600),key);
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(signed)));
  }

  @Test void everyRoleAndOperationUsesPositiveWritePermissions() throws Exception {
    for(String role:List.of("PLATFORM_ADMIN","PLANTATION_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER","FIELD_SUPERVISOR","FIELD_WORKER","AUDITOR","VIEWER")) {
      login(List.of(role),List.of(estate.toString()),"worker");
      for(var operation:Operation.values()) {
        boolean manager=Set.of("PLATFORM_ADMIN","PLANTATION_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER").contains(role);
        boolean supervisor=manager||role.equals("FIELD_SUPERVISOR");
        boolean allowed=switch(operation){case READ->true;case CREATE->supervisor||role.equals("FIELD_WORKER");case DRC->supervisor;default->manager;};
        if(allowed)assertDoesNotThrow(()->access.authorize(operation,estate,"worker"),role+" "+operation);
        else assertThrows(AccessDeniedException.class,()->access.authorize(operation,estate,"worker"),role+" "+operation);
      }
    }
  }

  @Test void mixedReadRolesDoNotBroadenWorkerWrites() throws Exception {
    for(String readRole:List.of("AUDITOR","VIEWER")) {
      login(List.of("FIELD_WORKER",readRole),List.of(estate.toString()),"worker");
      access.authorize(Operation.CREATE,estate,"worker");
      access.authorize(Operation.READ,estate,"another-worker");
      assertThrows(AccessDeniedException.class,()->access.authorize(Operation.CREATE,estate,"another-worker"));
      assertThrows(AccessDeniedException.class,()->access.authorize(Operation.DRC,estate,"worker"));
      assertThrows(AccessDeniedException.class,()->access.authorize(Operation.CORRECT,estate,"worker"));
    }
  }

  @Test void scopeAppliesBeforeOmittedFilterQueriesAndRejectsExplicitForeignFilters() throws Exception {
    login(List.of("FIELD_WORKER"),List.of(estate.toString()),"worker");
    var scope=access.readScope(null,null);
    assertEquals(List.of(estate),scope.estateIds());assertEquals("worker",scope.workerId());
    assertThrows(AccessDeniedException.class,()->access.readScope(null,"other"));
    assertThrows(AccessDeniedException.class,()->access.readScope(UUID.randomUUID(),null));
    assertThrows(AccessDeniedException.class,()->access.authorize(Operation.READ,estate,"other"));
    assertThrows(UnsupportedOperationException.class,()->scope.estateIds().add(UUID.randomUUID()));
  }

  @Test void globalAndScopedAdminsRemainDistinct() throws Exception {
    login(List.of("PLATFORM_ADMIN"),null,"admin");
    assertNull(access.readScope(null,null).estateIds());
    access.authorize(Operation.VOID,UUID.randomUUID(),"other");
    login(List.of("ESTATE_ADMIN"),List.of(estate.toString()),"admin");
    assertThrows(AccessDeniedException.class,()->access.authorize(Operation.VOID,UUID.randomUUID(),"other"));
  }

  @Test void malformedOrMissingTrustedScopesAndSubjectFailClosed() throws Exception {
    for(Object claim:Arrays.asList(null,"not-a-list",List.of("invalid"),Arrays.asList((String)null),List.of(123))) {
      login(List.of("ESTATE_MANAGER"),claim,"manager");
      assertThrows(AccessDeniedException.class,()->access.readScope(null,null));
    }
    login(List.of("ESTATE_MANAGER"),List.of(estate.toString()),null);
    assertThrows(AccessDeniedException.class,()->access.readScope(null,null));
    login(List.of("unknown"),List.of(estate.toString()),"actor");
    assertThrows(AccessDeniedException.class,()->access.authorize(Operation.READ,estate,"actor"));
  }

  @Test void revokedEstateCannotAuthorizeAStoredReceiptAgain() throws Exception {
    login(List.of("ESTATE_MANAGER"),List.of(estate.toString()),"manager");
    access.authorize(Operation.CORRECT,estate,"worker");
    login(List.of("ESTATE_MANAGER"),List.of(),"manager");
    assertThrows(AccessDeniedException.class,()->access.authorize(Operation.CORRECT,estate,"worker"));
    assertEquals(List.of(),access.readScope(null,null).estateIds());
  }

  @Test void actualProductionDecoderRejectsIssuerAudienceLifetimeAndSignatureErrors() throws Exception {
    var roles=List.of("ESTATE_MANAGER");var estates=List.of(estate.toString());
    var future=Instant.now().plusSeconds(3600);
    for(String invalid:List.of(
        token(roles,estates,"actor",issuer+"/wrong","operations-service",future,key),
        token(roles,estates,"actor",issuer,"wrong-audience",future,key),
        token(roles,estates,"actor",issuer,"operations-service",Instant.now().minusSeconds(120),key),
        token(roles,estates,"actor",issuer,"operations-service",future,new RSAKeyGenerator(2048).generate())))
      assertThrows(JwtException.class,()->decoder.decode(invalid));
  }
}
