package com.agrosphere.operations.workforce.infrastructure;

import com.agrosphere.operations.workforce.application.WorkforcePorts.*;
import com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Authorization is mandatory in the application before this internal transaction boundary. */
@Component
public class WorkforceTransactions implements Commands {
  private final JdbcWorkforceStore store;
  public WorkforceTransactions(JdbcWorkforceStore store) { this.store=store; }
  @Override @Transactional
  public Receipt execute(String actor,UUID key,String method,String path,String hash,Work work) {
    var existing=store.reserve(actor,key,method,path,hash);
    if(existing.isPresent())return existing.get();
    var receipt=work.apply(store,store,store);
    if(!actor.equals(receipt.actor())||!key.equals(receipt.key())||!method.equals(receipt.method())||!path.equals(receipt.path())
        ||!hash.equals(receipt.normalizedRequestHash())) throw new Conflict("Command result identity mismatch");
    // Own completion here, never in the callback; deferred constraints reject an incomplete commit.
    store.saveReceipt(receipt);
    return receipt;
  }
}
