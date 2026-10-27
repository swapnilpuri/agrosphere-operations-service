package com.agrosphere.operations.application;

import static com.agrosphere.operations.domain.Rules.require;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public record CollectionFilters(UUID estateId,UUID divisionId,UUID blockId,UUID tappingRecordId,String workerId,
    String collectionReference,LocalDate from,LocalDate to,String status,String provenance,String groupBy,int page,int size){
  public enum Mode{LIST,SUMMARY,PAGE}
  public static CollectionFilters parse(Map<String,List<String>> input,Mode mode){
    var allowed=new HashSet<>(List.of("page","size"));
    if(mode!=Mode.PAGE)allowed.addAll(List.of("estateId","divisionId","blockId","tappingRecordId","workerId","collectionReference","from","to","dataProvenance"));
    if(mode==Mode.LIST)allowed.add("status");if(mode==Mode.SUMMARY)allowed.add("groupBy");
    require(allowed.containsAll(input.keySet()),"Unsupported collection query parameter");
    var values=new HashMap<String,String>();input.forEach((k,v)->{require(v.size()==1,"Duplicate query parameter");values.put(k,v.getFirst());});
    try{
      var from=values.containsKey("from")?LocalDate.parse(values.get("from")):null;
      var to=values.containsKey("to")?LocalDate.parse(values.get("to")):null;
      require((from==null)==(to==null),"Date bounds must be paired");
      require(mode!=Mode.SUMMARY||from!=null,"Summary date bounds required");
      if(from!=null)require(!from.isAfter(to)&&ChronoUnit.DAYS.between(from,to)<=366,"Date range exceeds 366 days or is reversed");
      int page=Integer.parseInt(values.getOrDefault("page","0")),size=Integer.parseInt(values.getOrDefault("size","20"));
      require(page>=0&&size>=1&&size<=100,"Invalid page or size");
      var status=values.get("status");if(status!=null)require(Set.of("PENDING_DRC","CALCULATED","VOIDED").contains(status),"Invalid status");
      var provenance=values.get("dataProvenance");if(provenance!=null)require(Set.of("REAL","SIMULATED").contains(provenance),"Invalid provenance");
      var group=values.getOrDefault("groupBy","total");require(Set.of("total","estate","division","block","worker","date").contains(group),"Invalid grouping");
      String worker=values.get("workerId");if(worker!=null)require(!worker.isBlank()&&worker.length()<=200,"Invalid worker filter");
      return new CollectionFilters(id(values,"estateId"),id(values,"divisionId"),id(values,"blockId"),id(values,"tappingRecordId"),worker,
          values.containsKey("collectionReference")?com.agrosphere.operations.domain.CollectionRules.reference(values.get("collectionReference")):null,
          from,to,status,provenance,group,page,size);
    }catch(IllegalArgumentException|java.time.DateTimeException ex){throw new com.agrosphere.operations.domain.Rules.Violation("Invalid collection query value");}
  }
  private static UUID id(Map<String,String> values,String key){return values.containsKey(key)?CollectionCommands.uuid(values.get(key)):null;}
}
