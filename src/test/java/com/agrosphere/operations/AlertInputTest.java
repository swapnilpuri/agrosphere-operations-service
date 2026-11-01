package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;
import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import com.agrosphere.operations.alerts.application.AlertRuntime;
import org.junit.jupiter.api.Test;

class AlertInputTest {
  @Test void equivalentIntegralJsonAndLargestInt64PreserveExactValues(){
    assertEquals(AlertRuntime.command("{\"attentionVersion\":1}",false),AlertRuntime.command("{\"attentionVersion\":1.0}",false));
    assertEquals(Long.MAX_VALUE,AlertRuntime.command("{\"attentionVersion\":9223372036854775807.0}",false).get("attentionVersion"));
  }
  @Test void fractionsOverflowAndCoercionCannotRoundIntoValidCommand(){
    for(String value:new String[]{"0","-1","1.5","9223372036854775808","9223372036854775807.1","true","null","\"1\""})
      assertThrows(Invalid.class,()->AlertRuntime.command("{\"attentionVersion\":"+value+"}",false));
  }
  @Test void unknownAndDuplicateFieldsAreRejectedBeforeReceiptReservation(){
    for(String body:new String[]{"[]","{}","{\"attentionVersion\":1,\"subject\":\"forged\"}","{\"attentionVersion\":1,\"attentionVersion\":1}"})
      assertThrows(Invalid.class,()->AlertRuntime.command(body,false));
  }
  @Test void acknowledgementRequiresBothDisplayedVersions(){
    assertThrows(Invalid.class,()->AlertRuntime.command("{\"attentionVersion\":1}",true));
    assertEquals(2,AlertRuntime.command("{\"attentionVersion\":1,\"expectedAlertVersion\":2}",true).get("expectedAlertVersion"));
  }
}
