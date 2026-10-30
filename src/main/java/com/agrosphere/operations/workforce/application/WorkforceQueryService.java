package com.agrosphere.operations.workforce.application;

import static com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import static com.agrosphere.operations.workforce.application.WorkforceViews.fields;
import com.agrosphere.operations.workforce.application.WorkforcePorts.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.util.MultiValueMap;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class WorkforceQueryService {
  private final Identity identity;private final Ownership ownership;private final Registrations workers;private final Attendance attendance;
  private final Queries query;private final References references;private final Clock clock;
  public WorkforceQueryService(Identity identity,Ownership ownership,Registrations workers,Attendance attendance,Queries query,References references,Clock clock){
    this.identity=identity;this.ownership=ownership;this.workers=workers;this.attendance=attendance;this.query=query;this.references=references;this.clock=clock;
  }
  private Actor current(boolean history) {
    var actor=identity.current();var allowed=new HashSet<>(Set.of("ESTATE_ADMIN","ESTATE_MANAGER","AUDITOR"));if(!history)allowed.add("FIELD_SUPERVISOR");
    if(!actor.platform()&&Collections.disjoint(actor.roles(),allowed))throw new Denied("Workforce read denied");return actor;
  }
  private void filters(MultiValueMap<String,String> params,String... allowed){
    require(Set.of(allowed).containsAll(params.keySet()),"Unknown workforce filter");
    require(params.values().stream().allMatch(v->v!=null&&v.size()==1&&v.getFirst()!=null&&!v.getFirst().isEmpty()),"Invalid/repeated workforce filter");
  }
  private UUID id(MultiValueMap<String,String> p,String key,boolean required){String value=p.getFirst(key);if(value==null&&!required)return null;return WorkforceCommands.uuid(value);}
  private LocalDate date(MultiValueMap<String,String> p,String key,boolean required){String value=p.getFirst(key);if(value==null&&!required)return null;return WorkforceCommands.date(value);}
  private int integer(MultiValueMap<String,String> p,String key,int fallback){
    String raw=p.getFirst(key);if(raw==null)return fallback;
    try{require(raw.matches("[0-9]+"),"Invalid paging input");return Integer.parseInt(raw);}catch(NumberFormatException e){throw new Invalid("Invalid paging input");}
  }
  private int[] page(MultiValueMap<String,String> p){int page=integer(p,"page",0),size=integer(p,"size",20);require(page>=0&&size>=1&&size<=100&&(long)page*size<=Integer.MAX_VALUE,"Invalid page");return new int[]{page,size};}
  private Provenance provenance(MultiValueMap<String,String> p,boolean required){String v=p.getFirst("provenance");if(v==null&&!required)return null;return WorkforceCommands.enumeration(Provenance.class,v);}
  private ReadScope scope(Actor actor,UUID estate,boolean history){if(history)actor.requireHistory(estate);else actor.requireRead(estate);return new ReadScope(Set.of(estate));}
  public Object worker(UUID worker,MultiValueMap<String,String> p){var actor=current(false);filters(p);var scope=scope(actor,ownership.workerEstate(worker),false);return WorkforceViews.worker(workers.getWorker(worker,scope));}
  public Object day(UUID day,MultiValueMap<String,String> p){var actor=current(false);filters(p);var scope=scope(actor,ownership.attendanceEstate(day),false);return WorkforceViews.day(attendance.getDay(day,scope));}
  public Object workers(MultiValueMap<String,String> p){
    var actor=current(false);filters(p,"estateId","activeOn","code","name","divisionId","provenance","page","size");var estate=id(p,"estateId",true);var scope=scope(actor,estate,false);var page=page(p);
    var active=date(p,"activeOn",false);if(active!=null)require(!active.isAfter(references.estate(estate).today(clock.instant())),"Future activeOn query denied");
    String code=p.getFirst("code");if(code!=null)code=code(code);String prefix=p.getFirst("name");if(prefix!=null)prefix=text(prefix,100);
    var f=new WorkerFilter(estate,active,code,prefix,id(p,"divisionId",false),provenance(p,false),page[0],page[1]);
    return WorkforceViews.page(query.workers(f,scope).stream().map(WorkforceViews::worker).toList(),page[0],page[1],query.workerCount(f,scope));
  }
  public Object days(MultiValueMap<String,String> p){
    var actor=current(false);filters(p,"estateId","from","to","workerRegistrationId","status","provenance","page","size");var estate=id(p,"estateId",true);var scope=scope(actor,estate,false);var page=page(p);
    var from=date(p,"from",true);var to=date(p,"to",true);require(!from.isAfter(to)&&ChronoUnit.DAYS.between(from,to)<=366,"Invalid attendance date range");
    var worker=id(p,"workerRegistrationId",false);if(worker!=null)actor.requireRead(ownership.workerEstate(worker));
    var status=p.getFirst("status")==null?null:WorkforceCommands.enumeration(AttendanceStatus.class,p.getFirst("status"));
    var f=new DayFilter(estate,from,to,worker,status,provenance(p,false),page[0],page[1]);
    return WorkforceViews.page(query.days(f,scope).stream().map(WorkforceViews::day).toList(),page[0],page[1],query.dayCount(f,scope));
  }
  public Object roster(MultiValueMap<String,String> p){
    var actor=current(false);filters(p,"estateId","date","divisionId","provenance","page","size");var estate=id(p,"estateId",true);var scope=scope(actor,estate,false);var page=page(p);
    var date=date(p,"date",true);var master=references.estate(estate);require(!date.isAfter(master.today(clock.instant())),"Future roster date denied");
    var division=id(p,"divisionId",false);var provenance=provenance(p,true);var counts=query.coverage(estate,date,division,provenance,scope);
    var values=query.roster(estate,date,division,provenance,page[0],page[1],scope).stream().map(r->fields("worker",WorkforceViews.roster(r.worker()),"attendance",r.attendance()==null?null:WorkforceViews.day(r.attendance()))).toList();
    var out=WorkforceViews.page(values,page[0],page[1],counts.get("eligibleCount"));out.putAll(counts);
    out.putAll(fields("estateId",estate.toString(),"date",date.toString(),"estateTimezone",master.timezone().getId(),"dataProvenance",provenance.name()));return out;
  }
  public Object history(UUID id,boolean worker,MultiValueMap<String,String> p){
    var actor=current(true);filters(p,"page","size");var estate=worker?ownership.workerEstate(id):ownership.attendanceEstate(id);var scope=scope(actor,estate,true);var page=page(p);
    var rows=query.history(id,worker,scope,page[0],page[1]).stream().map(r->fields("id",r.aggregateId().toString(),"version",r.version(),"commandKind",r.commandKind().name(),
        "reason",r.reason(),"recordedAt",r.recordedAt().toString(),"recordedBy",r.recordedBy(),"correlationId",r.correlationId().toString(),
        "snapshot",r.snapshot() instanceof com.agrosphere.operations.workforce.domain.WorkerRegistration w?WorkforceViews.worker(w):WorkforceViews.day((com.agrosphere.operations.workforce.domain.AttendanceDay)r.snapshot()))).toList();
    return WorkforceViews.page(rows,page[0],page[1],query.historyCount(id,worker,scope));
  }
}
