package com.agrosphere.operations.api;

import static com.agrosphere.operations.application.Requests.*;
import static com.agrosphere.operations.domain.Rules.*;

import com.agrosphere.operations.application.*;
import com.agrosphere.operations.domain.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request or state transition",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid JWT",
      content = @Content(mediaType = "application/problem+json")),
  @ApiResponse(
      responseCode = "403",
      description = "Role, estate or assignment access denied",
      content = @Content(mediaType = "application/problem+json")),
  @ApiResponse(
      responseCode = "404",
      description = "Resource missing",
      content = @Content(mediaType = "application/problem+json")),
  @ApiResponse(
      responseCode = "409",
      description = "Stale version or duplicate occurrence",
      content = @Content(mediaType = "application/problem+json")),
  @ApiResponse(
      responseCode = "422",
      description = "Invalid estate/block reference",
      content = @Content(mediaType = "application/problem+json")),
  @ApiResponse(
      responseCode = "503",
      description = "Estate dependency unavailable",
      content = @Content(mediaType = "application/problem+json"))
})
@RestController
@RequestMapping("/api/v1/tapping")
public class TappingController {
  private final TappingService service;

  public TappingController(TappingService service) {
    this.service = service;
  }

  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/schedules")
  public ResponseEntity<TappingSchedule> create(@Valid @RequestBody ScheduleWrite r) {
    var s = service.create(r);
    return ResponseEntity.created(URI.create("/api/v1/tapping/schedules/" + s.id())).body(s);
  }

  @GetMapping("/schedules/{id}")
  public TappingSchedule schedule(@PathVariable UUID id) {
    return service.schedule(id);
  }

  @PatchMapping("/schedules/{id}")
  public TappingSchedule edit(@PathVariable UUID id, @Valid @RequestBody ScheduleEdit r) {
    return service.edit(id, r);
  }

  @PostMapping("/schedules/{id}/activate")
  public TappingSchedule activate(@PathVariable UUID id, @Valid @RequestBody Version r) {
    return service.lifecycle(id, r, ScheduleStatus.ACTIVE);
  }

  @PostMapping("/schedules/{id}/suspend")
  public TappingSchedule suspend(@PathVariable UUID id, @Valid @RequestBody Version r) {
    return service.lifecycle(id, r, ScheduleStatus.SUSPENDED);
  }

  @PostMapping("/schedules/{id}/close")
  public TappingSchedule close(@PathVariable UUID id, @Valid @RequestBody Version r) {
    return service.lifecycle(id, r, ScheduleStatus.CLOSED);
  }

  @PostMapping("/schedules/{id}/generate-assignments")
  public List<TappingAssignment> generate(@PathVariable UUID id, @Valid @RequestBody Generate r) {
    return service.generate(id, r);
  }

  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/assignments")
  public ResponseEntity<TappingAssignment> createAssignment(@Valid @RequestBody AssignmentWrite r) {
    var a = service.createAssignment(r);
    return ResponseEntity.created(URI.create("/api/v1/tapping/assignments/" + a.id())).body(a);
  }

  @GetMapping("/assignments/{id}")
  public AssignmentView assignment(@PathVariable UUID id) {
    return service.assignment(id);
  }

  @PatchMapping("/assignments/{id}/worker")
  public TappingAssignment worker(@PathVariable UUID id, @Valid @RequestBody WorkerWrite r) {
    return service.worker(id, r);
  }

  @PostMapping("/assignments/{id}/start")
  public AssignmentView start(@PathVariable UUID id, @Valid @RequestBody Version r) {
    return service.start(id, r);
  }

  @PostMapping("/assignments/{id}/complete")
  public AssignmentView complete(@PathVariable UUID id, @Valid @RequestBody Complete r) {
    return service.complete(id, r);
  }

  @PostMapping("/assignments/{id}/mark-missed")
  public AssignmentView missed(@PathVariable UUID id, @Valid @RequestBody Missed r) {
    return service.missed(id, r);
  }

  @PostMapping("/assignments/{id}/cancel")
  public AssignmentView cancel(@PathVariable UUID id, @Valid @RequestBody Version r) {
    return service.cancel(id, r);
  }

  @GetMapping("/schedules")
  public Page<TappingSchedule> schedules(
      @RequestParam(required = false) UUID estateId,
      @RequestParam(required = false) UUID blockId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.schedules(new Filter(estateId, blockId, null, null, null, status, page, size));
  }

  @GetMapping("/assignments")
  public Page<TappingAssignment> assignments(
      @RequestParam(required = false) UUID estateId,
      @RequestParam(required = false) UUID blockId,
      @RequestParam(required = false) String workerId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.assignments(
        new Filter(estateId, blockId, workerId, from, to, status, page, size));
  }

  @GetMapping("/records")
  public Page<TappingRecord> records(
      @RequestParam(required = false) UUID estateId,
      @RequestParam(required = false) UUID blockId,
      @RequestParam(required = false) String workerId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.records(new Filter(estateId, blockId, workerId, from, to, null, page, size));
  }

  @GetMapping("/summary")
  public Map<String, Object> summary(
      @RequestParam(required = false) UUID estateId,
      @RequestParam(required = false) UUID blockId,
      @RequestParam(required = false) String workerId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(defaultValue = "total") String groupBy,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.summary(
        new Filter(estateId, blockId, workerId, from, to, null, page, size), groupBy);
  }
}
