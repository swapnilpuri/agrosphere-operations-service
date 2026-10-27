package com.agrosphere.operations.application;

import com.agrosphere.operations.domain.*;
import java.util.UUID;

public interface CollectionEventPort {
  void append(String command,LatexCollection value,CollectionAdjustment adjustment,UUID correlationId);
}
