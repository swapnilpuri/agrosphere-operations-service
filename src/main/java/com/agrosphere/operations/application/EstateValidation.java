package com.agrosphere.operations.application;

import java.util.UUID;

public interface EstateValidation {
  String validate(UUID estateId, UUID blockId);
}
