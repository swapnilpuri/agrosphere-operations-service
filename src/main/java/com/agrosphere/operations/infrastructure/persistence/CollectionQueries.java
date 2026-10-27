package com.agrosphere.operations.infrastructure.persistence;

import com.agrosphere.operations.application.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionQueries implements CollectionQueryPort {
  private final NamedParameterJdbcTemplate db;
  public CollectionQueries(NamedParameterJdbcTemplate db){this.db=db;}
  private MapSqlParameterSource parameters(CollectionFilters f){return new MapSqlParameterSource("page",f.page()).addValue("size",f.size()).addValue("offset",(long)f.page()*f.size());}
  private String scope(CollectionScope s,MapSqlParameterSource p){
    String result="1=1";if(s.estateIds()!=null){if(s.estateIds().isEmpty())return "FALSE";result+=" AND c.estate_id IN (:estates)";p.addValue("estates",s.estateIds());}
    if(s.workerId()!=null){result+=" AND c.worker_id=:scopeWorker";p.addValue("scopeWorker",s.workerId());}return result;
  }
  private String filters(CollectionFilters f,CollectionScope s,MapSqlParameterSource p){
    var result=scope(s,p);
    var filters=new LinkedHashMap<String,Object>();filters.put("division_id_snapshot",f.divisionId());filters.put("block_id",f.blockId());filters.put("tapping_record_id",f.tappingRecordId());
    filters.put("collection_reference",f.collectionReference());filters.put("data_provenance",f.provenance());filters.put("status",f.status());
    for(var item:filters.entrySet())if(item.getValue()!=null){result+=" AND c."+item.getKey()+"=:"+item.getKey();p.addValue(item.getKey(),item.getValue());}
    if(f.from()!=null){result+=" AND c.collection_date BETWEEN :fromDate AND :toDate";p.addValue("fromDate",f.from()).addValue("toDate",f.to());}
    return result;
  }
  private String page(String source,String entry,String order,MapSqlParameterSource params,String extras){
    return db.queryForObject("WITH scoped AS ("+source+"),paged AS (SELECT "+entry+" AS entry,row_number() OVER(ORDER BY "+order+") ordinal FROM scoped ORDER BY "+order+" LIMIT :size OFFSET :offset) SELECT (jsonb_build_object('content',coalesce((SELECT jsonb_agg(entry ORDER BY ordinal) FROM paged),'[]'::jsonb),'page',:page,'size',:size,'totalElements',(SELECT count(*) FROM scoped))"+extras+")::text",params,String.class);
  }
  private String time(String field){return "to_char("+field+" AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')";}

  public String list(CollectionFilters f,CollectionScope s){
    var p=parameters(f);return page("SELECT c.id,c.collected_at,r.snapshot FROM latex_collection c JOIN collection_revision r ON r.collection_id=c.id AND r.version=c.version WHERE "+filters(f,s,p),"snapshot","collected_at DESC,id ASC",p,"");
  }
  public String history(UUID id,CollectionFilters f,CollectionScope s){
    var p=parameters(f).addValue("id",id);
    var source="SELECT r.*,a.id adjustment_id,a.kind adjustment_kind,a.before_version,a.after_version,a.reason,a.approved_at,a.approved_by FROM collection_revision r JOIN latex_collection c ON c.id=r.collection_id LEFT JOIN collection_adjustment a ON a.collection_id=r.collection_id AND a.after_version=r.version WHERE c.id=:id AND "+scope(s,p);
    var entry="jsonb_build_object('collectionId',collection_id,'version',version,'commandKind',command_kind,'recordedAt',"+time("recorded_at")+",'recordedBy',recorded_by,'correlationId',correlation_id,'snapshot',snapshot,'adjustment',CASE WHEN adjustment_id IS NULL THEN NULL ELSE jsonb_build_object('id',adjustment_id,'kind',adjustment_kind,'beforeVersion',before_version,'afterVersion',after_version,'reason',reason,'approvedAt',"+time("approved_at")+",'approvedBy',approved_by,'correlationId',correlation_id) END)";
    return page(source,entry,"version ASC",p,"");
  }
  public String measurements(UUID id,CollectionFilters f,CollectionScope s){
    var p=parameters(f).addValue("id",id);
    var source="SELECT m.* FROM drc_measurement m JOIN latex_collection c ON c.id=m.collection_id WHERE c.id=:id AND "+scope(s,p);
    var entry="jsonb_build_object('id',id,'collectionId',collection_id,'introducedVersion',introduced_version,'drcPercent',percent,'measuredAt',"+time("measured_at")+",'method',method,'sourceReference',source_reference,'sampleReference',sample_reference,'instrumentReference',instrument_reference,'dataProvenance',data_provenance,'recordedAt',"+time("recorded_at")+",'recordedBy',recorded_by)";
    return page(source,entry,"introduced_version ASC,id ASC",p,"");
  }
  public String summary(CollectionFilters f,CollectionScope s){
    var p=parameters(f).addValue("requestedProvenance",f.provenance()).addValue("groupBy",f.groupBy()).addValue("fromDate",f.from()).addValue("toDate",f.to());
    var where=filters(f,s,p);
    var group=switch(f.groupBy()){case "estate"->"c.estate_id::text";case "division"->"c.division_id_snapshot::text";case "block"->"c.block_id::text";case "worker"->"c.worker_id";case "date"->"c.collection_date::text";default->"NULL::text";};
    var aggregates="""
      count(*) FILTER(WHERE c.status<>'VOIDED') active_count,
      count(*) FILTER(WHERE c.status='PENDING_DRC') pending_count,
      count(*) FILTER(WHERE c.status='CALCULATED') calculated_count,
      count(*) FILTER(WHERE c.status='VOIDED') void_count,
      round(coalesce(sum(c.wet_latex_kg) FILTER(WHERE c.status<>'VOIDED'),0),3) wet,
      round(coalesce(sum(c.wet_latex_kg) FILTER(WHERE c.status='PENDING_DRC'),0),3) pending_wet,
      round(coalesce(sum(c.wet_latex_kg) FILTER(WHERE c.status='CALCULATED'),0),3) calculated_wet,
      round(coalesce(sum(c.dry_rubber_kg) FILTER(WHERE c.status='CALCULATED'),0),3) known_dry,
      round(sum(c.dry_rubber_exact_kg) FILTER(WHERE c.status='CALCULATED')*100/nullif(sum(c.wet_latex_kg) FILTER(WHERE c.status='CALCULATED'),0),2) weighted
      """;
    String groups;
    if(f.groupBy().equals("total"))groups="SELECT NULL::text group_key,p.data_provenance,"+aggregates+" FROM (VALUES('REAL'),('SIMULATED')) p(data_provenance) LEFT JOIN filtered c ON c.data_provenance=p.data_provenance WHERE CAST(:requestedProvenance AS text) IS NULL OR p.data_provenance=:requestedProvenance GROUP BY p.data_provenance";
    else groups="SELECT "+group+" group_key,c.data_provenance,"+aggregates+" FROM filtered c GROUP BY 1,2";
    var source="WITH filtered AS (SELECT c.* FROM latex_collection c WHERE "+where+") "+groups;
    var entry="jsonb_build_object('groupKey',group_key,'dataProvenance',data_provenance,'activeCollectionCount',active_count,'pendingDrcCount',pending_count,'calculatedCollectionCount',calculated_count,'voidedCollectionCount',void_count,'totalWetLatexKg',wet,'pendingWetLatexKg',pending_wet,'calculatedWetLatexKg',calculated_wet,'knownDryRubberKg',known_dry,'weightedDrcPercent',weighted,'isComplete',pending_count=0)";
    return page(source,entry,"group_key ASC,data_provenance ASC",p," || jsonb_build_object('groupBy',:groupBy,'from',CAST(:fromDate AS date),'to',CAST(:toDate AS date),'aggregationPolicy','SUM_ROUNDED_COLLECTION_KG_V1')");
  }
}
