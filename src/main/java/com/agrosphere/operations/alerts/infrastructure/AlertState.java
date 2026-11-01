package com.agrosphere.operations.alerts.infrastructure;

import static com.agrosphere.operations.crophealth.infrastructure.CropHealthState.*;
import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.alerts.domain.CropHealthAlert;
import com.agrosphere.operations.alerts.domain.CropHealthAlert.*;
import com.agrosphere.operations.crophealth.domain.CropIncident;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Explicit private/public codecs. Never use automatic record serialization for wire responses. */
public final class AlertState {
  private AlertState() {}
  public static Source source(CropIncident c) {
    var a=c.attribution();var r=c.report();return new Source(c.id(),a.estateId(),a.blockId(),a.estateTimezone(),a.dataProvenance(),c.audit().version(),r.observedAt(),r.category(),r.severity(),c.status());
  }
  public static Map<String,Object> identity(Identity i) { return i==null?null:fields("issuer",i.issuer(),"subject",i.subject()); }
  public static Map<String,Object> privateView(CropHealthAlert a) {
    var s=a.source();var ack=a.acknowledgement();
    return fields("id",id(a.id()),"incidentId",id(s.incidentId()),"estateId",id(s.estateId()),"blockId",id(s.blockId()),"ruleVersion",RULE,
        "sourceVersion",s.version(),"alertVersion",a.alertVersion(),"attentionVersion",a.attentionVersion(),"state",a.state().name(),
        "inactiveReason",a.inactiveReason()==null?null:a.inactiveReason().name(),"category",s.category().name(),"severity",s.severity().name(),
        "incidentStatus",s.status().name(),"dataProvenance","SIMULATED","estateTimezone",s.estateTimezone().getId(),"observedAt",time(s.observedAt()),
        "raisedAt",time(a.raisedAt()),"attentionAt",time(a.attentionAt()),"updatedAt",time(a.updatedAt()),
        "acknowledged",ack!=null,"acknowledgedAt",ack==null?null:time(ack.at()),"acknowledgedBy",ack==null?null:identity(ack.actor()));
  }
  public static Map<String,Object> view(CropHealthAlert a,ReadMarker own) {
    var v=privateView(a);v.remove("acknowledgedBy");v.putAll(fields("readAttentionVersion",own==null?0:own.attentionVersion(),
        "readAt",own==null?null:time(own.at()),"unreadForMe",a.unread(own)));return v;
  }
  public static Map<String,Object> transition(Transition t) {
    return fields("alertId",id(t.alertId()),"alertVersion",t.alertVersion(),"sourceVersion",t.sourceVersion(),"attentionVersion",t.attentionVersion(),
        "ruleVersion",RULE,"kind",t.kind().name(),"beforeState",t.beforeState()==null?null:t.beforeState().name(),"afterState",t.afterState().name(),
        "recordedAt",time(t.at()),"correlationId",id(t.correlationId()),"acknowledgedBy",identity(t.acknowledgedBy()));
  }
  private static UUID uuid(JsonNode n,String k){return UUID.fromString(n.required(k).asText());}
  private static Instant at(JsonNode n,String k){return Instant.parse(n.required(k).asText());}
  public static CropHealthAlert read(String raw) {
    var n=parse(raw);var s=new Source(uuid(n,"incidentId"),uuid(n,"estateId"),uuid(n,"blockId"),ZoneId.of(n.required("estateTimezone").asText()),
        Provenance.valueOf(n.required("dataProvenance").asText()),n.required("sourceVersion").longValue(),at(n,"observedAt"),Category.valueOf(n.required("category").asText()),
        Severity.valueOf(n.required("severity").asText()),Status.valueOf(n.required("incidentStatus").asText()));
    var who=n.required("acknowledgedBy");var ack=who.isNull()?null:new Acknowledgement(uuid(n,"id"),n.required("attentionVersion").longValue(),
        new Identity(who.required("issuer").asText(),who.required("subject").asText()),at(n,"acknowledgedAt"));
    return new CropHealthAlert(uuid(n,"id"),s,n.required("alertVersion").longValue(),n.required("attentionVersion").longValue(),at(n,"raisedAt"),at(n,"attentionAt"),at(n,"updatedAt"),ack);
  }
}
