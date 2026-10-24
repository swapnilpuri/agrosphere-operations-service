package com.agrosphere.operations;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint((r, s, e) -> write(s, 401))
                    .accessDeniedHandler((r, s, e) -> write(s, 403)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((r, s, x) -> write(s, 401))
                    .accessDeniedHandler((r, s, x) -> write(s, 403)))
        .build();
  }

  private static void write(jakarta.servlet.http.HttpServletResponse response, int code)
      throws java.io.IOException {
    response.setStatus(code);
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            "{\"status\":"
                + code
                + ",\"title\":\""
                + (code == 401 ? "Unauthorized" : "Forbidden")
                + "\",\"correlationId\":\""
                + java.util.Objects.toString(org.slf4j.MDC.get("correlationId"), "")
                + "\"}");
  }
}
