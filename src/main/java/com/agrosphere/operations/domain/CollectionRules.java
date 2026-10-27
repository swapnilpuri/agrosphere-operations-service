package com.agrosphere.operations.domain;

import static com.agrosphere.operations.domain.Rules.require;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure observation rules; no authorization, persistence or HTTP behavior. */
public final class CollectionRules {
  private CollectionRules() {}

  public enum Status { PENDING_DRC, CALCULATED, VOIDED }
  public enum Provenance { REAL, SIMULATED }
  public enum WeightMethod { SCALE, MANUAL_ENTRY }
  public enum DrcMethod { LAB_REPORTED, FIELD_TEST }
  public enum CommandKind { CREATE, DRC, CORRECT, VOID }

  public static final String POLICY = "DRC_KG_V1";
  private static final BigDecimal MAX_KG = new BigDecimal("999999.999");
  private static final Pattern OBSERVED_TIME = Pattern.compile(
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.([0-9]{1,6}0*))?(?:Z|[+-]\\d{2}:\\d{2})$");

  public static String text(String value, int limit, String field) {
    require(value != null, field + " required");
    var normalized = value.strip();
    require(!normalized.isEmpty() && normalized.length() <= limit, "Invalid " + field);
    return normalized;
  }

  public static String optionalText(String value, int limit, String field) {
    return value == null ? null : text(value, limit, field);
  }

  public static String subject(String value) {
    require(value != null && !value.isBlank() && value.length() <= 200, "Valid signed subject required");
    return value;
  }

  public static String reference(String value) {
    var normalized = text(value, 64, "collectionReference");
    require(normalized.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"), "Invalid collectionReference");
    return normalized.toUpperCase(Locale.ROOT);
  }

  private static BigDecimal decimal(BigDecimal value, int scale, BigDecimal min,
                                    BigDecimal max, String field) {
    require(value != null && value.compareTo(min) >= 0 && value.compareTo(max) <= 0,
        "Invalid " + field + " range");
    try {
      return value.setScale(scale, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException ex) {
      throw new Rules.Violation("Invalid " + field + " precision");
    }
  }

  public static BigDecimal wetKg(BigDecimal value) {
    return decimal(value, 3, new BigDecimal("0.001"), MAX_KG, "wetLatexKg");
  }

  public static BigDecimal drcPercent(BigDecimal value) {
    return decimal(value, 2, BigDecimal.ZERO, new BigDecimal("100"), "drcPercent");
  }

  /** R01: require an offset and reject information loss beyond microseconds. */
  public static Instant observedTime(String value) {
    require(value != null, "Observed timestamp required");
    var matcher = OBSERVED_TIME.matcher(value);
    require(matcher.matches(), "Explicit-offset microsecond timestamp required");
    var fraction = matcher.group(1);
    var normalized = value;
    if (fraction != null && fraction.length() > 6) {
      int start = matcher.start(1);
      normalized = value.substring(0, start) + fraction.substring(0, 6)
          + value.substring(matcher.end(1));
    }
    try {
      return OffsetDateTime.parse(normalized).toInstant();
    } catch (DateTimeException ex) {
      throw new Rules.Violation("Invalid observed timestamp");
    }
  }

  public static Instant observation(Instant value) {
    require(value != null && value.getNano() % 1000 == 0,
        "Observed instant must be exactly representable at microsecond precision");
    return value;
  }

  public static Instant now(Clock clock) {
    require(clock != null, "Clock required");
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  public static ZoneId zone(String value) {
    try {
      require(value != null && !value.isBlank(), "Estate timezone required");
      return ZoneId.of(value);
    } catch (DateTimeException ex) {
      throw new Rules.Violation("Invalid canonical estate timezone");
    }
  }

  public record Calculation(BigDecimal exactKg, BigDecimal dryKg, String policy) {}

  public static Calculation calculate(BigDecimal wet, BigDecimal percent) {
    wet = wetKg(wet);
    if (percent == null) return null;
    var exact = wet.multiply(drcPercent(percent)).divide(new BigDecimal("100")).setScale(7);
    return new Calculation(exact, exact.setScale(3, RoundingMode.HALF_UP), POLICY);
  }
}
