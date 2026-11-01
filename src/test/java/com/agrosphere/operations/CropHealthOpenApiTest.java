package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;
import com.agrosphere.operations.crophealth.api.CropHealthOpenApi;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

class CropHealthOpenApiTest {
  @Test void acceptedConditionalRequiredSurvivesSwaggerSerialization()throws Exception {
    OpenAPI api;
    try(var stream=getClass().getResourceAsStream("/contracts/operations-service.yaml")) {
      api=io.swagger.v3.core.util.Json31.mapper().readValue(stream,OpenAPI.class);
    }
    new CropHealthOpenApi().cropHealthDocumentation().customise(api);
    var actual=io.swagger.v3.core.util.Json31.mapper().readTree(io.swagger.v3.core.util.Json31.mapper().writeValueAsString(api));
    assertEquals("incident",actual.path("components").path("schemas").path("CHInspectionRecord").path("oneOf").get(1).path("required").get(0).asText());
  }
}
