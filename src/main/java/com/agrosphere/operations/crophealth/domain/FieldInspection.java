package com.agrosphere.operations.crophealth.domain;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import java.util.*;

public record FieldInspection(UUID id, Attribution attribution, Observation observation, InspectionState state,
    UUID linkedIncidentId, Audit audit) implements Root {
  public FieldInspection {
    Objects.requireNonNull(id); Objects.requireNonNull(attribution); Objects.requireNonNull(observation);
    Objects.requireNonNull(state); Objects.requireNonNull(audit);
    require(!observation.observedAt().isAfter(audit.updatedAt()),"Observation after recording");
    require(observation.outcome()!=Outcome.FOLLOW_UP || linkedIncidentId!=null,"FOLLOW_UP requires linked incident");
  }
  /** Return both roots together; the application transaction must persist both or neither. */
  public record Result(FieldInspection inspection, CropIncident incident, boolean incidentCreated) {
    public Result {
      Objects.requireNonNull(inspection); inspection.validateLink(incident);
      require(!incidentCreated || incident!=null && incident.audit().version()==0,"Invalid created incident");
    }
  }
  public void validateLink(CropIncident incident) {
    require(Objects.equals(linkedIncidentId,incident==null?null:incident.id()),"Linked incident identity mismatch");
    if (incident!=null) {
      require(id.equals(incident.inspectionId()) && attribution.equals(incident.attribution()),"Linked attribution mismatch");
      conflict(observation.outcome()!=Outcome.CLEAR || incident.status()==Status.VOIDED,"CLEAR retains only a voided link");
      conflict(state!=InspectionState.VOIDED || incident.status()==Status.VOIDED,"Voided inspection requires voided incident");
    }
  }
  public static Result record(UUID id, UUID incidentId, Context context, Provenance provenance, Observation observation,
      Report initialIncident, String backdateReason, Command c) {
    Attribution a=Attribution.from(context,provenance);
    c.actor().create(a.estateId(),a.estateTimezone(),observation.observedAt(),c.now(),backdateReason);
    boolean follow=observation.outcome()==Outcome.FOLLOW_UP;
    require(follow==(initialIncident!=null) && follow==(incidentId!=null),"Initial incident/outcome mismatch");
    if (follow) require(initialIncident.observedAt().equals(observation.observedAt()),"Initial linked observation mismatch");
    CropIncident incident=follow?CropIncident.report(incidentId,a,id,initialIncident,backdateReason,c):null;
    return new Result(new FieldInspection(id,a,observation,InspectionState.RECORDED,incidentId,Audit.create(c)),incident,follow);
  }
  private void check(long version, String reason, Command c) {
    c.actor().manage(attribution.estateId()); developmentOnly(attribution.dataProvenance());audit.expect(version);text(reason,1000);
    conflict(state==InspectionState.RECORDED,"Voided inspection is terminal");
  }
  public Result correct(long version, Long incidentVersion, Observation replacement, CropIncident linked,
      UUID newIncidentId, Report firstIncident, String reason, Command c) {
    check(version,reason,c);validatePair(linked,incidentVersion);
    require(!replacement.observedAt().isAfter(c.now()),"Future observation");
    conflict(!replacement.equals(observation),"No inspection change");
    boolean create=replacement.outcome()==Outcome.FOLLOW_UP && linkedIncidentId==null;
    require(create==(firstIncident!=null) && create==(newIncidentId!=null),"Unexpected/missing first incident");
    if (linkedIncidentId!=null && replacement.outcome()==Outcome.FOLLOW_UP)
      conflict(observation.outcome()!=Outcome.CLEAR,"Retained link prevents second follow-up; record new inspection");
    if (replacement.outcome()==Outcome.CLEAR && linked!=null) conflict(linked.status()==Status.VOIDED,"Void linked incident first");
    if (create) {
      require(firstIncident.observedAt().equals(replacement.observedAt()),"Initial linked observation mismatch");
      linked=CropIncident.report(newIncidentId,attribution,id,firstIncident,reason,c);
    }
    var changed=new FieldInspection(id,attribution,replacement,state,create?newIncidentId:linkedIncidentId,audit.next(c));
    return new Result(changed,linked,create);
  }
  public FieldInspection voidInspection(long version, Long incidentVersion, CropIncident linked, String reason, Command c) {
    check(version,reason,c);validatePair(linked,incidentVersion);
    conflict(linked==null || linked.status()==Status.VOIDED,"Void linked incident first");
    return new FieldInspection(id,attribution,observation,InspectionState.VOIDED,linkedIncidentId,audit.next(c));
  }
  public void validatePair(CropIncident linked, Long incidentVersion) {
    validateLink(linked);
    require((linked==null)==(incidentVersion==null),"Paired incident version required iff linked");
    if (linked!=null) linked.audit().expect(incidentVersion);
  }
}
