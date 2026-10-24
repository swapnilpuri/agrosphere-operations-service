package com.agrosphere.operations.infrastructure.integration;

import com.agrosphere.operations.application.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

@Component
public class EstateHttpClient implements EstateValidation {
  private final HttpClient client;
  private final URI base;
  private final Duration timeout;
  private final Access access;
  private final ObjectMapper json = new ObjectMapper();

  public EstateHttpClient(
      Access access,
      @Value("${operations.estate-url:http://localhost:8081}") String url,
      @Value("${operations.estate-timeout-seconds:3}") long seconds) {
    this.access = access;
    base = URI.create(url);
    if (!java.util.Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || seconds < 1
        || seconds > 30) throw new IllegalArgumentException("Invalid Estate client configuration");
    timeout = Duration.ofSeconds(seconds);
    client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  private JsonNode get(String path) {
    try {
      var request =
          HttpRequest.newBuilder(base.resolve(path))
              .timeout(timeout)
              .header(
                  "Authorization", "Bearer " + access.authentication().getToken().getTokenValue())
              .header(
                  "X-Correlation-ID",
                  org.slf4j.MDC.get("correlationId") == null
                      ? UUID.randomUUID().toString()
                      : org.slf4j.MDC.get("correlationId"))
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404)
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "Unknown estate or block");
      if (response.statusCode() == 403 || response.statusCode() == 401)
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Estate validation denied");
      if (response.statusCode() != 200)
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Estate dependency unavailable");
      return json.readTree(response.body());
    } catch (java.io.IOException | tools.jackson.core.JacksonException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Estate dependency unavailable");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Estate dependency interrupted");
    }
  }

  public String validate(UUID estate, UUID block) {
    var e = get("/api/v1/estates/" + estate);
    var b = get("/api/v1/blocks/" + block);
    if (!estate.toString().equals(e.path("id").asText())
        || !block.toString().equals(b.path("id").asText())
        || !estate.toString().equals(b.path("estateId").asText())
        || !"ACTIVE".equals(e.path("status").asText())
        || !"ACTIVE".equals(b.path("status").asText())
        || !"RUBBER".equals(b.path("cropType").asText()))
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Active rubber block must belong to active estate");
    try {
      return ZoneId.of(e.path("timezone").asText()).getId();
    } catch (DateTimeException ex) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Invalid canonical estate timezone");
    }
  }
}
