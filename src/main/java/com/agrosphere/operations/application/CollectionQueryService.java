package com.agrosphere.operations.application;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class CollectionQueryService {
  private final CollectionQueryPort query;
  private final CollectionAccess access;
  private final CollectionService collections;
  public CollectionQueryService(CollectionQueryPort query,CollectionAccess access,CollectionService collections){this.query=query;this.access=access;this.collections=collections;}
  public String list(Map<String,List<String>> input){var f=CollectionFilters.parse(input,CollectionFilters.Mode.LIST);return query.list(f,access.readScope(f.estateId(),f.workerId()));}
  public String summary(Map<String,List<String>> input){var f=CollectionFilters.parse(input,CollectionFilters.Mode.SUMMARY);return query.summary(f,access.readScope(f.estateId(),f.workerId()));}
  public String history(UUID id,Map<String,List<String>> input){collections.read(id);return query.history(id,CollectionFilters.parse(input,CollectionFilters.Mode.PAGE),access.readScope(null,null));}
  public String measurements(UUID id,Map<String,List<String>> input){collections.read(id);return query.measurements(id,CollectionFilters.parse(input,CollectionFilters.Mode.PAGE),access.readScope(null,null));}
}
