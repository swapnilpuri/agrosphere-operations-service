package com.agrosphere.operations.workforce.api;

import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

/** Advertise pinned contract shapes only for actual registered workforce routes. */
@Configuration
public class WorkforceOpenApi {
  @Bean OpenApiCustomizer workforceDocumentation(){return api->{
    try(var input=getClass().getResourceAsStream("/contracts/operations-service.yaml")){
      var contract=io.swagger.v3.core.util.Json31.mapper().readValue(input,OpenAPI.class);
      for(var entry:contract.getPaths().entrySet())if(entry.getKey().startsWith("/api/v1/workers")||entry.getKey().startsWith("/api/v1/attendance")){
        var actual=api.getPaths().get(entry.getKey());if(actual==null||!actual.readOperationsMap().keySet().equals(entry.getValue().readOperationsMap().keySet()))
          throw new IllegalStateException("Unregistered workforce contract operation");
        api.getPaths().addPathItem(entry.getKey(),entry.getValue());
      }
      contract.getComponents().getSchemas().forEach((name,schema)->{if(name.startsWith("Worker")||name.startsWith("Attendance")||name.startsWith("Workforce"))api.getComponents().addSchemas(name,schema);});
    }catch(java.io.IOException e){throw new IllegalStateException("Pinned workforce contract unavailable",e);}
  };}
}
