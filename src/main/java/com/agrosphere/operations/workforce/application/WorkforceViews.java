package com.agrosphere.operations.workforce.application;

import com.agrosphere.operations.workforce.domain.*;
import com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import java.util.*;

/** Explicit minimal wire projections. Never serialize a private aggregate directly to HTTP/events. */
public final class WorkforceViews {
  private WorkforceViews() {}
  public static Map<String,Object> fields(Object... pairs) {
    var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
  }
  private static String id(UUID value){return value==null?null:value.toString();}
  public static Map<String,Object> worker(WorkerRegistration w) {
    return fields("id",id(w.id()),"estateId",id(w.estateId()),"workerCode",w.workerCode(),"displayName",w.displayName(),
        "divisionId",id(w.divisionId()),"dataProvenance",w.dataProvenance().name(),"activePeriods",w.activePeriods().stream().map(p->fields(
        "id",id(p.id()),"activeFrom",p.activeFrom().toString(),"activeTo",p.activeTo()==null?null:p.activeTo().toString(),
        "introducedVersion",p.introducedVersion(),"closedVersion",p.closedVersion())).toList(),"version",w.audit().version(),
        "createdAt",w.audit().createdAt().toString(),"updatedAt",w.audit().updatedAt().toString());
  }
  public static Map<String,Object> day(AttendanceDay d) {
    var a=d.attribution();return fields("id",id(d.id()),"estateId",id(a.estateId()),"workerRegistrationId",id(a.workerRegistrationId()),
        "attendanceDate",a.attendanceDate().toString(),"estateTimezone",a.estateTimezone(),"workerCodeSnapshot",a.workerCodeSnapshot(),
        "divisionIdSnapshot",id(a.divisionIdSnapshot()),"workerVersionSnapshot",a.workerVersionSnapshot(),"activePeriodId",id(a.activePeriodId()),
        "dataProvenance",a.dataProvenance().name(),"status",d.status().name(),"version",d.audit().version(),
        "createdAt",d.audit().createdAt().toString(),"updatedAt",d.audit().updatedAt().toString());
  }
  public static Map<String,Object> roster(WorkerRegistration w) {
    return fields("id",id(w.id()),"code",w.workerCode(),"displayName",w.displayName(),"divisionId",id(w.divisionId()),
        "provenance",w.dataProvenance().name(),"version",w.audit().version());
  }
  public static Map<String,Object> page(List<?> values,int page,int size,long total) {
    return fields("content",values,"page",page,"size",size,"totalElements",total);
  }
}
