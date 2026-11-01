package com.agrosphere.operations.crophealth.api;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.crophealth.application.*;
import com.agrosphere.operations.crophealth.application.CropHealthPorts.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class CropHealthController {
  private final CropHealthService service;
  public CropHealthController(CropHealthService service){this.service=service;}
  private ResponseEntity<String> response(Receipt r) {
    var builder=ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON);if(r.location()!=null)builder.header("Location",r.location());return builder.body(r.body());
  }
  @PostMapping("/api/v1/inspections") public ResponseEntity<String> recordInspection(@RequestBody String body,@RequestHeader("Idempotency-Key") String key){return response(service.create(true,body,key));}
  @PostMapping("/api/v1/incidents") public ResponseEntity<String> reportIncident(@RequestBody String body,@RequestHeader("Idempotency-Key") String key){return response(service.create(false,body,key));}
  @GetMapping("/api/v1/inspections/{id}") public Object getInspection(@PathVariable String id){return service.get(true,CropHealthInput.uuid(id));}
  @GetMapping("/api/v1/incidents/{id}") public Object getIncident(@PathVariable String id){return service.get(false,CropHealthInput.uuid(id));}
  @GetMapping("/api/v1/inspections/{id}/history") public Object inspectionHistory(@PathVariable String id,@RequestParam MultiValueMap<String,String> query){check(query,Set.of("page","size"));return service.history(true,CropHealthInput.uuid(id),integer(query,"page",0),integer(query,"size",25));}
  @GetMapping("/api/v1/incidents/{id}/history") public Object incidentHistory(@PathVariable String id,@RequestParam MultiValueMap<String,String> query){check(query,Set.of("page","size"));return service.history(false,CropHealthInput.uuid(id),integer(query,"page",0),integer(query,"size",25));}
  @PostMapping("/api/v1/inspections/{id}/corrections") public ResponseEntity<String> correctInspection(@PathVariable String id,@RequestBody String body,@RequestHeader("Idempotency-Key") String key){return response(service.change(true,CropHealthInput.uuid(id),"corrections",body,key));}
  @PostMapping("/api/v1/inspections/{id}/void") public ResponseEntity<String> voidInspection(@PathVariable String id,@RequestBody String body,@RequestHeader("Idempotency-Key") String key){return response(service.change(true,CropHealthInput.uuid(id),"void",body,key));}
  @PostMapping("/api/v1/incidents/{id}/amendments") public ResponseEntity<String> amendIncident(@PathVariable String id,@RequestBody String body,@RequestHeader("Idempotency-Key") String key){return response(service.change(false,CropHealthInput.uuid(id),"amendments",body,key));}
  @PostMapping("/api/v1/incidents/{id}/corrections") public ResponseEntity<String> correctIncident(@PathVariable String id,@RequestBody String body,@RequestHeader("Idempotency-Key") String key){return response(service.change(false,CropHealthInput.uuid(id),"corrections",body,key));}
  @PostMapping("/api/v1/incidents/{id}/triage") public ResponseEntity<String> triageIncident(@PathVariable String id,@RequestBody String body,@RequestHeader("Idempotency-Key") String key){return response(service.change(false,CropHealthInput.uuid(id),"triage",body,key));}
  @PostMapping("/api/v1/incidents/{id}/resolve") public ResponseEntity<String> resolveIncident(@PathVariable String id,@RequestBody String body,@RequestHeader("Idempotency-Key") String key){return response(service.change(false,CropHealthInput.uuid(id),"resolve",body,key));}
  @PostMapping("/api/v1/incidents/{id}/reopen") public ResponseEntity<String> reopenIncident(@PathVariable String id,@RequestBody String body,@RequestHeader("Idempotency-Key") String key){return response(service.change(false,CropHealthInput.uuid(id),"reopen",body,key));}
  @PostMapping("/api/v1/incidents/{id}/void") public ResponseEntity<String> voidIncident(@PathVariable String id,@RequestBody String body,@RequestHeader("Idempotency-Key") String key){return response(service.change(false,CropHealthInput.uuid(id),"void",body,key));}
  private void check(MultiValueMap<String,String> q,Set<String> allowed) {
    require(allowed.containsAll(q.keySet()),"Unknown query filter");q.forEach((k,v)->require(!v.isEmpty()&&v.stream().allMatch(x->x!=null&&!x.isBlank())&&(k.equals("status")||v.size()==1),"Empty/repeated query filter"));
  }
  private String one(MultiValueMap<String,String> q,String key){return q.getFirst(key);}
  private int integer(MultiValueMap<String,String> q,String key,int fallback) {
    String value=one(q,key);if(value==null)return fallback;require(value.matches("0|[1-9][0-9]*"),"Invalid page integer");
    try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new Invalid("Page integer overflow");}
  }
  private Filter filter(MultiValueMap<String,String> q) {
    require(q.containsKey("estateId")&&q.containsKey("provenance"),"Estate and provenance required");
    return new Filter(CropHealthInput.uuid(one(q,"estateId")),q.containsKey("blockId")?CropHealthInput.uuid(one(q,"blockId")):null,
        q.containsKey("observedFrom")?parseObservation(one(q,"observedFrom")):null,q.containsKey("observedTo")?parseObservation(one(q,"observedTo")):null,
        CropHealthInput.parse(Provenance.class,one(q,"provenance")),integer(q,"page",0),integer(q,"size",25));
  }
  @GetMapping("/api/v1/inspections") public Object inspections(@RequestParam MultiValueMap<String,String> q) {
    check(q,Set.of("estateId","provenance","blockId","observedFrom","observedTo","page","size","outcome","state"));
    return service.list(new InspectionFilter(filter(q),q.containsKey("outcome")?CropHealthInput.parse(Outcome.class,one(q,"outcome")):null,q.containsKey("state")?CropHealthInput.parse(InspectionState.class,one(q,"state")):null));
  }
  @GetMapping("/api/v1/incidents") public Object incidents(@RequestParam MultiValueMap<String,String> q) {
    check(q,Set.of("estateId","provenance","blockId","observedFrom","observedTo","page","size","category","severity","status"));
    Set<Status> statuses=null;if(q.containsKey("status")){statuses=new HashSet<>();for(String value:q.get("status"))require(statuses.add(CropHealthInput.parse(Status.class,value)),"Duplicate status filter");}
    return service.list(new IncidentFilter(filter(q),q.containsKey("category")?CropHealthInput.parse(Category.class,one(q,"category")):null,q.containsKey("severity")?CropHealthInput.parse(Severity.class,one(q,"severity")):null,statuses));
  }
}
