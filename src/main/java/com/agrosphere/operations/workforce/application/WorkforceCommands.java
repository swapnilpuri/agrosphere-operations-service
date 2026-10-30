package com.agrosphere.operations.workforce.application;

import static com.agrosphere.operations.workforce.domain.WorkforceRules.*;
import com.agrosphere.operations.workforce.application.WorkforcePorts.CommandKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Closed lossless command decoding and canonical request-hash policy 1. */
public final class WorkforceCommands {
  private WorkforceCommands() {}
  private static final ObjectMapper JSON=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
      DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  public static UUID uuid(String value) {
    try{require(value!=null&&value.length()==36,"Canonical UUID required");var id=UUID.fromString(value);
      require(id.toString().equalsIgnoreCase(value),"Canonical UUID required");return id;
    }catch(IllegalArgumentException e){throw new Invalid("Invalid UUID");}
  }
  private static String string(Object value){require(value instanceof String,"Text required");return (String)value;}
  private static long version(Object value) {
    require(value instanceof Number,"Version required");
    try{long result=new java.math.BigDecimal(value.toString()).longValueExact();require(result>=0,"Invalid version");return result;}
    catch(ArithmeticException e){throw new Invalid("Invalid version");}
  }
  public static LocalDate date(String value){try{return LocalDate.parse(value);}catch(DateTimeException|NullPointerException e){throw new Invalid("Invalid date");}}
  public static <E extends Enum<E>> E enumeration(Class<E> type,String value){try{return Enum.valueOf(type,value);}catch(IllegalArgumentException|NullPointerException e){throw new Invalid("Invalid enum");}}
  @SuppressWarnings("unchecked") public static Map<String,Object> parse(CommandKind kind,String raw) {
    Map<String,Object> body;
    try{Object value=JSON.readValue(raw,Object.class);require(value instanceof Map<?,?>,"JSON object required");body=(Map<String,Object>)value;}
    catch(Exception e){throw new Invalid("Malformed workforce command");}
    String required,optional="";
    switch(kind){
      case REGISTER->{required="estateId,workerCode,displayName,activeFrom,dataProvenance";optional="divisionId";}
      case PROFILE->{required="version,reason";optional="displayName,divisionId";}
      case PERIOD_CLOSED->required="version,activePeriodId,activeTo,reason";
      case PERIOD_OPENED->required="version,activeFrom,reason";
      case RECORD->{required="workerRegistrationId,attendanceDate,status";optional="backdateReason";}
      case CORRECT,RERECORD->required="version,status,reason";
      case VOID->required="version,reason";
      default->throw new Invalid("Unsupported workforce command");
    }
    var allowed=new HashSet<>(Arrays.asList((required+","+optional).split(",")));
    require(allowed.containsAll(body.keySet()),"Unknown command field");
    for(String field:required.split(","))require(body.containsKey(field)&&body.get(field)!=null,"Required command field missing");
    var out=new TreeMap<String,Object>();
    for(var entry:body.entrySet()) {
      String key=entry.getKey();Object value=entry.getValue();
      Object normalized=switch(key){
        case "estateId","workerRegistrationId","activePeriodId"->uuid(string(value));
        case "divisionId"->value==null?null:uuid(string(value));
        case "workerCode"->code(string(value));
        case "displayName"->text(string(value),200);
        case "reason","backdateReason"->reason(string(value));
        case "version"->version(value);
        case "activeFrom","activeTo","attendanceDate"->date(string(value));
        case "dataProvenance"->enumeration(Provenance.class,string(value));
        case "status"->{var status=enumeration(AttendanceStatus.class,string(value));require(status!=AttendanceStatus.VOIDED,"Observation status required");yield status;}
        default->throw new Invalid("Unsupported field");
      };
      out.put(key,normalized);
    }
    if(kind==CommandKind.REGISTER)out.putIfAbsent("divisionId",null);
    if(kind==CommandKind.PROFILE)require(out.containsKey("displayName")||out.containsKey("divisionId"),"Profile change required");
    return out;
  }
  public static String hash(String method,String path,Map<String,Object> body) {
    // All date/UUID/enum inputs have canonical text, version is exact long, absence/null semantics are preserved.
    var canonical=new TreeMap<String,Object>();body.forEach((k,v)->canonical.put(k,v instanceof LocalDate||v instanceof UUID||v instanceof Enum<?>?v.toString():v));
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((method+"\n"+path+"\n"+JSON.writeValueAsString(canonical)).getBytes(StandardCharsets.UTF_8)));}
    catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }
}
