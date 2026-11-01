package com.agrosphere.operations.crophealth.infrastructure;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.crophealth.domain.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.*;

/** Explicit private codec and public/event allowlists. Never serialize a domain record implicitly. */
public final class CropHealthState {
  private CropHealthState() {}
  private static final ObjectMapper JSON=new ObjectMapper();
  public static Map<String,Object> fields(Object... pairs) {
    var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
  }
  public static String stringify(Object value){return JSON.writeValueAsString(value);}
  public static JsonNode parse(String value){return JSON.readTree(value);}
  public static String id(UUID value){return value==null?null:value.toString();}
  public static String time(Instant value){return value==null?null:value.toString();}
  public static Map<String,Object> privateSnapshot(Root root) {
    var a=root.attribution();var audit=root.audit();
    var result=fields("id",id(root.id()),"estateId",id(a.estateId()),"blockId",id(a.blockId()),"estateTimezone",a.estateTimezone().getId(),
        "dataProvenance",a.dataProvenance().name(),"version",audit.version(),"createdAt",time(audit.createdAt()),"updatedAt",time(audit.updatedAt()),
        "createdBy",audit.createdBy(),"updatedBy",audit.updatedBy());
    if(root instanceof FieldInspection i) result.putAll(fields("observedAt",time(i.observation().observedAt()),"findings",i.observation().findings(),
        "outcome",i.observation().outcome().name(),"state",i.state().name(),"linkedIncidentId",id(i.linkedIncidentId())));
    else {
      var c=(CropIncident)root;var r=c.report();result.putAll(fields("observedAt",time(r.observedAt()),"inspectionId",id(c.inspectionId()),
          "category",r.category().name(),"severity",r.severity().name(),"summary",r.summary(),"details",r.details(),"status",c.status().name(),
          "resolvedAt",time(c.resolvedAt()),"resolutionNote",c.resolutionNote(),"earliestRetainedResolutionAt",time(c.earliestRetainedResolutionAt())));
    }return result;
  }
  public static String write(Root root){return stringify(privateSnapshot(root));}
  public static Map<String,Object> view(Root root) {
    var result=privateSnapshot(root);result.keySet().removeAll(Set.of("createdBy","updatedBy","earliestRetainedResolutionAt"));return result;
  }
  public static Map<String,Object> inspectionView(FieldInspection root,CropIncident linked) {
    root.validateLink(linked);var result=view(root);result.put("linkedIncidentStatus",linked==null?null:linked.status().name());
    result.put("linkedIncidentVersion",linked==null?null:linked.audit().version());return result;
  }
  public static Map<String,Object> history(Revision revision) {
    var root=revision.snapshot();return fields("id",id(root.id()),"version",root.audit().version(),
        "previousVersion",root.audit().version()==0?null:root.audit().version()-1,"commandKind",revision.commandKind().name(),
        "reason",revision.reason(),"recordedAt",time(revision.recordedAt()),"recordedBy",revision.recordedBy(),
        "correlationId",id(revision.correlationId()),"snapshot",view(root));
  }
  public static Map<String,Object> minimal(Root root) {
    var result=view(root);result.keySet().removeAll(Set.of("createdAt","updatedAt","findings","summary","details","resolutionNote"));return result;
  }
  public static String eventType(Revision revision) {
    return revision.snapshot() instanceof FieldInspection ? (revision.snapshot().audit().version()==0?"FieldInspectionRecorded":"FieldInspectionChanged")
        : (revision.snapshot().audit().version()==0?"CropHealthIncidentReported":"CropHealthIncidentChanged");
  }
  public static String event(Revision revision,UUID eventId) {
    var root=revision.snapshot();var a=root.attribution();var payload=fields("snapshot",minimal(root));
    if(root.audit().version()>0)payload.putAll(fields("previousVersion",root.audit().version()-1,"changeKind",revision.commandKind().name()));
    return stringify(fields("eventId",id(eventId),"eventType",eventType(revision),"eventVersion",1,"timestamp",time(revision.recordedAt()),
        "source","agrosphere-operations-service","correlationId",id(revision.correlationId()),"estateId",id(a.estateId()),"blockId",id(a.blockId()),
        "dataProvenance",a.dataProvenance().name(),"aggregateId",id(root.id()),"aggregateVersion",root.audit().version(),"payload",payload));
  }
  private static UUID uuid(JsonNode n,String key){return UUID.fromString(n.required(key).asText());}
  private static UUID optionalId(JsonNode n,String key){return n.required(key).isNull()?null:uuid(n,key);}
  private static Instant instant(JsonNode n,String key){return n.required(key).isNull()?null:Instant.parse(n.required(key).asText());}
  private static Attribution attribution(JsonNode n){return new Attribution(uuid(n,"estateId"),uuid(n,"blockId"),ZoneId.of(n.required("estateTimezone").asText()),Provenance.valueOf(n.required("dataProvenance").asText()));}
  private static Audit audit(JsonNode n){return new Audit(n.required("version").longValue(),instant(n,"createdAt"),instant(n,"updatedAt"),n.required("createdBy").asText(),n.required("updatedBy").asText());}
  public static FieldInspection inspection(String raw) {
    var n=parse(raw);return new FieldInspection(uuid(n,"id"),attribution(n),new Observation(instant(n,"observedAt"),Outcome.valueOf(n.required("outcome").asText()),n.required("findings").asText()),
        InspectionState.valueOf(n.required("state").asText()),optionalId(n,"linkedIncidentId"),audit(n));
  }
  public static CropIncident incident(String raw) {
    var n=parse(raw);return new CropIncident(uuid(n,"id"),attribution(n),optionalId(n,"inspectionId"),new Report(instant(n,"observedAt"),Category.valueOf(n.required("category").asText()),
        Severity.valueOf(n.required("severity").asText()),n.required("summary").asText(),n.required("details").asText()),Status.valueOf(n.required("status").asText()),
        instant(n,"resolvedAt"),n.required("resolutionNote").isNull()?null:n.required("resolutionNote").asText(),instant(n,"earliestRetainedResolutionAt"),audit(n));
  }
}
