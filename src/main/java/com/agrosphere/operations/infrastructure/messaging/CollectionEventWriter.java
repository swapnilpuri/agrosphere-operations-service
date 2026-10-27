package com.agrosphere.operations.infrastructure.messaging;

import com.agrosphere.operations.application.CollectionSnapshots;
import com.agrosphere.operations.domain.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Additive v2 outbox writer. The v1 Events signature and behavior remain untouched. */
@Component
public class CollectionEventWriter implements com.agrosphere.operations.application.CollectionEventPort {
  private final JdbcTemplate db;
  private final ObjectMapper json=new ObjectMapper();
  public CollectionEventWriter(JdbcTemplate db){this.db=db;}

  public void append(String command,LatexCollection c,CollectionAdjustment adjustment,UUID correlation){
    var type=switch(command){case "CREATE"->"LatexCollectionRecorded";case "DRC"->"DrcMeasurementRecorded";case "CORRECT"->"LatexCollectionAdjusted";case "VOID"->"LatexCollectionVoided";default->throw new IllegalArgumentException();};
    var payload=new LinkedHashMap<String,Object>();payload.put("collection",CollectionSnapshots.view(c));
    if(command.equals("CREATE"))payload.put("reason",c.weight().backdateReason());
    else if(command.equals("DRC"))payload.put("measurementId",c.measurement().id());
    else{payload.put("previousVersion",adjustment.before().version());payload.put("adjustment",Map.of("id",adjustment.id(),"kind",command,
        "beforeVersion",adjustment.before().version(),"afterVersion",adjustment.after().version(),"reason",adjustment.reason(),
        "approvedAt",adjustment.approvedAt(),"approvedBy",adjustment.approvedBy(),"correlationId",correlation));}
    var id=UUID.randomUUID();var event=new LinkedHashMap<String,Object>();
    event.put("eventId",id);event.put("eventType",type);event.put("eventVersion",2);event.put("timestamp",c.updatedAt());
    event.put("source","agrosphere-operations-service");event.put("correlationId",correlation);event.put("estateId",c.attribution().estateId());
    event.put("blockId",c.attribution().blockId());event.put("dataProvenance",c.provenance());event.put("aggregateId",c.id());event.put("aggregateVersion",c.version());event.put("payload",payload);
    db.update("INSERT INTO outbox_event(event_id,aggregate_id,event_type,event_version,correlation_id,occurred_at,payload) VALUES (?,?,?,2,?,?,?::jsonb)",id,c.id(),type,correlation,Timestamp.from(c.updatedAt()),json.writeValueAsString(event));
  }
}
