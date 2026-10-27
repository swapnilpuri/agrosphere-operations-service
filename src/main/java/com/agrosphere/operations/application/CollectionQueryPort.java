package com.agrosphere.operations.application;
import java.util.UUID;
/** JSON is returned directly from exact numeric SQL, with one snapshot per page/count. */
public interface CollectionQueryPort {
  String list(CollectionFilters filter,CollectionScope scope);
  String summary(CollectionFilters filter,CollectionScope scope);
  String history(UUID id,CollectionFilters filter,CollectionScope scope);
  String measurements(UUID id,CollectionFilters filter,CollectionScope scope);
}
