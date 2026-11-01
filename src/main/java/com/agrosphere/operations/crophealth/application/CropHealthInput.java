package com.agrosphere.operations.crophealth.application;

import static com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Closed, canonical input; validation occurs before reservation/hash, without guessing defaults. */
public final class CropHealthInput {
  private static final ObjectMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Map<String,Set<String>> REQUIRED=Map.of(
      "InspectionRecord",Set.of("estateId","blockId","dataProvenance","observedAt","outcome","findings"),
      "IncidentReport",Set.of("estateId","blockId","dataProvenance","observedAt","category","severity","summary","details"),
      "InspectionCorrect",Set.of("inspectionVersion","reason","observedAt","outcome","findings"),
      "InspectionVoid",Set.of("inspectionVersion","reason"),
      "IncidentEdit",Set.of("expectedVersion","reason","observedAt","category","severity","summary","details"),
      "IncidentAction",Set.of("expectedVersion","reason"),
      "IncidentResolve",Set.of("expectedVersion","reason","resolvedAt","resolutionNote"));
  private final SortedMap<String,Object> values;
  private CropHealthInput(SortedMap<String,Object> values){this.values=values;}
  public static UUID uuid(String value) {
    require(value!=null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),"Canonical UUID required");return UUID.fromString(value);
  }
  public static CropHealthInput read(String shape,String body) {
    require(body!=null && body.length()<=32768,"Command body too large");
    try {
      var node=JSON.readTree(body);require(node!=null && node.isObject(),"Object body required");
      var required=REQUIRED.get(shape);require(required!=null,"Unknown command shape");var allowed=new HashSet<>(required);
      if(shape.equals("InspectionRecord")||shape.equals("IncidentReport"))allowed.add("backdateReason");
      if(shape.equals("InspectionRecord")||shape.equals("InspectionCorrect"))allowed.add("incident");
      if(shape.equals("InspectionCorrect")||shape.equals("InspectionVoid"))allowed.add("incidentVersion");
      if(shape.startsWith("Incident")&&!shape.equals("IncidentReport"))allowed.add("inspectionVersion");
      var normalized=normalize(node,allowed);
      require(normalized.keySet().containsAll(required),"Required command field missing");
      if(shape.equals("InspectionRecord")) require("FOLLOW_UP".equals(normalized.get("outcome"))==normalized.containsKey("incident"),"Incident input required only for FOLLOW_UP");
      return new CropHealthInput(normalized);
    }catch(tools.jackson.core.JacksonException e){throw new Invalid("Malformed JSON command");}
  }
  private static SortedMap<String,Object> normalize(JsonNode node,Set<String> allowed) {
    var out=new TreeMap<String,Object>();
    for(var property:node.properties()) {
      String key=property.getKey();var v=property.getValue();require(allowed.contains(key)&&!v.isNull(),"Unknown/null command field");
      Object value;
      if(key.equals("incident")) {
        require(v.isObject(),"Incident input must be object");var keys=Set.of("category","severity","summary","details");
        var nested=normalize(v,keys);require(nested.keySet().equals(keys),"Incomplete linked incident");value=nested;
      } else if(key.endsWith("Version")) {
        require(v.isIntegralNumber() && v.canConvertToLong() && v.longValue()>=0,"Invalid expected version");value=v.longValue();
      } else {
        require(v.isTextual(),"Text command field required");String raw=v.asText();
        value=switch(key) {
          case "estateId","blockId" -> uuid(raw).toString();
          case "observedAt","resolvedAt" -> parseObservation(raw).toString();
          case "dataProvenance" -> {developmentOnly(parse(Provenance.class,raw));yield raw;}
          case "outcome" -> parse(Outcome.class,raw).name();
          case "category" -> parse(Category.class,raw).name();
          case "severity" -> parse(Severity.class,raw).name();
          case "findings","details" -> text(raw,2000);
          case "summary" -> text(raw,200);
          default -> text(raw,1000);
        };
      }out.put(key,value);
    }return out;
  }
  public static <T extends Enum<T>> T parse(Class<T> type,String raw) {
    try{return Enum.valueOf(type,raw);}catch(IllegalArgumentException|NullPointerException e){throw new Invalid("Invalid "+type.getSimpleName());}
  }
  public String string(String key){return (String)values.get(key);}
  public long version(String key){require(values.containsKey(key),"Expected version required");return (Long)values.get(key);}
  public Long optionalVersion(String key){return (Long)values.get(key);}
  public UUID id(String key){return uuid(string(key));}
  public Instant instant(String key){return Instant.parse(string(key));}
  public Outcome outcome(){return parse(Outcome.class,string("outcome"));}
  public Observation observation(){return new Observation(instant("observedAt"),outcome(),string("findings"));}
  public Report report(){return new Report(instant("observedAt"),parse(Category.class,string("category")),parse(Severity.class,string("severity")),string("summary"),string("details"));}
  @SuppressWarnings("unchecked") public Report initialReport() {
    var nested=(Map<String,Object>)values.get("incident");return nested==null?null:new Report(instant("observedAt"),parse(Category.class,(String)nested.get("category")),
        parse(Severity.class,(String)nested.get("severity")),(String)nested.get("summary"),(String)nested.get("details"));
  }
  public String hash(String path) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("POST\n"+path+"\n"+JSON.writeValueAsString(values)).getBytes(StandardCharsets.UTF_8)));}
    catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }
}
