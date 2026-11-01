package com.agrosphere.operations.alerts.domain;

import java.time.*;
import java.util.*;

/** Accepted 0.9.0 key admission. Caller must authorize object/scope before using this result. */
public final class PrivacyCommandRules {
  private PrivacyCommandRules() {}
  public enum Decision { NEW, REPLAY, EXPIRED, UNKNOWN, INVALID }
  public record Removal(Instant cutoff, boolean pending) { public Removal { Objects.requireNonNull(cutoff); } }
  public static Instant keyTime(UUID key) {
    Objects.requireNonNull(key);
    if(key.version()!=7 || key.variant()!=2) throw new IllegalArgumentException("UUIDv7 required");
    return Instant.ofEpochMilli(key.getMostSignificantBits() >>> 16);
  }
  public static Decision decide(String raw, Instant now, Instant receiptDeadline, Removal removal) {
    Objects.requireNonNull(now);
    UUID key;
    try { key=UUID.fromString(raw); if(!key.toString().equals(raw))return Decision.INVALID; }
    catch(IllegalArgumentException|NullPointerException e){return Decision.INVALID;}
    Instant at;
    try {at=keyTime(key);}catch(IllegalArgumentException e){return Decision.INVALID;}
    if(at.isAfter(now.plusSeconds(300)))return Decision.INVALID;
    if(removal!=null&&(removal.pending()||!at.isAfter(removal.cutoff())))return Decision.EXPIRED;
    if(!now.isBefore(at.plus(Duration.ofDays(30))))return Decision.EXPIRED;
    if(receiptDeadline!=null)return now.isBefore(receiptDeadline)?Decision.REPLAY:Decision.EXPIRED;
    return at.isBefore(now.minusSeconds(300))?Decision.UNKNOWN:Decision.NEW;
  }
  public static Instant receiptDeadline(UUID key, Instant completedAt, Instant personalDeadline) {
    Instant deadline=keyTime(key).plus(Duration.ofDays(30));
    Instant completion=completedAt.plus(Duration.ofDays(30));
    if(completion.isBefore(deadline))deadline=completion;
    if(personalDeadline!=null&&personalDeadline.isBefore(deadline))deadline=personalDeadline;
    return deadline;
  }
  public static Removal removal(Instant verifiedAt) { return new Removal(verifiedAt.plusSeconds(300),true); }
}
