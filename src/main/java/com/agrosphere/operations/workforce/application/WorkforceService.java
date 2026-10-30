package com.agrosphere.operations.workforce.application;

import static com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import com.agrosphere.operations.workforce.application.WorkforcePorts.*;
import com.agrosphere.operations.workforce.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class WorkforceService {
  private final Commands commands;private final Ownership ownership;private final References references;private final Identity identity;private final Clock clock;
  private final ObjectMapper json=new ObjectMapper();
  public WorkforceService(Commands commands,Ownership ownership,References references,Identity identity,Clock clock){
    this.commands=commands;this.ownership=ownership;this.references=references;this.identity=identity;this.clock=clock;
  }
  public Receipt mutate(CommandKind kind,UUID id,String key,String raw) {
    Actor actor=identity.current();boolean record=kind==CommandKind.RECORD;
    if(!actor.platform()&&Collections.disjoint(actor.roles(),record?Set.of("ESTATE_ADMIN","ESTATE_MANAGER","FIELD_SUPERVISOR"):Set.of("ESTATE_ADMIN","ESTATE_MANAGER")))
      throw new Denied("Workforce command denied");
    var body=WorkforceCommands.parse(kind,raw);UUID estate;
    boolean workerCommand=Set.of(CommandKind.REGISTER,CommandKind.PROFILE,CommandKind.PERIOD_CLOSED,CommandKind.PERIOD_OPENED).contains(kind);
    if(kind==CommandKind.REGISTER){estate=(UUID)body.get("estateId");developmentOnly((Provenance)body.get("dataProvenance"));}
    else if(kind==CommandKind.RECORD)estate=ownership.workerEstate((UUID)body.get("workerRegistrationId"));
    else estate=workerCommand?ownership.workerEstate(id):ownership.attendanceEstate(id);
    if(record)actor.requireRead(estate);else actor.requireManager(estate);
    String method=kind==CommandKind.PROFILE?"PATCH":"POST";String path=path(kind,id);UUID commandKey=WorkforceCommands.uuid(key);
    String hash=WorkforceCommands.hash(method,path,body);var scope=new ReadScope(Set.of(estate));
    return commands.execute(actor.subject(),commandKey,method,path,hash,(workers,days,evidence)->{
      UUID correlation;try{correlation=UUID.fromString(org.slf4j.MDC.get("correlationId"));}catch(Exception e){correlation=UUID.randomUUID();}
      var command=new Command(actor,clock,correlation);Aggregate root;
      if(kind==CommandKind.REGISTER){
        var master=references.activeEstate(estate);UUID division=(UUID)body.get("divisionId");if(division!=null)division=references.activeDivision(estate,division);
        var w=WorkerRegistration.register(UUID.randomUUID(),UUID.randomUUID(),master,(String)body.get("workerCode"),(String)body.get("displayName"),division,
            (LocalDate)body.get("activeFrom"),(Provenance)body.get("dataProvenance"),command);workers.insertWorker(w);root=w;
      }else if(workerCommand){
        var w=workers.lockWorker(id,scope);long version=(long)body.get("version");String reason=(String)body.get("reason");WorkerRegistration changed;
        if(kind==CommandKind.PROFILE){UUID division=(UUID)body.get("divisionId");if(division!=null)division=references.activeDivision(estate,division);
          changed=w.edit(version,(String)body.get("displayName"),body.containsKey("divisionId"),division,reason,command);
        }else if(kind==CommandKind.PERIOD_CLOSED){changed=w.closePeriod(version,(UUID)body.get("activePeriodId"),(LocalDate)body.get("activeTo"),reason,
            workers.nonvoidedAttendanceDates(w.id(),scope),references.estate(estate).timezone(),command);
        }else changed=w.openPeriod(version,UUID.randomUUID(),(LocalDate)body.get("activeFrom"),reason,references.activeEstate(estate),command);
        workers.updateWorker(changed,version);root=changed;
      }else if(kind==CommandKind.RECORD){
        var w=workers.lockWorker((UUID)body.get("workerRegistrationId"),scope);
        var d=AttendanceDay.record(UUID.randomUUID(),w,(LocalDate)body.get("attendanceDate"),(AttendanceStatus)body.get("status"),references.activeEstate(estate),(String)body.get("backdateReason"),command);
        days.insertDay(d);root=d;
      }else{
        var original=days.getDay(id,scope);var w=workers.lockWorker(original.attribution().workerRegistrationId(),scope);var d=days.getDay(id,scope);
        long version=(long)body.get("version");String reason=(String)body.get("reason");AttendanceDay changed;
        if(kind==CommandKind.CORRECT)changed=d.correct(version,(AttendanceStatus)body.get("status"),reason,command);
        else if(kind==CommandKind.VOID)changed=d.voidDay(version,reason,command);
        else changed=d.rerecord(version,w,(AttendanceStatus)body.get("status"),references.activeEstate(estate),reason,command);
        days.updateDay(changed,version);root=changed;
      }
      String reason=(String)body.get(kind==CommandKind.RECORD?"backdateReason":"reason");
      var revision=new Revision<>(root.id(),root.audit().version(),kind,reason,root.audit().updatedAt(),root.audit().updatedBy(),correlation,root);
      evidence.append(revision);WorkforceEventSnapshots.append(revision,evidence);
      boolean created=kind==CommandKind.REGISTER||kind==CommandKind.RECORD;
      var view=root instanceof WorkerRegistration w?WorkforceViews.worker(w):WorkforceViews.day((AttendanceDay)root);
      return new Receipt(actor.subject(),commandKey,method,path,hash,created?201:200,json.writeValueAsString(view),created?(workerCommand?"/api/v1/workers/":"/api/v1/attendance/days/")+root.id():null);
    });
  }
  private static String path(CommandKind kind,UUID id){return switch(kind){
    case REGISTER->"/api/v1/workers";case RECORD->"/api/v1/attendance/days";
    case PROFILE->"/api/v1/workers/"+id;case PERIOD_CLOSED->"/api/v1/workers/"+id+"/close-period";case PERIOD_OPENED->"/api/v1/workers/"+id+"/open-period";
    case CORRECT->"/api/v1/attendance/days/"+id+"/corrections";case VOID->"/api/v1/attendance/days/"+id+"/void";case RERECORD->"/api/v1/attendance/days/"+id+"/re-record";};}
}
