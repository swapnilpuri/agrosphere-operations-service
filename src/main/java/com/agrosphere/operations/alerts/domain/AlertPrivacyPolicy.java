package com.agrosphere.operations.alerts.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Provisional AN-D07 calculations only. No runtime activation, storage or deletion authority. */
public final class AlertPrivacyPolicy {
  public static final String VERSION = "AN_PRIVACY_PROVISIONAL_1";
  private AlertPrivacyPolicy() {}

  public enum Data {
    PERSONAL_READ(30), ACKNOWLEDGEMENT(365), AUDIT_HISTORY(365);
    private final Duration retention;
    Data(long days) { retention = Duration.ofDays(days); }
  }

  /** Must be maintained from actual state transitions, never incidental source update time. */
  public record Lifecycle(Optional<Instant> inactiveSince) {
    public Lifecycle { Objects.requireNonNull(inactiveSince); }
    public static Lifecycle active() { return new Lifecycle(Optional.empty()); }
    public Lifecycle transition(boolean active, Instant at) {
      Objects.requireNonNull(at);
      if (active) return active();
      return inactiveSince.isPresent() ? this : new Lifecycle(Optional.of(at));
    }
  }

  /** Empty means lifecycle retention has no deadline while active, not a deletion instruction. */
  public static Optional<Instant> deadline(Data data, Lifecycle lifecycle) {
    Objects.requireNonNull(data);
    return lifecycle.inactiveSince().map(at -> at.plus(data.retention));
  }

  public static boolean expired(Instant deadline, Instant now) {
    return !Objects.requireNonNull(now).isBefore(Objects.requireNonNull(deadline));
  }

  /** READ receipts cannot preserve a read-response copy beyond the marker's current deadline. */
  public static Instant receiptDeadline(Instant completedAt, boolean personalRead, Lifecycle lifecycle) {
    Instant deadline = Objects.requireNonNull(completedAt).plus(Duration.ofDays(30));
    if (personalRead) {
      var marker = deadline(Data.PERSONAL_READ, lifecycle);
      if (marker.isPresent() && marker.get().isBefore(deadline)) return marker.get();
    }
    return deadline;
  }

  public static Instant logDeadline(Instant recordedAt, boolean securityLog) {
    return Objects.requireNonNull(recordedAt).plus(Duration.ofDays(securityLog ? 90 : 14));
  }

  /** Backup expiry from creation; chained backups must not silently extend this bound. */
  public static Instant backupDeadline(Instant createdAt) {
    return Objects.requireNonNull(createdAt).plus(Duration.ofDays(35));
  }
}
