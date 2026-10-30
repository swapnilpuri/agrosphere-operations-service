package com.agrosphere.operations.workforce.application;

import static com.agrosphere.operations.workforce.application.WorkforceViews.fields;
import com.agrosphere.operations.workforce.application.WorkforcePorts.*;
import com.agrosphere.operations.workforce.domain.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Explicit allowlist for minimal workforce/v1 events. No private snapshot serialization. */
public final class WorkforceEventSnapshots {
  private static final ObjectMapper JSON=new ObjectMapper();
  private WorkforceEventSnapshots(){}
  public static void append(Revision<?> revision,Evidence evidence) {
    var root=revision.snapshot();boolean worker=root instanceof WorkerRegistration;var kind=revision.commandKind();
    boolean created=kind==CommandKind.REGISTER||kind==CommandKind.RECORD;
    UUID estate;String provenance;Map<String,Object> state;
    if(root instanceof WorkerRegistration w){
      estate=w.estateId();provenance=w.dataProvenance().name();
      state=fields("registrationId",w.id().toString(),"estateId",estate.toString(),"dataProvenance",provenance,"version",w.audit().version(),
          "divisionId",w.divisionId()==null?null:w.divisionId().toString(),"activePeriods",w.activePeriods().stream().map(p->fields("id",p.id().toString(),
          "activeFrom",p.activeFrom().toString(),"activeTo",p.activeTo()==null?null:p.activeTo().toString())).toList());
    }else{
      var d=(AttendanceDay)root;var a=d.attribution();estate=a.estateId();provenance=a.dataProvenance().name();
      state=fields("attendanceId",d.id().toString(),"estateId",estate.toString(),"dataProvenance",provenance,"version",d.audit().version(),
          "workerRegistrationId",a.workerRegistrationId().toString(),"attendanceDate",a.attendanceDate().toString(),"estateTimezone",a.estateTimezone(),
          "divisionIdSnapshot",a.divisionIdSnapshot()==null?null:a.divisionIdSnapshot().toString(),"activePeriodId",a.activePeriodId().toString(),
          "workerVersionSnapshot",a.workerVersionSnapshot(),"status",d.status().name());
    }
    String type=worker?(created?"WorkerRegistrationCreated":"WorkerRegistrationChanged"):(created?"WorkerAttendanceRecorded":"WorkerAttendanceChanged");
    var payload=fields("snapshot",state);if(!created){payload.put("previousVersion",root.audit().version()-1);payload.put("changeKind",kind.name());}
    UUID id=UUID.randomUUID();var envelope=fields("eventId",id.toString(),"eventType",type,"eventVersion",1,"timestamp",revision.recordedAt().toString(),
        "source","agrosphere-operations-service","correlationId",revision.correlationId().toString(),"estateId",estate.toString(),"dataProvenance",provenance,
        "aggregateId",root.id().toString(),"aggregateVersion",root.audit().version(),"payload",payload);
    evidence.appendEvent(id,type,root.audit().version(),JSON.writeValueAsString(envelope));
  }
}
