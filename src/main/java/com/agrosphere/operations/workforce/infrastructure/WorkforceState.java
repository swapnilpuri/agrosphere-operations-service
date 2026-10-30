package com.agrosphere.operations.workforce.infrastructure;

import static com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import com.agrosphere.operations.workforce.domain.*;
import com.agrosphere.operations.workforce.domain.WorkerRegistration.ActivePeriod;
import java.time.*;
import java.util.*;
import tools.jackson.databind.*;

/** Private persistence codec. These snapshots contain personal/audit data, not public DTOs or events. */
public final class WorkforceState {
  private static final ObjectMapper JSON = new ObjectMapper();
  private WorkforceState() {}

  public static Map<String,Object> fields(Object... pairs) {
    var result = new LinkedHashMap<String,Object>();
    for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
    return result;
  }
  private static String id(UUID value) { return value == null ? null : value.toString(); }
  private static Map<String,Object> audit(Audit a) {
    return fields("version",a.version(),"createdAt",a.createdAt().toString(),"updatedAt",a.updatedAt().toString(),
        "createdBy",a.createdBy(),"updatedBy",a.updatedBy());
  }
  public static String write(Aggregate root) {
    if (root instanceof WorkerRegistration w) {
      var periods = w.activePeriods().stream().map(p -> fields("id",id(p.id()),"activeFrom",p.activeFrom().toString(),
          "activeTo",p.activeTo()==null?null:p.activeTo().toString(),"introducedVersion",p.introducedVersion(),"closedVersion",p.closedVersion())).toList();
      return stringify(fields("id",id(w.id()),"estateId",id(w.estateId()),"workerCode",w.workerCode(),"displayName",w.displayName(),
          "divisionId",id(w.divisionId()),"dataProvenance",w.dataProvenance().name(),"activePeriods",periods,"audit",audit(w.audit())));
    }
    var day = (AttendanceDay)root; var a=day.attribution();
    return stringify(fields("id",id(day.id()),"attribution",fields("estateId",id(a.estateId()),"workerRegistrationId",id(a.workerRegistrationId()),
        "attendanceDate",a.attendanceDate().toString(),"estateTimezone",a.estateTimezone(),"workerCodeSnapshot",a.workerCodeSnapshot(),
        "divisionIdSnapshot",id(a.divisionIdSnapshot()),"workerVersionSnapshot",a.workerVersionSnapshot(),"activePeriodId",id(a.activePeriodId()),
        "dataProvenance",a.dataProvenance().name()),"status",day.status().name(),"audit",audit(day.audit())));
  }
  public static String stringify(Object value) { return JSON.writeValueAsString(value); }
  public static JsonNode parse(String raw) { return JSON.readTree(raw); }
  private static UUID uuid(JsonNode node,String key) { return UUID.fromString(node.required(key).asText()); }
  private static UUID nullableUuid(JsonNode node,String key) { return node.required(key).isNull()?null:uuid(node,key); }
  private static Audit audit(JsonNode n) {
    return new Audit(n.required("version").longValue(),Instant.parse(n.required("createdAt").asText()),
        Instant.parse(n.required("updatedAt").asText()),n.required("createdBy").asText(),n.required("updatedBy").asText());
  }
  public static WorkerRegistration worker(String raw) {
    var n=parse(raw); var periods=new ArrayList<ActivePeriod>();
    for(var p:n.required("activePeriods")) periods.add(new ActivePeriod(uuid(p,"id"),LocalDate.parse(p.required("activeFrom").asText()),
        p.required("activeTo").isNull()?null:LocalDate.parse(p.required("activeTo").asText()),p.required("introducedVersion").longValue(),
        p.required("closedVersion").isNull()?null:p.required("closedVersion").longValue()));
    return new WorkerRegistration(uuid(n,"id"),uuid(n,"estateId"),n.required("workerCode").asText(),n.required("displayName").asText(),
        nullableUuid(n,"divisionId"),Provenance.valueOf(n.required("dataProvenance").asText()),periods,audit(n.required("audit")));
  }
  public static AttendanceDay day(String raw) {
    var n=parse(raw);var a=n.required("attribution");
    return new AttendanceDay(uuid(n,"id"),new AttendanceDay.Attribution(uuid(a,"estateId"),uuid(a,"workerRegistrationId"),
        LocalDate.parse(a.required("attendanceDate").asText()),a.required("estateTimezone").asText(),a.required("workerCodeSnapshot").asText(),
        nullableUuid(a,"divisionIdSnapshot"),a.required("workerVersionSnapshot").longValue(),uuid(a,"activePeriodId"),
        Provenance.valueOf(a.required("dataProvenance").asText())),AttendanceStatus.valueOf(n.required("status").asText()),audit(n.required("audit")));
  }
}
