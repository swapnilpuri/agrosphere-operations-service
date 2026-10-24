# agrosphere-operations-service

Rubber tapping, latex collection, DRC, production calculations, inspections and crop-health incidents.

This is an independent project and Git root. Version: 0.1.0; use independent SemVer tags and CHANGELOG.md. License decision: LICENSE.md (all rights reserved pending selection).

See AGENTS.md for scope and engineering rules. This scaffold provides no business APIs or production features. Architecture lives in the sibling agrosphere-architecture repository; versioned API/event definitions live in agrosphere-contracts. Do not add path dependencies to sibling projects.

## Build and test

Requires JDK 25. Run ./mvnw verify (Windows: .\mvnw.cmd verify). No infrastructure is needed for the security policy tests. Four tests verify public health, unauthenticated denial, authenticated access and invalid-token rejection using test-only routes and a test decoder. Real issuer/audience, database and Testcontainers integration tests arrive with the first use case.

Copy .env.example to .env and supply private values when runtime work begins. Spring does not load .env automatically: export variables or use Compose. Run ./mvnw spring-boot:run only after database and OIDC configuration. Only Actuator health is public.

Packages reserve clean domain, application, ports, web/messaging and persistence adapters. V1 creates outbox and idempotency infrastructure only; no handlers or relays are implemented. Flyway owns DDL; JPA validates schemas.

Spring Boot 4.0.9 / Java 25. JUnit Jupiter 5.14.4 overrides the Boot 4 JUnit 6 default to retain the requested JUnit 5 baseline. CI verifies this combination. Boot manages other dependencies except those explicitly pinned in pom.xml.

JUnit compatibility: Spring 7 SpringExtension calls JUnit 6-only APIs. Foundation security tests use JUnit 5 with an explicitly managed Spring web context instead. Do not introduce @SpringBootTest/SpringExtension until the JUnit baseline decision is revisited or a supported JUnit 5 integration approach is tested. These tests use a mock decoder and do not verify live OIDC claims.
