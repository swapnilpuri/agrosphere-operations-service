package com.agrosphere.operations.api;

import com.agrosphere.operations.application.*;
import com.agrosphere.operations.application.CollectionReplayStore.Receipt;
import io.swagger.v3.oas.annotations.Operation;
import java.util.*;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping(CollectionCommands.BASE)
public class LatexCollectionController {
  private final CollectionService service;
  private final CollectionQueryService query;
  private final ObjectMapper json=new ObjectMapper();
  public LatexCollectionController(CollectionService service,CollectionQueryService query){this.service=service;this.query=query;}
  private ResponseEntity<String> result(Receipt receipt){var builder=ResponseEntity.status(receipt.status()).contentType(MediaType.APPLICATION_JSON);if(receipt.location()!=null)builder.header("Location",receipt.location());return builder.body(receipt.body());}
  private ResponseEntity<String> result(String value){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(value);}
  @Operation(operationId="createLatexCollection") @PostMapping(consumes="application/json")
  public ResponseEntity<String> create(@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String raw){return result(service.mutate("CREATE",null,key,raw));}
  @Operation(operationId="listLatexCollections") @GetMapping
  public ResponseEntity<String> list(@RequestParam MultiValueMap<String,String> filters){return result(query.list(filters));}
  @Operation(operationId="summarizeLatexCollections") @GetMapping("/summary")
  public ResponseEntity<String> summary(@RequestParam MultiValueMap<String,String> filters){return result(query.summary(filters));}
  @Operation(operationId="getLatexCollection") @GetMapping("/{id}")
  public ResponseEntity<String> get(@PathVariable String id){return result(json.writeValueAsString(CollectionSnapshots.view(service.read(CollectionCommands.uuid(id)))));}
  @Operation(operationId="getLatexCollectionHistory") @GetMapping("/{id}/history")
  public ResponseEntity<String> history(@PathVariable String id,@RequestParam MultiValueMap<String,String> filters){return result(query.history(CollectionCommands.uuid(id),filters));}
  @Operation(operationId="listDrcMeasurements") @GetMapping("/{id}/drc-measurements")
  public ResponseEntity<String> measurements(@PathVariable String id,@RequestParam MultiValueMap<String,String> filters){return result(query.measurements(CollectionCommands.uuid(id),filters));}
  @Operation(operationId="recordDrcMeasurement") @PostMapping(value="/{id}/drc-measurements",consumes="application/json")
  public ResponseEntity<String> drc(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String raw){return result(service.mutate("DRC",CollectionCommands.uuid(id),key,raw));}
  @Operation(operationId="correctLatexCollection") @PostMapping(value="/{id}/corrections",consumes="application/json")
  public ResponseEntity<String> correct(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String raw){return result(service.mutate("CORRECT",CollectionCommands.uuid(id),key,raw));}
  @Operation(operationId="voidLatexCollection") @PostMapping(value="/{id}/void",consumes="application/json")
  public ResponseEntity<String> voidCollection(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String raw){return result(service.mutate("VOID",CollectionCommands.uuid(id),key,raw));}
}
