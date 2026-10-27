package com.agrosphere.operations.infrastructure.persistence;

import static com.agrosphere.operations.domain.CollectionRules.*;

import com.agrosphere.operations.application.*;
import com.agrosphere.operations.domain.*;
import com.agrosphere.operations.domain.LatexCollection.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Scoped JDBC adapter. No external database access or sibling runtime dependencies. */
@Repository
public class CollectionStore implements CollectionRepository {
  private final NamedParameterJdbcTemplate db;
  private final ObjectMapper json=tools.jackson.databind.json.JsonMapper.builder()
      .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
  private static final String COLUMNS="id,tapping_record_id,assignment_id,estate_id,block_id,division_id_snapshot,worker_id,tapping_date,estate_timezone,estate_version_snapshot,block_version_snapshot,master_validated_at,collection_reference,replacement_of_collection_id,collected_at,collection_date,wet_latex_kg,weight_method,weight_source_reference,weight_instrument_reference,backdate_reason,data_provenance,status,selected_measurement_id,dry_rubber_exact_kg,dry_rubber_kg,calculation_policy,version,created_at,updated_at,created_by,updated_by";
  private static final String MUTABLE="collected_at,collection_date,wet_latex_kg,weight_method,weight_source_reference,weight_instrument_reference,backdate_reason,status,selected_measurement_id,dry_rubber_exact_kg,dry_rubber_kg,calculation_policy,version,updated_at,updated_by";
  private static final String SELECT="""
      SELECT c.*,r.completed_at AS execution_completed_at,
       m.id AS m_id,m.introduced_version AS m_version,m.percent AS m_percent,m.measured_at AS m_measured_at,
       m.method AS m_method,m.source_reference AS m_source,m.sample_reference AS m_sample,
       m.instrument_reference AS m_instrument,m.data_provenance AS m_provenance,m.recorded_at AS m_recorded_at,m.recorded_by AS m_recorded_by
      FROM latex_collection c JOIN tapping_record r ON r.id=c.tapping_record_id
      LEFT JOIN drc_measurement m ON m.collection_id=c.id AND m.id=c.selected_measurement_id
      """;

  public CollectionStore(NamedParameterJdbcTemplate db){this.db=db;}

  private void transaction(){
    if(!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Collection writes require an application transaction");
  }

  private String scope(CollectionScope scope,MapSqlParameterSource parameters) {
    Objects.requireNonNull(scope,"Trusted scope required");
    String sql="1=1";
    if(scope.estateIds()!=null) {
      if(scope.estateIds().isEmpty())return "FALSE";
      sql+=" AND c.estate_id IN (:scopeEstates)";parameters.addValue("scopeEstates",scope.estateIds());
    }
    if(scope.workerId()!=null){sql+=" AND c.worker_id=:scopeWorker";parameters.addValue("scopeWorker",scope.workerId());}
    return sql;
  }

  private static UUID uuid(ResultSet row,String name)throws SQLException{return row.getObject(name,UUID.class);}
  private static Instant time(ResultSet row,String name)throws SQLException{return row.getTimestamp(name).toInstant();}

  private final RowMapper<LatexCollection> row=(r,n)->{
    var master=new MasterSnapshot(uuid(r,"estate_id"),uuid(r,"block_id"),uuid(r,"division_id_snapshot"),
        r.getString("estate_timezone"),r.getLong("estate_version_snapshot"),r.getLong("block_version_snapshot"),time(r,"master_validated_at"));
    var attribution=new Attribution(uuid(r,"tapping_record_id"),uuid(r,"assignment_id"),uuid(r,"estate_id"),
        uuid(r,"block_id"),r.getString("worker_id"),r.getObject("tapping_date",LocalDate.class),time(r,"execution_completed_at"),master);
    var weight=new WeightObservation(time(r,"collected_at"),r.getBigDecimal("wet_latex_kg"),
        new WeightEvidence(WeightMethod.valueOf(r.getString("weight_method")),r.getString("weight_source_reference"),r.getString("weight_instrument_reference")),r.getString("backdate_reason"));
    DrcMeasurement m=uuid(r,"m_id")==null?null:new DrcMeasurement(uuid(r,"m_id"),uuid(r,"id"),r.getLong("m_version"),
        r.getBigDecimal("m_percent"),time(r,"m_measured_at"),DrcMethod.valueOf(r.getString("m_method")),r.getString("m_source"),
        r.getString("m_sample"),r.getString("m_instrument"),Provenance.valueOf(r.getString("m_provenance")),time(r,"m_recorded_at"),r.getString("m_recorded_by"));
    Calculation calculation=r.getBigDecimal("dry_rubber_exact_kg")==null?null:
        new Calculation(r.getBigDecimal("dry_rubber_exact_kg"),r.getBigDecimal("dry_rubber_kg"),r.getString("calculation_policy"));
    return new LatexCollection(uuid(r,"id"),attribution,r.getString("collection_reference"),uuid(r,"replacement_of_collection_id"),
        weight,Provenance.valueOf(r.getString("data_provenance")),Status.valueOf(r.getString("status")),m,calculation,
        r.getLong("version"),time(r,"created_at"),time(r,"updated_at"),r.getString("created_by"),r.getString("updated_by"));
  };

  public Optional<LatexCollection> find(UUID id,CollectionScope scope){
    var params=new MapSqlParameterSource("id",id);
    var rows=db.query(SELECT+" WHERE c.id=:id AND "+scope(scope,params),params,row);
    return rows.stream().findFirst();
  }

  private void page(int page,int size){
    if(page<0||size<1||size>100)throw new IllegalArgumentException("Invalid pagination");
  }

  public List<LatexCollection> list(CollectionScope scope,int page,int size){
    page(page,size);var params=new MapSqlParameterSource("limit",size).addValue("offset",(long)page*size);
    return db.query(SELECT+" WHERE "+scope(scope,params)+" ORDER BY c.collected_at DESC,c.id ASC LIMIT :limit OFFSET :offset",params,row);
  }

  public long count(CollectionScope scope){
    var params=new MapSqlParameterSource();return db.queryForObject("SELECT count(*) FROM latex_collection c WHERE "+scope(scope,params),params,Long.class);
  }

  private MapSqlParameterSource parameters(LatexCollection c){
    var a=c.attribution();var m=a.master();var w=c.weight();var calc=c.calculation();
    return new MapSqlParameterSource("id",c.id()).addValue("tapping_record_id",a.tappingRecordId()).addValue("assignment_id",a.assignmentId())
        .addValue("estate_id",a.estateId()).addValue("block_id",a.blockId()).addValue("division_id_snapshot",m.divisionId())
        .addValue("worker_id",a.workerId()).addValue("tapping_date",a.tappingDate()).addValue("estate_timezone",m.estateTimezone())
        .addValue("estate_version_snapshot",m.estateVersion()).addValue("block_version_snapshot",m.blockVersion())
        .addValue("master_validated_at",Timestamp.from(m.validatedAt())).addValue("collection_reference",c.collectionReference())
        .addValue("replacement_of_collection_id",c.replacementOfCollectionId()).addValue("collected_at",Timestamp.from(w.collectedAt()))
        .addValue("collection_date",c.collectionDate()).addValue("wet_latex_kg",w.wetLatexKg()).addValue("weight_method",w.evidence().method().name())
        .addValue("weight_source_reference",w.evidence().sourceReference()).addValue("weight_instrument_reference",w.evidence().instrumentReference())
        .addValue("backdate_reason",w.backdateReason()).addValue("data_provenance",c.provenance().name()).addValue("status",c.status().name())
        .addValue("selected_measurement_id",c.measurement()==null?null:c.measurement().id()).addValue("dry_rubber_exact_kg",calc==null?null:calc.exactKg())
        .addValue("dry_rubber_kg",calc==null?null:calc.dryKg()).addValue("calculation_policy",calc==null?null:calc.policy()).addValue("version",c.version())
        .addValue("created_at",Timestamp.from(c.createdAt())).addValue("updated_at",Timestamp.from(c.updatedAt())).addValue("created_by",c.createdBy()).addValue("updated_by",c.updatedBy());
  }

  private void allowed(LatexCollection c,CollectionScope scope){
    Objects.requireNonNull(scope,"Trusted scope required");
    if((scope.estateIds()!=null&&!scope.estateIds().contains(c.attribution().estateId()))
        ||(scope.workerId()!=null&&!scope.workerId().equals(c.attribution().workerId())))throw new AccessDeniedException("Collection persistence scope denied");
  }

  private void existing(UUID id,CollectionScope scope){
    if(find(id,scope).isEmpty())throw new AccessDeniedException("Collection evidence scope denied");
  }

  public void insert(LatexCollection c,CollectionScope scope){
    transaction();allowed(c,scope);
    if(c.version()!=0||c.status()!=Status.PENDING_DRC)throw new IllegalArgumentException("Initial pending collection required");
    var placeholders=Arrays.stream(COLUMNS.split(",")).map(s->":"+s).collect(Collectors.joining(","));
    db.update("INSERT INTO latex_collection("+COLUMNS+") VALUES ("+placeholders+")",parameters(c));
  }

  public void save(long expectedVersion,LatexCollection c,CollectionScope scope){
    transaction();allowed(c,scope);
    if(expectedVersion<0||expectedVersion==Long.MAX_VALUE||c.version()!=expectedVersion+1)throw new IllegalArgumentException("Consecutive revision required");
    var params=parameters(c).addValue("expectedVersion",expectedVersion);
    var assignments=Arrays.stream(MUTABLE.split(",")).map(s->s+"=:"+s).collect(Collectors.joining(","));
    int changed=db.update("UPDATE latex_collection c SET "+assignments+" WHERE c.id=:id AND c.version=:expectedVersion AND "+scope(scope,params),params);
    if(changed!=1)throw new OptimisticLockingFailureException("Collection version changed");
  }

  public void append(DrcMeasurement m,CollectionScope scope){
    transaction();existing(m.collectionId(),scope);
    var params=new MapSqlParameterSource("id",m.id()).addValue("collection",m.collectionId()).addValue("version",m.introducedVersion())
        .addValue("percent",m.percent()).addValue("measured",Timestamp.from(m.measuredAt())).addValue("method",m.method().name())
        .addValue("source",m.sourceReference()).addValue("sample",m.sampleReference()).addValue("instrument",m.instrumentReference())
        .addValue("provenance",m.provenance().name()).addValue("recorded",Timestamp.from(m.recordedAt())).addValue("actor",m.recordedBy());
    db.update("INSERT INTO drc_measurement(id,collection_id,introduced_version,percent,measured_at,method,source_reference,sample_reference,instrument_reference,data_provenance,recorded_at,recorded_by) VALUES (:id,:collection,:version,:percent,:measured,:method,:source,:sample,:instrument,:provenance,:recorded,:actor)",params);
  }

  public void append(CollectionRevision r,CollectionScope scope){
    transaction();allowed(r.snapshot(),scope);
    var current=find(r.collectionId(),scope).orElseThrow(()->new AccessDeniedException("Collection revision scope denied"));
    if(!current.equals(r.snapshot()))throw new IllegalArgumentException("Revision must match persisted current root");
    var params=new MapSqlParameterSource("id",r.collectionId()).addValue("version",r.version()).addValue("kind",r.commandKind().name())
        .addValue("snapshot",json.writeValueAsString(CollectionSnapshots.view(r.snapshot()))).addValue("time",Timestamp.from(r.recordedAt()))
        .addValue("actor",r.recordedBy()).addValue("correlation",r.correlationId()).addValue("policy",r.snapshot().calculation()==null?null:r.snapshot().calculation().policy());
    db.update("INSERT INTO collection_revision(collection_id,version,command_kind,snapshot,recorded_at,recorded_by,correlation_id,calculation_policy) VALUES (:id,:version,:kind,:snapshot::jsonb,:time,:actor,:correlation,:policy)",params);
  }

  public void append(CollectionAdjustment a,CollectionScope scope){
    transaction();allowed(a.after().snapshot(),scope);
    var current=find(a.after().collectionId(),scope).orElseThrow(()->new AccessDeniedException("Collection adjustment scope denied"));
    if(!current.equals(a.after().snapshot()))throw new IllegalArgumentException("Adjustment must match persisted current root");
    var params=new MapSqlParameterSource("id",a.id()).addValue("collection",a.after().collectionId()).addValue("before",a.before().version())
        .addValue("after",a.after().version()).addValue("kind",a.after().commandKind().name()).addValue("reason",a.reason())
        .addValue("time",Timestamp.from(a.approvedAt())).addValue("actor",a.approvedBy()).addValue("correlation",a.correlationId());
    db.update("INSERT INTO collection_adjustment(id,collection_id,before_version,after_version,kind,reason,approved_at,approved_by,correlation_id) VALUES (:id,:collection,:before,:after,:kind,:reason,:time,:actor,:correlation)",params);
  }

  @SuppressWarnings("unchecked")
  public List<Map<String,Object>> history(UUID id,CollectionScope scope,int page,int size){
    page(page,size);var params=new MapSqlParameterSource("id",id).addValue("limit",size).addValue("offset",(long)page*size);
    return db.query("SELECT r.snapshot::text FROM collection_revision r JOIN latex_collection c ON c.id=r.collection_id WHERE c.id=:id AND "+scope(scope,params)+" ORDER BY r.version ASC LIMIT :limit OFFSET :offset",params,
        (r,n)->(Map<String,Object>)json.readValue(r.getString(1),Map.class));
  }

  public long historyCount(UUID id,CollectionScope scope){
    var params=new MapSqlParameterSource("id",id);
    return db.queryForObject("SELECT count(*) FROM collection_revision r JOIN latex_collection c ON c.id=r.collection_id WHERE c.id=:id AND "+scope(scope,params),params,Long.class);
  }
}
