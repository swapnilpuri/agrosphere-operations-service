package com.agrosphere.operations;

import com.agrosphere.operations.alerts.domain.AlertPrivacyPolicy;
import static com.agrosphere.operations.alerts.domain.AlertPrivacyPolicy.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class AlertPrivacyPolicyTest {
  final Instant start = Instant.parse("2026-10-31T12:00:00Z");
  Instant days(long n) { return start.plus(Duration.ofDays(n)); }

  @Test void activeDataHasNoInactiveDeadline() {
    for (Data data : Data.values()) assertTrue(deadline(data, Lifecycle.active()).isEmpty());
  }
  @Test void incidentalInactiveUpdatesCannotExtendRetention() {
    var inactive = Lifecycle.active().transition(false, start);
    assertSame(inactive, inactive.transition(false, days(10)));
    assertEquals(days(30), deadline(Data.PERSONAL_READ, inactive).orElseThrow());
  }
  @Test void reactivationCancelsDeadlineAndNextDeactivationStartsNewWindow() {
    var inactive = Lifecycle.active().transition(false, start);
    var active = inactive.transition(true, days(20));
    assertTrue(deadline(Data.PERSONAL_READ, active).isEmpty());
    assertEquals(days(55), deadline(Data.PERSONAL_READ, active.transition(false, days(25))).orElseThrow());
    // Lifecycle changes cannot themselves restore already deleted rows; this class has no storage.
  }
  @Test void acknowledgementsAndHistoryUseSeparateLongerWindow() {
    var inactive = Lifecycle.active().transition(false, start);
    assertEquals(days(365), deadline(Data.ACKNOWLEDGEMENT, inactive).orElseThrow());
    assertEquals(days(365), deadline(Data.AUDIT_HISTORY, inactive).orElseThrow());
  }
  @Test void expiryIsInclusiveAndIndependentOfLocalDaylightSaving() {
    assertFalse(expired(days(30), days(30).minusNanos(1)));
    assertTrue(expired(days(30), days(30)));
    assertTrue(expired(days(30), days(31)));
    assertEquals(Duration.ofHours(720), Duration.between(start, days(30)));
  }
  @Test void readReceiptCannotOutliveMarkerAndExpiryDoesNotRestartOnReplay() {
    var inactive = Lifecycle.active().transition(false, start);
    assertEquals(days(30), receiptDeadline(days(25), true, inactive));
    assertEquals(days(30), receiptDeadline(days(40), true, inactive));
    assertEquals(days(55), receiptDeadline(days(25), false, inactive));
    assertEquals(days(55), receiptDeadline(days(25), true, Lifecycle.active()));
  }
  @Test void receiptWindowCannotBeExtendedByLaterInactivityDeadline() {
    var inactive = Lifecycle.active().transition(false, days(20));
    assertEquals(days(30), receiptDeadline(start, true, inactive));
  }
  @Test void logsAndBackupsHaveCreationBasedDeadlines() {
    assertEquals(days(14), logDeadline(start, false));
    assertEquals(days(90), logDeadline(start, true));
    assertEquals(days(35), backupDeadline(start));
  }
}
