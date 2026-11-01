package com.agrosphere.operations.crophealth.api;

import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class CropHealthOpenApi {
  @Bean public OpenApiCustomizer cropHealthDocumentation(){return api->{
    try(var input=getClass().getResourceAsStream("/contracts/operations-service.yaml")){
      var contract=io.swagger.v3.core.util.Json31.mapper().readValue(input,OpenAPI.class);
      // Swagger Schema.setRequired filters out sibling-defined properties during parsing.
      // JSON Schema permits conditional required keys defined by the enclosing object.
      // Restore this accepted constraint without changing any pinned contract bytes.
      var conditional=contract.getComponents().getSchemas().get("CHInspectionRecord").getOneOf();
      ((io.swagger.v3.oas.models.media.Schema)conditional.get(1)).addRequiredItem("incident");
      for(var entry:contract.getPaths().entrySet())if(entry.getKey().startsWith("/api/v1/inspections")||entry.getKey().startsWith("/api/v1/incidents")){
        var actual=api.getPaths().get(entry.getKey());if(actual==null||!actual.readOperationsMap().keySet().equals(entry.getValue().readOperationsMap().keySet()))throw new IllegalStateException("Unregistered crop-health contract operation");
        api.getPaths().addPathItem(entry.getKey(),entry.getValue());
      }
      contract.getComponents().getSchemas().forEach((name,schema)->{if(name.startsWith("CH"))api.getComponents().addSchemas(name,schema);});
    }catch(java.io.IOException e){throw new IllegalStateException("Pinned crop-health contract unavailable",e);}
  };}
}
