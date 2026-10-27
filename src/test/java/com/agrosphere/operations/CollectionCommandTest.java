package com.agrosphere.operations;

import static org.junit.jupiter.api.Assertions.*;
import com.agrosphere.operations.application.CollectionCommands;
import com.agrosphere.operations.domain.Rules;
import org.junit.jupiter.api.Test;

class CollectionCommandTest {
  final String body="""
      {"tappingRecordId":"00000000-0000-0000-0000-000000000001","collectionReference":" lot-01 ",
       "collectedAt":"2026-10-27T10:00:00.123456000+05:30","wetLatexKg":125.5000,
       "weightEvidence":{"method":"SCALE","sourceReference":" source "},"dataProvenance":"SIMULATED"}
      """;

  @Test void canonicalHashUsesSemanticDecimalsTimeReferenceAndOptionalDefaults(){
    var first=CollectionCommands.parse("CREATE",body);
    var other=CollectionCommands.parse("CREATE",body.replace("125.5000","125.5").replace(" lot-01 ","LOT-01")
        .replace("2026-10-27T10:00:00.123456000+05:30","2026-10-27T04:30:00.123456Z")
        .replace("\"sourceReference\":\" source \"","\"sourceReference\":\"source\",\"instrumentReference\":null")
        .replace("\"dataProvenance\":\"SIMULATED\"","\"dataProvenance\":\"SIMULATED\",\"backdateReason\":null"));
    assertEquals(CollectionCommands.hash("/same",first),CollectionCommands.hash("/same",other));
    assertNotEquals(CollectionCommands.hash("/same",first),CollectionCommands.hash("/other",first));
  }
  @Test void duplicateUnknownAndTrailingJsonRejected(){
    for(String invalid:new String[]{body+"{}",body.replace("125.5000","125.5000,\"wetLatexKg\":1"),body.replace("\"wetLatexKg\":125.5000","\"workerId\":\"forged\",\"wetLatexKg\":125.5000")})
      assertThrows(Rules.Violation.class,()->CollectionCommands.parse("CREATE",invalid));
  }
  @Test void stringOrExcessPrecisionAndLossyTimestampsRejected(){
    for(String value:new String[]{"\"125.5\"","1.0001","0","NaN","null"})
      assertThrows(Rules.Violation.class,()->CollectionCommands.parse("CREATE",body.replace("125.5000",value)));
    assertThrows(Rules.Violation.class,()->CollectionCommands.parse("CREATE",body.replace(".123456000+05:30",".0000001Z")));
  }
  @Test void correctionNullAndOmissionHaveDifferentMeaning(){
    var absent=CollectionCommands.parse("CORRECT","{\"version\":1,\"reason\":\"reason\",\"wetLatexKg\":1}");
    var clear=CollectionCommands.parse("CORRECT","{\"version\":1,\"reason\":\"reason\",\"wetLatexKg\":1,\"backdateReason\":null}");
    assertNotEquals(CollectionCommands.hash("/same",absent),CollectionCommands.hash("/same",clear));
  }
}
