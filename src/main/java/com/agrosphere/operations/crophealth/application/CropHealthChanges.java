package com.agrosphere.operations.crophealth.application;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.crophealth.domain.*;
import java.time.*;
import java.util.*;

/** Pure transaction results. Adapters must persist every revision/event atomically with the receipt. */
public final class CropHealthChanges {
  private CropHealthChanges() {}
  public record ChangeSet(FieldInspection inspection, CropIncident incident, List<Revision> revisions) {
    public ChangeSet {
      revisions=List.copyOf(revisions);require(!revisions.isEmpty(),"Empty change set");
      if (inspection!=null) inspection.validateLink(incident);
      else require(incident!=null && incident.inspectionId()==null,"Linked incident requires inspection context");
    }
  }
  public static ChangeSet record(UUID inspectionId, UUID incidentId, Context context, Provenance provenance,
      Observation observation, Report report, String backdateReason, Command c) {
    var result=FieldInspection.record(inspectionId,incidentId,context,provenance,observation,report,backdateReason,c);
    var revisions=new ArrayList<Revision>();revisions.add(Revision.of(result.inspection(),Kind.RECORD,backdateReason,c));
    if (result.incidentCreated()) revisions.add(Revision.of(result.incident(),Kind.REPORT,backdateReason,c));
    return new ChangeSet(result.inspection(),result.incident(),revisions);
  }
  public static ChangeSet directReport(UUID id, Context context, Provenance provenance, Report report, String backdateReason, Command c) {
    var incident=CropIncident.report(id,Attribution.from(context,provenance),null,report,backdateReason,c);
    return new ChangeSet(null,incident,List.of(Revision.of(incident,Kind.REPORT,backdateReason,c)));
  }
  public static ChangeSet correctInspection(FieldInspection inspection, CropIncident linked, long version, Long incidentVersion,
      Observation replacement, UUID newIncidentId, Report firstIncident, String reason, Command c) {
    var result=inspection.correct(version,incidentVersion,replacement,linked,newIncidentId,firstIncident,reason,c);
    var revisions=new ArrayList<Revision>();revisions.add(Revision.of(result.inspection(),Kind.CORRECT,reason,c));
    if (result.incidentCreated()) revisions.add(Revision.of(result.incident(),Kind.REPORT,reason,c));
    return new ChangeSet(result.inspection(),result.incident(),revisions);
  }
  public static ChangeSet voidInspection(FieldInspection inspection, CropIncident linked, long version, Long incidentVersion, String reason, Command c) {
    var changed=inspection.voidInspection(version,incidentVersion,linked,reason,c);
    return new ChangeSet(changed,linked,List.of(Revision.of(changed,Kind.VOID,reason,c)));
  }
  private static void pair(CropIncident incident, FieldInspection inspection, Long inspectionVersion) {
    require((incident.inspectionId()==null)==(inspection==null),"Missing/unexpected inspection context");
    require((inspection==null)==(inspectionVersion==null),"Paired inspection version required iff linked");
    if (inspection!=null) { inspection.validateLink(incident);inspection.audit().expect(inspectionVersion); }
  }
  public static ChangeSet editIncident(CropIncident incident, FieldInspection inspection, long version, Long inspectionVersion,
      Report replacement, boolean correction, String reason, Command c) {
    pair(incident,inspection,inspectionVersion);
    var changed=incident.edit(version,replacement,correction,reason,c);
    return new ChangeSet(inspection,changed,List.of(Revision.of(changed,correction?Kind.CORRECT:Kind.AMEND,reason,c)));
  }
  public static ChangeSet transition(CropIncident incident, FieldInspection inspection, long version, Long inspectionVersion,
      Kind kind, Instant resolvedAt, String resolutionNote, String reason, Command c) {
    pair(incident,inspection,inspectionVersion);
    var changed=incident.transition(version,kind,resolvedAt,resolutionNote,reason,c);
    return new ChangeSet(inspection,changed,List.of(Revision.of(changed,kind,reason,c)));
  }
}
