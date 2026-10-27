package com.agrosphere.operations.application;

import static com.agrosphere.operations.domain.CollectionRules.*;

import com.agrosphere.operations.domain.*;
import com.agrosphere.operations.application.CollectionReplayStore.Receipt;
import java.time.*;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CollectionService {
  private final CollectionRepository repository;
  private final CollectionReplayStore commands;
  private final CollectionReferenceValidation references;
  private final CollectionEventPort events;
  private final CollectionAccess access;
  private final Access identity;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final ObjectMapper json=new ObjectMapper();

  public CollectionService(CollectionRepository repository,CollectionReplayStore commands,CollectionReferenceValidation references,
      CollectionEventPort events,CollectionAccess access,Access identity,Clock clock,PlatformTransactionManager manager){
    this.repository=repository;this.commands=commands;this.references=references;this.events=events;this.access=access;this.identity=identity;this.clock=clock;tx=new TransactionTemplate(manager);
  }

  private CollectionAccess.Operation operation(String command){return switch(command){case "CREATE"->CollectionAccess.Operation.CREATE;case "DRC"->CollectionAccess.Operation.DRC;case "CORRECT"->CollectionAccess.Operation.CORRECT;case "VOID"->CollectionAccess.Operation.VOID;default->throw new IllegalArgumentException();};}
  private void authorize(String command,UUID id,Map<String,Object> body){
    if(command.equals("CREATE")){
      var execution=commands.execution((UUID)body.get("tappingRecordId"),false);access.authorize(operation(command),execution.record().estateId(),execution.record().workerId());
      if(body.containsKey("replacementOfCollectionId"))access.authorize(CollectionAccess.Operation.REPLACE,execution.record().estateId(),execution.record().workerId());
    }else{var context=commands.ownership(id);access.authorize(operation(command),context.estate(),context.worker());}
  }

  public LatexCollection read(UUID id){
    var context=commands.ownership(id);access.authorize(CollectionAccess.Operation.READ,context.estate(),context.worker());
    return repository.find(id,access.readScope(null,null)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Collection not found"));
  }

  public Receipt mutate(String command,UUID id,String key,String raw){
    var body=CollectionCommands.parse(command,raw);var actor=identity.actor();var uuid=CollectionCommands.uuid(key);
    var path=CollectionCommands.BASE+(command.equals("CREATE")?"":"/"+id+switch(command){case "DRC"->"/drc-measurements";case "CORRECT"->"/corrections";case "VOID"->"/void";default->throw new IllegalArgumentException();});
    var hash=CollectionCommands.hash(path,body);authorize(command,id,body);
    var replay=commands.replay(actor,uuid,hash);if(replay.isPresent())return replay.get();
    LatexCollection.MasterSnapshot master;
    if(command.equals("CREATE")){var execution=commands.execution((UUID)body.get("tappingRecordId"),false);master=references.validateCollection(execution.record().estateId(),execution.record().blockId());}
    else master=null;
    try{return tx.execute(status->{
      var applied=now(clock);
      if(!commands.reserve(actor,uuid,hash,path,applied))return commands.replay(actor,uuid,hash).orElseThrow();
      authorize(command,id,body); // Current trusted claims and local context rechecked inside write transaction.
      var correlation=org.slf4j.MDC.get("correlationId");var correlationId=correlation==null?UUID.randomUUID():CollectionCommands.uuid(correlation);
      LatexCollection before=null,after;DrcMeasurement measurement=null;CollectionAdjustment adjustment=null;
      CollectionScope scope;
      if(command.equals("CREATE")){
        var execution=commands.execution((UUID)body.get("tappingRecordId"),true);
        var replaces=body.containsKey("replacementOfCollectionId")?read((UUID)body.get("replacementOfCollectionId")):null;
        after=LatexCollection.create(UUID.randomUUID(),execution.record(),execution.assignment(),master,(String)body.get("collectionReference"),
            CollectionCommands.weight(body,null),(Provenance)body.get("dataProvenance"),replaces,Clock.fixed(applied,ZoneOffset.UTC),actor);
        scope=new CollectionScope(List.of(after.attribution().estateId()),identity.role("FIELD_WORKER")&&!identity.admin()&&!identity.role("ESTATE_ADMIN","ESTATE_MANAGER","FIELD_SUPERVISOR")?actor:null);
        repository.insert(after,scope);
      }else{
        var ownership=commands.ownership(id);scope=new CollectionScope(List.of(ownership.estate()),null);
        before=repository.find(id,scope).orElseThrow();long version=(long)body.get("version");
        if(before.version()!=version)throw new ResponseStatusException(HttpStatus.CONFLICT,"Collection version changed");
        var fixed=Clock.fixed(applied,ZoneOffset.UTC);
        if(command.equals("DRC")){
          measurement=CollectionCommands.measurement(body.get("measurement"),before,applied,actor);after=before.recordDrc(version,measurement,fixed,actor);
        }else if(command.equals("CORRECT")){
          measurement=body.containsKey("replacementMeasurement")?CollectionCommands.measurement(body.get("replacementMeasurement"),before,applied,actor):null;
          after=before.correct(version,CollectionCommands.weight(body,before),measurement,(String)body.get("reason"),fixed,actor);
        }else after=before.voidCollection(version,(String)body.get("reason"),fixed,actor);
        if(measurement!=null)repository.append(measurement,scope);
        repository.save(version,after,scope);
      }
      var revision=new CollectionRevision(after.id(),after.version(),CommandKind.valueOf(command),after,applied,actor,correlationId);
      repository.append(revision,scope);
      if(command.equals("CORRECT")||command.equals("VOID")){
        var previous=commands.revision(before);
        adjustment=new CollectionAdjustment(UUID.randomUUID(),previous,revision,(String)body.get("reason"),applied,actor,correlationId);repository.append(adjustment,scope);
      }
      events.append(command,after,adjustment,correlationId);
      return commands.complete(actor,uuid,after.id(),command.equals("CREATE")?201:200,command.equals("CREATE")?CollectionCommands.BASE+"/"+after.id():null,json.writeValueAsString(CollectionSnapshots.view(after)),applied);
    });}catch(ConcurrencyFailureException|DuplicateKeyException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"Concurrent collection command; retry with the original key");}
  }
}
