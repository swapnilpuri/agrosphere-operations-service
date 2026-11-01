package com.agrosphere.operations.alerts.api;

import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class AlertOpenApi {
  @Bean public OpenApiCustomizer alertDocumentation(){return api->{
    try(var input=getClass().getResourceAsStream("/contracts/operations-service.yaml")){
      var contract=io.swagger.v3.core.util.Json31.mapper().readValue(input,OpenAPI.class);
      for(var entry:contract.getPaths().entrySet())if(entry.getKey().matches("/api/v[12]/alerts.*")){
        var actual=api.getPaths().get(entry.getKey());if(actual==null||!actual.readOperationsMap().keySet().equals(entry.getValue().readOperationsMap().keySet()))throw new IllegalStateException("Unregistered alert contract operation");api.getPaths().addPathItem(entry.getKey(),entry.getValue());
      }
      contract.getComponents().getSchemas().forEach((name,schema)->{if(name.startsWith("AN"))api.getComponents().addSchemas(name,schema);});
    }catch(java.io.IOException e){throw new IllegalStateException("Pinned alert contract unavailable",e);}
  };}
}
