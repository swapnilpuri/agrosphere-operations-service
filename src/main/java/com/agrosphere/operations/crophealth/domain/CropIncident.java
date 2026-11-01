package com.agrosphere.operations.crophealth.domain;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import java.time.*;
import java.util.*;

public record CropIncident(UUID id, Attribution attribution, UUID inspectionId, Report report, Status status,
    Instant resolvedAt, String resolutionNote, Instant earliestRetainedResolutionAt, Audit audit) implements Root {
  public CropIncident {
    Objects.requireNonNull(id); Objects.requireNonNull(attribution); Objects.requireNonNull(report);
    Objects.requireNonNull(status); Objects.requireNonNull(audit);
    require(!id.equals(inspectionId), "Inspection and incident identities must be distinct");
    require(!report.observedAt().isAfter(audit.updatedAt()), "Observation after recording");
    require((resolvedAt == null) == (resolutionNote == null), "Incomplete resolution evidence");
    if (status == Status.RESOLVED) require(resolvedAt != null, "Resolution evidence required");
    if (status == Status.OPEN || status == Status.IN_REVIEW) require(resolvedAt == null, "Unexpected current resolution");
    if (resolvedAt != null) {
      precise(resolvedAt); resolutionNote = text(resolutionNote,1000);
      require(!resolvedAt.isBefore(report.observedAt()) && !resolvedAt.isAfter(audit.updatedAt()), "Invalid resolution chronology");
      require(earliestRetainedResolutionAt != null && !earliestRetainedResolutionAt.isAfter(resolvedAt), "Missing retained resolution");
    }
    if (earliestRetainedResolutionAt != null) {
      precise(earliestRetainedResolutionAt);
      require(!earliestRetainedResolutionAt.isBefore(report.observedAt()) && !earliestRetainedResolutionAt.isAfter(audit.updatedAt()), "Retained chronology invalidated");
    }
  }
  public static CropIncident report(UUID id, Attribution a, UUID inspectionId, Report report, String backdateReason, Command c) {
    developmentOnly(a.dataProvenance()); c.actor().create(a.estateId(),a.estateTimezone(),report.observedAt(),c.now(),backdateReason);
    return new CropIncident(id,a,inspectionId,report,Status.OPEN,null,null,null,Audit.create(c));
  }
  private void check(long version, String reason, Command c) {
    c.actor().manage(attribution.estateId()); developmentOnly(attribution.dataProvenance()); audit.expect(version);
    text(reason,1000); conflict(status != Status.VOIDED,"Voided incident is terminal");
  }
  public CropIncident edit(long version, Report replacement, boolean correction, String reason, Command c) {
    check(version,reason,c);
    conflict(correction || status == Status.OPEN || status == Status.IN_REVIEW,"Amend requires open incident");
    require(!replacement.observedAt().isAfter(c.now()),"Future observation");
    conflict(!replacement.equals(report),"No report change");
    // Every previous resolution must remain at/after observation, including after reopening.
    require(earliestRetainedResolutionAt == null || !replacement.observedAt().isAfter(earliestRetainedResolutionAt),"Retained resolution chronology invalidated");
    return new CropIncident(id,attribution,inspectionId,replacement,status,resolvedAt,resolutionNote,earliestRetainedResolutionAt,audit.next(c));
  }
  public CropIncident transition(long version, Kind kind, Instant resolutionTime, String note, String reason, Command c) {
    check(version,reason,c); Status next; Instant at = resolvedAt, retained = earliestRetainedResolutionAt; String resolution = resolutionNote;
    switch (kind) {
      case TRIAGE -> { conflict(status==Status.OPEN,"Triage requires OPEN"); next=Status.IN_REVIEW; }
      case RESOLVE -> {
        conflict(status==Status.OPEN || status==Status.IN_REVIEW,"Resolve requires open incident");
        at=precise(resolutionTime); require(!at.isBefore(report.observedAt()) && !at.isAfter(c.now()),"Invalid resolution chronology");
        resolution=text(note,1000); next=Status.RESOLVED;
        // Retain the earliest resolution boundary so every historical closure remains valid.
        retained=retained==null || at.isBefore(retained) ? at : retained;
      }
      case REOPEN -> { conflict(status==Status.RESOLVED,"Reopen requires RESOLVED"); next=Status.OPEN; at=null; resolution=null; }
      case VOID -> next=Status.VOIDED;
      default -> throw new Invalid("Not a lifecycle command");
    }
    if (kind!=Kind.RESOLVE) require(resolutionTime==null && note==null,"Unexpected resolution input");
    return new CropIncident(id,attribution,inspectionId,report,next,at,resolution,retained,audit.next(c));
  }
}
