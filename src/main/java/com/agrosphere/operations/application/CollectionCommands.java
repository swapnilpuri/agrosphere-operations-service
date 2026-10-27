package com.agrosphere.operations.application;

import static com.agrosphere.operations.domain.CollectionRules.*;
import static com.agrosphere.operations.domain.Rules.require;

import com.agrosphere.operations.domain.*;
import com.agrosphere.operations.domain.LatexCollection.*;
import java.math.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Closed, lossless command decoding and normalization policy 1. */
public final class CollectionCommands {
  private CollectionCommands() {}
  public static final String BASE="/api/v1/tapping/collections";
  private static final ObjectMapper JSON=JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  public static UUID uuid(String value){
    try{require(value!=null&&value.length()==36,"Canonical UUID required");var id=UUID.fromString(value);
      require(id.toString().equalsIgnoreCase(value),"Canonical UUID required");return id;
    }catch(IllegalArgumentException ex){throw new Rules.Violation("Invalid UUID");}
  }

  @SuppressWarnings("unchecked")
  private static Map<String,Object> object(Object value){
    require(value instanceof Map<?,?>,"JSON object required");return (Map<String,Object>)value;
  }

  private static void keys(Map<String,Object> value,String required,String optional){
    var allowed=new HashSet<String>();
    for(String key:(required+","+optional).split(","))if(!key.isEmpty())allowed.add(key);
    require(allowed.containsAll(value.keySet()),"Unknown command field");
    for(String key:required.split(","))if(!key.isEmpty())require(value.containsKey(key)&&value.get(key)!=null,key+" required");
  }

  private static String string(Object value){require(value instanceof String,"String required");return (String)value;}
  private static BigDecimal decimal(Object value){require(value instanceof Number&&!(value instanceof Double)&&!(value instanceof Float),"Exact JSON decimal number required");return new BigDecimal(value.toString());}
  public static long version(Object value){try{long result=decimal(value).longValueExact();require(result>=0,"Nonnegative version required");return result;}catch(ArithmeticException ex){throw new Rules.Violation("Invalid version");}}
  private static <T extends Enum<T>> T enumeration(Class<T> type,Object value){try{return Enum.valueOf(type,string(value));}catch(IllegalArgumentException ex){throw new Rules.Violation("Unsupported enum value");}}

  private static Map<String,Object> evidence(Object value){
    var body=object(value);keys(body,"method,sourceReference","instrumentReference");var normalized=new LinkedHashMap<String,Object>();
    normalized.put("method",enumeration(WeightMethod.class,body.get("method")));
    normalized.put("sourceReference",text(string(body.get("sourceReference")),200,"weight source"));
    if(body.containsKey("instrumentReference"))normalized.put("instrumentReference",body.get("instrumentReference")==null?null:text(string(body.get("instrumentReference")),200,"instrument reference"));
    normalized.putIfAbsent("instrumentReference",null);
    return normalized;
  }

  private static Map<String,Object> measurement(Object value){
    var body=object(value);keys(body,"drcPercent,measuredAt,method,sourceReference,dataProvenance","sampleReference,instrumentReference");var normalized=new LinkedHashMap<String,Object>();
    normalized.put("drcPercent",drcPercent(decimal(body.get("drcPercent"))));normalized.put("measuredAt",observedTime(string(body.get("measuredAt"))));
    normalized.put("method",enumeration(DrcMethod.class,body.get("method")));normalized.put("sourceReference",text(string(body.get("sourceReference")),200,"measurement source"));
    normalized.put("dataProvenance",enumeration(Provenance.class,body.get("dataProvenance")));
    for(String key:List.of("sampleReference","instrumentReference"))if(body.containsKey(key))normalized.put(key,body.get(key)==null?null:text(string(body.get(key)),200,key));
    normalized.putIfAbsent("sampleReference",null);normalized.putIfAbsent("instrumentReference",null);
    require(normalized.get("method")!=DrcMethod.LAB_REPORTED||normalized.get("sampleReference")!=null,"Reported laboratory measurement requires sample reference");
    return normalized;
  }

  public static Map<String,Object> parse(String command,String raw){
    Map<String,Object> body;
    try{body=object(JSON.readValue(raw,Map.class));}catch(Exception ex){throw new Rules.Violation("Malformed JSON command");}
    switch(command){
      case "CREATE"->keys(body,"tappingRecordId,collectionReference,collectedAt,wetLatexKg,weightEvidence,dataProvenance","backdateReason,replacementOfCollectionId");
      case "DRC"->keys(body,"version,measurement","");
      case "CORRECT"->keys(body,"version,reason","wetLatexKg,collectedAt,weightEvidence,backdateReason,replacementMeasurement");
      case "VOID"->keys(body,"version,reason","");
      default->throw new IllegalArgumentException("Unknown internal command");
    }
    var result=new LinkedHashMap<String,Object>();
    for(var item:body.entrySet()){
      var key=item.getKey();var value=item.getValue();
      result.put(key,switch(key){
        case "tappingRecordId","replacementOfCollectionId"->uuid(string(value));
        case "collectionReference"->reference(string(value));
        case "collectedAt"->observedTime(string(value));
        case "wetLatexKg"->wetKg(decimal(value));
        case "weightEvidence"->evidence(value);
        case "dataProvenance"->enumeration(Provenance.class,value);
        case "measurement","replacementMeasurement"->measurement(value);
        case "version"->version(value);
        case "reason"->text(string(value),2000,"reason");
        case "backdateReason"->value==null?null:text(string(value),2000,"backdate reason");
        default->throw new Rules.Violation("Unknown command field");
      });
    }
    if(command.equals("CREATE"))result.putIfAbsent("backdateReason",null);
    return result;
  }

  private static String canonical(Object value){
    if(value==null)return "null";
    if(value instanceof BigDecimal number)return number.stripTrailingZeros().toPlainString();
    if(value instanceof Number)return value.toString();
    if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put((String)k,v));
      return sorted.entrySet().stream().map(e->JSON.writeValueAsString(e.getKey())+":"+canonical(e.getValue())).collect(java.util.stream.Collectors.joining(",","{","}"));}
    return JSON.writeValueAsString(value.toString());
  }

  public static String hash(String path,Map<String,Object> body){
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("1\nPOST\n"+path+"\n"+canonical(body)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
  }

  public static WeightObservation weight(Map<String,Object> body,LatexCollection old){
    var evidence=body.containsKey("weightEvidence")?object(body.get("weightEvidence")):null;
    return new WeightObservation((Instant)body.getOrDefault("collectedAt",old==null?null:old.weight().collectedAt()),
        (BigDecimal)body.getOrDefault("wetLatexKg",old==null?null:old.weight().wetLatexKg()),
        evidence==null?(old==null?null:old.weight().evidence()):new WeightEvidence((WeightMethod)evidence.get("method"),(String)evidence.get("sourceReference"),(String)evidence.get("instrumentReference")),
        body.containsKey("backdateReason")?(String)body.get("backdateReason"):(old==null?null:old.weight().backdateReason()));
  }

  public static DrcMeasurement measurement(Object value,LatexCollection old,Instant now,String actor){
    var body=object(value);return new DrcMeasurement(UUID.randomUUID(),old.id(),old.version()+1,(BigDecimal)body.get("drcPercent"),
        (Instant)body.get("measuredAt"),(DrcMethod)body.get("method"),(String)body.get("sourceReference"),(String)body.get("sampleReference"),
        (String)body.get("instrumentReference"),(Provenance)body.get("dataProvenance"),now,actor);
  }
}
