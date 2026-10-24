package com.agrosphere.operations.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
  @Bean
  OpenAPI operationsApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("AgroSphere Rubber Tapping Operations")
                .version("0.2.0")
                .description(
                    "Milestone 3A. UTC audit instants; estate-local inclusive tapping dates."
                        + " Mutations require version; identical terminal retries are no-ops."
                        + " Generation recurrence: DAILY or EVERY_N_DAYS:n. Pages 0-based, sizes"
                        + " 1..100. See ADR 0012 for authorization and retry policies."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
  }
}
