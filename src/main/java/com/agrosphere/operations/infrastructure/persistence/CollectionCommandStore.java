package com.agrosphere.operations.infrastructure.persistence;

import com.agrosphere.operations.domain.*;
import com.agrosphere.operations.application.CollectionReplayStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Durable actor/key reservations and immutable receipts in the Operations transaction. */
@Repository
public class CollectionCommandStore implements CollectionReplayStore {
  private final NamedParameterJdbcTemplate db;
  public CollectionCommandStore(NamedParameterJdbcTemplate db){this.db=db;}

  public Optional<Receipt> replay(String actor,UUID key,String hash){
    var values=db.query("SELECT request_hash,response_status,response_body::text,response_location FROM collection_command WHERE actor=:actor AND idempotency_key=:key",Map.of("actor",actor,"key",key),(r,n)->{
      if(!hash.equals(r.getString(1)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key used for a different normalized command");
      if(r.getObject(2)==null)throw new IllegalStateException("Incomplete visible command receipt");
      return new Receipt(r.getInt(2),r.getString(3),r.getString(4));
    });return values.stream().findFirst();
  }

  public boolean reserve(String actor,UUID key,String hash,String path,Instant now){
    db.getJdbcTemplate().execute("SET LOCAL lock_timeout='3s'");
    return db.update("INSERT INTO collection_command(actor,idempotency_key,request_hash,normalization_version,method,resource_path,created_at) VALUES (:actor,:key,:hash,1,'POST',:path,:now) ON CONFLICT DO NOTHING",
        new MapSqlParameterSource("actor",actor).addValue("key",key).addValue("hash",hash).addValue("path",path).addValue("now",Timestamp.from(now)))==1;
  }

  public Receipt complete(String actor,UUID key,UUID id,int status,String location,String body,Instant now){
    var p=new MapSqlParameterSource("actor",actor).addValue("key",key).addValue("id",id).addValue("status",status)
        .addValue("location",location).addValue("body",body).addValue("now",Timestamp.from(now));
    return db.queryForObject("UPDATE collection_command SET collection_id=:id,response_status=:status,response_location=:location,response_body=:body::jsonb,completed_at=:now WHERE actor=:actor AND idempotency_key=:key AND completed_at IS NULL RETURNING response_status,response_body::text,response_location",
        p,(r,n)->new Receipt(r.getInt(1),r.getString(2),r.getString(3)));
  }

  /** Only minimum metadata is read before object authorization; never returned as payload. */
  public Ownership ownership(UUID id){
    var rows=db.query("SELECT estate_id,worker_id FROM latex_collection WHERE id=:id",Map.of("id",id),(r,n)->new Ownership(r.getObject(1,UUID.class),r.getString(2)));
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Collection not found");return rows.getFirst();
  }

  public Execution execution(UUID recordId,boolean lock){
    var params=Map.of("id",recordId);
    var ids=db.query("SELECT assignment_id FROM tapping_record WHERE id=:id",params,(r,n)->r.getObject(1,UUID.class));
    if(ids.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Execution not found");
    // Assignment first, matching the tapping mutation lock order.
    var assignment=db.query("SELECT * FROM tapping_assignment WHERE id=:id"+(lock?" FOR SHARE":""),Map.of("id",ids.getFirst()),TappingStore.A).getFirst();
    var record=db.query("SELECT * FROM tapping_record WHERE id=:id"+(lock?" FOR SHARE":""),params,TappingStore.R).getFirst();
    return new Execution(record,assignment);
  }

  public CollectionRevision revision(LatexCollection root){
    return db.queryForObject("SELECT command_kind,recorded_at,recorded_by,correlation_id FROM collection_revision WHERE collection_id=:id AND version=:version",
        Map.of("id",root.id(),"version",root.version()),(r,n)->new CollectionRevision(root.id(),root.version(),
            com.agrosphere.operations.domain.CollectionRules.CommandKind.valueOf(r.getString(1)),root,
            r.getTimestamp(2).toInstant(),r.getString(3),r.getObject(4,UUID.class)));
  }
}
