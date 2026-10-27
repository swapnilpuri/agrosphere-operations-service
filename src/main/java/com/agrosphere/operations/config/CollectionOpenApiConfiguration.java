package com.agrosphere.operations.config;

import com.agrosphere.operations.application.CollectionCommands;
import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

/** Contract-first documentation, bound only to actually registered collection routes.
 * Runtime request/response compliance is separately tested against these schemas. */
@Configuration
public class CollectionOpenApiConfiguration {
  @Bean OpenApiCustomizer collectionContractDocumentation(){
    return api->{
      try(var input=getClass().getResourceAsStream("/contracts/operations-service.yaml")){
        var contract=io.swagger.v3.core.util.Json31.mapper().readValue(input,OpenAPI.class);
        for(var entry:contract.getPaths().entrySet())if(entry.getKey().startsWith(CollectionCommands.BASE)){
          var actual=api.getPaths().get(entry.getKey());
          if(actual==null||!actual.readOperationsMap().keySet().equals(entry.getValue().readOperationsMap().keySet()))
            throw new IllegalStateException("Contract documentation cannot advertise an unregistered collection operation");
          api.getPaths().addPathItem(entry.getKey(),entry.getValue());
        }
        contract.getComponents().getSchemas().forEach((name,schema)->{if(name.startsWith("Collection"))api.getComponents().addSchemas(name,schema);});
      }catch(java.io.IOException ex){throw new IllegalStateException("Pinned collection contract unavailable",ex);}
    };
  }
}
