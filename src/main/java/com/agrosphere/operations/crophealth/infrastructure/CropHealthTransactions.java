package com.agrosphere.operations.crophealth.infrastructure;

import com.agrosphere.operations.crophealth.application.CropHealthPorts.*;
import com.agrosphere.operations.crophealth.domain.CropHealthRules.Conflict;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Public entry is called only after current feature/estate authorization, including replay. */
@Component
public class CropHealthTransactions {
  private final JdbcCropHealthStore store;
  public CropHealthTransactions(JdbcCropHealthStore store){this.store=store;}
  @Transactional public Receipt execute(String actor,UUID key,String path,String hash,Supplier<Receipt> work) {
    return execute(actor,key,path,hash,"MANAGER",work);
  }
  @Transactional public Receipt execute(String actor,UUID key,String path,String hash,String permissionClass,Supplier<Receipt> work) {
    var receipt=store.reserve(actor,key,path,hash,permissionClass);if(receipt.isPresent())return receipt.get();
    var result=work.get();
    if(!actor.equals(result.actor())||!key.equals(result.key())||!path.equals(result.path())||!hash.equals(result.normalizedRequestHash()))throw new Conflict("Command result identity mismatch");
    store.saveReceipt(result);return result;
  }
}
