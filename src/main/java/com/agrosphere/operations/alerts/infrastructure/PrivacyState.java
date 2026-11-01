package com.agrosphere.operations.alerts.infrastructure;

import static com.agrosphere.operations.crophealth.infrastructure.CropHealthState.*;
import static com.agrosphere.operations.alerts.domain.AlertRules.*;
import com.agrosphere.operations.alerts.domain.*;
import com.agrosphere.operations.alerts.domain.CropHealthAlert.ReadMarker;
import java.time.*;
import java.util.*;

public final class PrivacyState {
  private PrivacyState() {}
  public static Map<String,Object> root(PrivacyAlert a){
    var v=AlertState.privateView(new CropHealthAlert(a.id(),a.source(),a.alertVersion(),a.attentionVersion(),a.raisedAt(),a.attentionAt(),a.updatedAt(),null));
    v.remove("acknowledgedBy");v.remove("acknowledgedAt");v.put("acknowledged",a.acknowledged());return v;
  }
  public static PrivacyAlert read(String raw,Instant ackAt,Instant inactive,Instant history){
    var n=parse(raw);boolean acknowledged=n.required("acknowledged").asBoolean();
    var v=new tools.jackson.databind.ObjectMapper().readValue(raw,Map.class);
    v.put("acknowledged",false);v.put("acknowledgedBy",null);v.put("acknowledgedAt",null);
    var a=AlertState.read(stringify(v));
    return new PrivacyAlert(a.id(),a.source(),a.alertVersion(),a.attentionVersion(),a.raisedAt(),a.attentionAt(),a.updatedAt(),acknowledged,ackAt,inactive,history);
  }
  public static Map<String,Object> view(PrivacyAlert raw,ReadMarker marker,Instant now){
    var a=raw.expire(now);var v=root(a);boolean expired=a.readExpired(now);
    long read=expired||marker==null?0:marker.attentionVersion();
    boolean unread=!expired&&read<a.attentionVersion();
    v.putAll(fields("policyVersion",AlertPrivacyPolicy.VERSION,"readState",expired?"EXPIRED":unread?"UNREAD":"READ",
      "readExpiresAt",a.deadline(AlertPrivacyPolicy.Data.PERSONAL_READ)==null?null:time(a.deadline(AlertPrivacyPolicy.Data.PERSONAL_READ)),
      "readAttentionVersion",read,"readAt",expired||marker==null?null:time(marker.at()),"unreadForMe",unread,
      "acknowledgementState",!a.acknowledged()?"NONE":a.acknowledgedAt()==null?"REDACTED":"RETAINED",
      "acknowledgedAt",a.acknowledgedAt()==null?null:time(a.acknowledgedAt())));return v;
  }
}
