package com.agrosphere.operations.application;

import com.agrosphere.operations.domain.*;
import java.time.Instant;
import java.util.*;

public interface CollectionReplayStore {
  record Receipt(int status,String body,String location){}
  record Execution(TappingRecord record,TappingAssignment assignment){}
  record Ownership(UUID estate,String worker){}
  Optional<Receipt> replay(String actor,UUID key,String hash);
  boolean reserve(String actor,UUID key,String hash,String path,Instant now);
  Receipt complete(String actor,UUID key,UUID id,int status,String location,String body,Instant now);
  Ownership ownership(UUID id);
  Execution execution(UUID recordId,boolean lock);
  CollectionRevision revision(LatexCollection root);
}
