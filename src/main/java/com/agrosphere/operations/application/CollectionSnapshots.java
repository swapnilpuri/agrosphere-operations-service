package com.agrosphere.operations.application;

import com.agrosphere.operations.domain.*;
import java.util.*;

/** Explicit wire snapshot mapping; domain record layout is never serialized as the API. */
public final class CollectionSnapshots {
  private CollectionSnapshots() {}

  private static Map<String,Object> fields(Object... values) {
    var result=new LinkedHashMap<String,Object>();
    for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);
    return Collections.unmodifiableMap(result);
  }

  public static Map<String,Object> measurement(DrcMeasurement m) {
    if(m==null)return null;
    return fields("id",m.id(),"collectionId",m.collectionId(),"introducedVersion",m.introducedVersion(),
        "drcPercent",m.percent(),"measuredAt",m.measuredAt(),"method",m.method(),"sourceReference",m.sourceReference(),
        "sampleReference",m.sampleReference(),"instrumentReference",m.instrumentReference(),"dataProvenance",m.provenance(),
        "recordedAt",m.recordedAt(),"recordedBy",m.recordedBy());
  }

  public static Map<String,Object> view(LatexCollection c) {
    var a=c.attribution();var master=a.master();var w=c.weight();var calculation=c.calculation();
    return fields("id",c.id(),"tappingRecordId",a.tappingRecordId(),"assignmentId",a.assignmentId(),
        "estateId",a.estateId(),"blockId",a.blockId(),"divisionIdSnapshot",master.divisionId(),
        "workerId",a.workerId(),"tappingDate",a.tappingDate(),"estateTimezone",master.estateTimezone(),
        "estateVersionSnapshot",master.estateVersion(),"blockVersionSnapshot",master.blockVersion(),
        "masterValidatedAt",master.validatedAt(),"collectionReference",c.collectionReference(),
        "replacementOfCollectionId",c.replacementOfCollectionId(),"dataProvenance",c.provenance(),
        "collectedAt",w.collectedAt(),"collectionDate",c.collectionDate(),"wetLatexKg",w.wetLatexKg(),
        "weightEvidence",fields("method",w.evidence().method(),"sourceReference",w.evidence().sourceReference(),
            "instrumentReference",w.evidence().instrumentReference()),
        "backdateReason",w.backdateReason(),"currentMeasurement",measurement(c.measurement()),
        "status",c.status(),"dryRubberExactKg",calculation==null?null:calculation.exactKg(),
        "dryRubberKg",calculation==null?null:calculation.dryKg(),"calculationPolicy",calculation==null?null:calculation.policy(),
        "version",c.version(),"createdAt",c.createdAt(),"updatedAt",c.updatedAt(),"createdBy",c.createdBy(),"updatedBy",c.updatedBy());
  }
}
