package com.agrosphere.operations;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class SecurityPolicyTest {
  AnnotationConfigWebApplicationContext context;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(TestConfig.class);
    context.refresh();
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  void healthIsPublic() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void unauthenticatedRequestsAreDenied() throws Exception {
    mvc.perform(get("/test/protected")).andExpect(status().isUnauthorized());
  }

  @Test
  void authenticatedRequestReachesProtectedRoute() throws Exception {
    mvc.perform(get("/test/protected").with(jwt())).andExpect(status().isOk());
  }

  @Test
  void invalidBearerTokenIsRejected() throws Exception {
    mvc.perform(get("/test/protected").header("Authorization", "Bearer unsigned"))
        .andExpect(status().isUnauthorized());
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({SecurityConfiguration.class, TestRoutes.class})
  static class TestConfig {
    @Bean
    JwtDecoder decoder() {
      return token -> {
        throw new BadJwtException("Test decoder rejects unsigned tokens");
      };
    }
  }

  @RestController
  static class TestRoutes {
    @GetMapping({"/actuator/health", "/test/protected"})
    String ok() {
      return "OK";
    }
  }
}
