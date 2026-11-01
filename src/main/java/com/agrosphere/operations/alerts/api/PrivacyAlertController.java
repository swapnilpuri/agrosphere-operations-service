package com.agrosphere.operations.alerts.api;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.alerts.application.*;
import com.agrosphere.operations.alerts.application.AlertPorts.*;
import com.agrosphere.operations.alerts.domain.AlertRules.State;
import com.agrosphere.operations.crophealth.application.CropHealthInput;
import java.util.*;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/alerts")
public class PrivacyAlertController {
  private final PrivacyAlertRuntime service;
  public PrivacyAlertController(PrivacyAlertRuntime service){this.service=service;}
  private void query(MultiValueMap<String,String> q,Set<String> allowed){for(var e:q.entrySet())require(allowed.contains(e.getKey())&&e.getValue().size()==1&&!e.getValue().getFirst().isBlank(),"Unknown/duplicate/empty alert filter");}
  private int integer(MultiValueMap<String,String> q,String name,int fallback){try{String s=q.getFirst(name);require(s==null||s.matches("[0-9]+"),"Invalid page");return s==null?fallback:Integer.parseInt(s);}catch(NumberFormatException e){throw new Invalid("Invalid page");}}
  private boolean bool(MultiValueMap<String,String> q,String name){String s=q.getFirst(name);require(s==null||s.equals("true")||s.equals("false"),"Invalid boolean filter");return "true".equals(s);}
  private <T extends Enum<T>> T choice(String s,Class<T> type,T fallback){if(s==null)return fallback;try{return Enum.valueOf(type,s);}catch(IllegalArgumentException e){throw new Invalid("Invalid alert filter");}}
  private UUID uuid(String s){return CropHealthInput.uuid(s);}
  private ResponseEntity<Object> ok(Object body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
  @GetMapping public ResponseEntity<Object> list(@RequestParam MultiValueMap<String,String> q){
    query(q,Set.of("estateId","state","severity","blockId","unreadOnly","unacknowledgedOnly","page","size"));
    return ok(service.list(new Filter(uuid(q.getFirst("estateId")),q.containsKey("blockId")?uuid(q.getFirst("blockId")):null,
        choice(q.getFirst("state"),State.class,State.ACTIVE),choice(q.getFirst("severity"),Severity.class,null),bool(q,"unreadOnly"),bool(q,"unacknowledgedOnly"),integer(q,"page",0),integer(q,"size",20))));
  }
  @GetMapping("/counts") public ResponseEntity<Object> counts(@RequestParam MultiValueMap<String,String> q){query(q,Set.of("estateId"));return ok(service.counts(uuid(q.getFirst("estateId"))));}
  @GetMapping("/{alertId}") public ResponseEntity<Object> get(@PathVariable String alertId,@RequestParam MultiValueMap<String,String> q){query(q,Set.of());return ok(service.get(uuid(alertId)));}
  @GetMapping("/{alertId}/history") public ResponseEntity<Object> history(@PathVariable String alertId,@RequestParam MultiValueMap<String,String> q){query(q,Set.of("page","size"));return ok(service.history(uuid(alertId),integer(q,"page",0),integer(q,"size",20)));}
  private ResponseEntity<String> change(String id,boolean ack,String body,String key,MultiValueMap<String,String> q){query(q,Set.of());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(service.change(uuid(id),ack,body,key));}
  @PostMapping(value="/{alertId}/read",consumes="application/json") public ResponseEntity<String> read(@PathVariable String alertId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body,@RequestParam MultiValueMap<String,String> q){return change(alertId,false,body,key,q);}
  @PostMapping(value="/{alertId}/acknowledgements",consumes="application/json") public ResponseEntity<String> ack(@PathVariable String alertId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body,@RequestParam MultiValueMap<String,String> q){return change(alertId,true,body,key,q);}
}
