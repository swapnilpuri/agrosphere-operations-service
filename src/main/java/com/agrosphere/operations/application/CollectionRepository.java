package com.agrosphere.operations.application;

import com.agrosphere.operations.domain.*;
import java.util.*;

/** Operations-local persistence. Writes must join one application transaction. */
public interface CollectionRepository {
  Optional<LatexCollection> find(UUID id,CollectionScope scope);
  List<LatexCollection> list(CollectionScope scope,int page,int size);
  long count(CollectionScope scope);
  void insert(LatexCollection value,CollectionScope scope);
  void save(long expectedVersion,LatexCollection value,CollectionScope scope);
  void append(DrcMeasurement measurement,CollectionScope scope);
  void append(CollectionRevision revision,CollectionScope scope);
  void append(CollectionAdjustment adjustment,CollectionScope scope);
  List<Map<String,Object>> history(UUID collectionId,CollectionScope scope,int page,int size);
  long historyCount(UUID collectionId,CollectionScope scope);
}
