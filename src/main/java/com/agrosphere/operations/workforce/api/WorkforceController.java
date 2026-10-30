package com.agrosphere.operations.workforce.api;

import com.agrosphere.operations.workforce.application.*;
import com.agrosphere.operations.workforce.application.WorkforcePorts.*;
import com.agrosphere.operations.workforce.domain.WorkforceRules.Invalid;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
public class WorkforceController {
  private final WorkforceService service;private final WorkforceQueryService query;private final ObjectMapper json=new ObjectMapper();
  public WorkforceController(WorkforceService service,WorkforceQueryService query){this.service=service;this.query=query;}
  private ResponseEntity<String> read(Object value){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(value));}
  private ResponseEntity<String> write(CommandKind kind,String id,String key,String body,HttpServletRequest request){
    if(!request.getParameterMap().isEmpty())throw new Invalid("Unknown workforce command filter");
    var r=service.mutate(kind,id==null?null:WorkforceCommands.uuid(id),key,body);var response=ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON);
    if(r.location()!=null)response.header("Location",r.location());return response.body(r.body());
  }
  @Operation(operationId="registerWorker") @PostMapping(value="/api/v1/workers",consumes="application/json")
  public ResponseEntity<String> register(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String body,HttpServletRequest request){return write(CommandKind.REGISTER,null,key,body,request);}
  @Operation(operationId="listWorkers") @GetMapping("/api/v1/workers")
  public ResponseEntity<String> workers(@RequestParam MultiValueMap<String,String> p){return read(query.workers(p));}
  @Operation(operationId="getWorker") @GetMapping("/api/v1/workers/{id}")
  public ResponseEntity<String> worker(@PathVariable String id,@RequestParam MultiValueMap<String,String> p){return read(query.worker(WorkforceCommands.uuid(id),p));}
  @Operation(operationId="updateWorker") @PatchMapping(value="/api/v1/workers/{id}",consumes="application/json")
  public ResponseEntity<String> edit(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String body,HttpServletRequest request){return write(CommandKind.PROFILE,id,key,body,request);}
  @Operation(operationId="closeWorkerPeriod") @PostMapping(value="/api/v1/workers/{id}/close-period",consumes="application/json")
  public ResponseEntity<String> close(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String body,HttpServletRequest request){return write(CommandKind.PERIOD_CLOSED,id,key,body,request);}
  @Operation(operationId="openWorkerPeriod") @PostMapping(value="/api/v1/workers/{id}/open-period",consumes="application/json")
  public ResponseEntity<String> open(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String body,HttpServletRequest request){return write(CommandKind.PERIOD_OPENED,id,key,body,request);}
  @Operation(operationId="getWorkerHistory") @GetMapping("/api/v1/workers/{id}/history")
  public ResponseEntity<String> workerHistory(@PathVariable String id,@RequestParam MultiValueMap<String,String> p){return read(query.history(WorkforceCommands.uuid(id),true,p));}
  @Operation(operationId="getAttendanceRoster") @GetMapping("/api/v1/attendance/roster")
  public ResponseEntity<String> roster(@RequestParam MultiValueMap<String,String> p){return read(query.roster(p));}
  @Operation(operationId="recordAttendanceDay") @PostMapping(value="/api/v1/attendance/days",consumes="application/json")
  public ResponseEntity<String> record(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String body,HttpServletRequest request){return write(CommandKind.RECORD,null,key,body,request);}
  @Operation(operationId="listAttendanceDays") @GetMapping("/api/v1/attendance/days")
  public ResponseEntity<String> days(@RequestParam MultiValueMap<String,String> p){return read(query.days(p));}
  @Operation(operationId="getAttendanceDay") @GetMapping("/api/v1/attendance/days/{id}")
  public ResponseEntity<String> day(@PathVariable String id,@RequestParam MultiValueMap<String,String> p){return read(query.day(WorkforceCommands.uuid(id),p));}
  @Operation(operationId="correctAttendanceDay") @PostMapping(value="/api/v1/attendance/days/{id}/corrections",consumes="application/json")
  public ResponseEntity<String> correct(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String body,HttpServletRequest request){return write(CommandKind.CORRECT,id,key,body,request);}
  @Operation(operationId="voidAttendanceDay") @PostMapping(value="/api/v1/attendance/days/{id}/void",consumes="application/json")
  public ResponseEntity<String> voidDay(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String body,HttpServletRequest request){return write(CommandKind.VOID,id,key,body,request);}
  @Operation(operationId="rerecordAttendanceDay") @PostMapping(value="/api/v1/attendance/days/{id}/re-record",consumes="application/json")
  public ResponseEntity<String> rerecord(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String body,HttpServletRequest request){return write(CommandKind.RERECORD,id,key,body,request);}
  @Operation(operationId="getAttendanceHistory") @GetMapping("/api/v1/attendance/days/{id}/history")
  public ResponseEntity<String> dayHistory(@PathVariable String id,@RequestParam MultiValueMap<String,String> p){return read(query.history(WorkforceCommands.uuid(id),false,p));}
}
