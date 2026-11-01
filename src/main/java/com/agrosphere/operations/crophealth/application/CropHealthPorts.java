package com.agrosphere.operations.crophealth.application;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.crophealth.domain.*;
import com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import java.time.*;
import java.util.*;

/** Pure application boundaries; no Spring bean, controller, migration or adapter registered. */
public final class CropHealthPorts {
  private CropHealthPorts() {}
  public interface Identity { Actor current(); }
  public interface References {
    /** Delegated JWT, bounded fail-closed validation of active estate/block relationship and timezone. */
    Context activeBlock(UUID estateId, UUID blockId);
  }
  public record ReadScope(UUID estateId) { public ReadScope { Objects.requireNonNull(estateId); } }
  public interface Ownership {
    /** Minimal ownership only; do not materialize protected fields before authorization. */
    UUID inspectionEstate(UUID id);
    UUID incidentEstate(UUID id);
  }
  public record Filter(UUID estateId, UUID blockId, Instant observedFrom, Instant observedTo,
      Provenance provenance, int page, int size) {
    public Filter {
      Objects.requireNonNull(estateId); developmentOnly(provenance);
      require((observedFrom==null)==(observedTo==null),"Both observation bounds or neither");
      if (observedFrom!=null) { precise(observedFrom); precise(observedTo); require(observedFrom.isBefore(observedTo),"Empty/reversed observation range"); }
      require(page>=0 && size>=1 && size<=100 && (long)page*size<=Integer.MAX_VALUE,"Invalid page");
    }
  }
  public record InspectionFilter(Filter common, Outcome outcome, InspectionState state) { public InspectionFilter { Objects.requireNonNull(common); } }
  public record IncidentFilter(Filter common, Category category, Severity severity, Set<Status> statuses) {
    public IncidentFilter {
      Objects.requireNonNull(common);
      statuses=statuses==null?Set.of(Status.OPEN,Status.IN_REVIEW):Set.copyOf(statuses);
      require(!statuses.isEmpty(),"Empty status filter");
    }
  }
  public record Page<T>(List<T> content, int page, int size, long totalElements) {
    public Page {
      content=List.copyOf(content);require(page>=0 && size>=1 && size<=100 && totalElements>=content.size(),"Invalid page result");
    }
  }
  public interface Queries {
    /** Each page/count uses one scoped DB snapshot, ordered observedAt DESC,id DESC. */
    Page<FieldInspection> inspections(InspectionFilter filter, ReadScope scope);
    Page<CropIncident> incidents(IncidentFilter filter, ReadScope scope);
    FieldInspection inspection(UUID id, ReadScope scope);
    CropIncident incident(UUID id, ReadScope scope);
    Page<Revision> history(UUID id, boolean inspection, ReadScope scope, int page, int size);
  }
  public interface LockedRoots {
    /** Command reservation FIRST, then inspection root if linked, then incident root. */
    FieldInspection lockInspection(UUID id, ReadScope scope);
    CropIncident lockIncident(UUID id, ReadScope scope);
    void insert(Root root);
    void update(Root root, long expectedVersion);
  }
  public record Receipt(String actor, UUID key, String method, String path, String normalizedRequestHash,
      int normalizationVersion, int status, String body, String location) {
    public Receipt {
      actor=subject(actor);Objects.requireNonNull(key);Objects.requireNonNull(body);
      require("POST".equals(method) && path!=null && path.matches("/api/v1/(?:inspections|incidents)(?:/.*)?"),"Invalid crop command target");
      require(normalizedRequestHash!=null && normalizedRequestHash.matches("[0-9a-f]{64}") && normalizationVersion==1,"Invalid normalization/hash");
      require(status==200 || status==201,"Only successful receipts");require(status!=201 || location!=null && !location.isBlank(),"Missing Location");
    }
  }
  public interface Evidence {
    void appendRevision(Revision revision);
    /** Adapter serializes an explicit minimal snapshot allowlist, never the private Root. */
    void appendMinimalEvent(Revision revision, UUID eventId);
    void saveReceipt(Receipt receipt);
  }
  @FunctionalInterface public interface Work { Receipt apply(LockedRoots roots, Evidence evidence); }
  public interface Transactions {
    /**
     * Caller authorizes current operation/estate AND retained response permission before this call.
     * Reserve feature-scoped actor/key; identical normalized method/path/body replays original
     * success including Location without invoking work. Different command conflicts. Failed
     * commands retain no receipt. One transaction owns root(s), revisions, events and receipt.
     * No distributed transaction, sibling SQL or live-stack provisioning.
     */
    Receipt execute(String actor, UUID key, String normalizedMethodPathBodyHash, Work work);
  }
}
