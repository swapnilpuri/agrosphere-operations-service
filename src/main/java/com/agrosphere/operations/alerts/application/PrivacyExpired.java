package com.agrosphere.operations.alerts.application;

public final class PrivacyExpired extends RuntimeException {
  private final String code;
  public PrivacyExpired(String code){super("Alert privacy period expired");this.code=code;}
  public String code(){return code;}
}
