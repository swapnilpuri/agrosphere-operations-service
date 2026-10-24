package com.agrosphere.operations.application;

import com.agrosphere.operations.domain.Rules.MissedReasonCode;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.UUID;

public final class Requests {
  private Requests() {}

  public record ScheduleWrite(
      @NotNull UUID estateId,
      @NotNull UUID blockId,
      @NotBlank @Size(max = 200) String name,
      @NotBlank String recurrenceRule,
      @Positive int plannedTreeCount,
      @NotNull LocalDate effectiveFrom,
      LocalDate effectiveTo) {}

  public record ScheduleEdit(
      @NotNull @PositiveOrZero Long version,
      @NotBlank @Size(max = 200) String name,
      @NotBlank String recurrenceRule,
      @Positive int plannedTreeCount,
      @NotNull LocalDate effectiveFrom,
      LocalDate effectiveTo) {}

  public record Version(@NotNull @PositiveOrZero Long version) {}

  public record Generate(
      @NotNull @PositiveOrZero Long version,
      @NotNull LocalDate from,
      @NotNull LocalDate to,
      @NotBlank @Size(max = 200) String workerId) {}

  public record AssignmentWrite(
      @NotNull UUID estateId,
      @NotNull UUID blockId,
      @NotBlank @Size(max = 200) String workerId,
      @NotNull LocalDate tappingDate,
      @Positive int plannedTreeCount) {}

  public record WorkerWrite(
      @NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 200) String workerId) {}

  public record Complete(
      @NotNull @PositiveOrZero Long version,
      @NotNull @PositiveOrZero Integer actualTreeCount,
      @Size(max = 2000) String notes) {}

  public record Missed(
      @NotNull @PositiveOrZero Long version,
      @NotNull MissedReasonCode reasonCode,
      @Size(max = 2000) String remarks) {}

  public record Filter(
      UUID estateId,
      UUID blockId,
      String workerId,
      LocalDate from,
      LocalDate to,
      String status,
      int page,
      int size) {
    public Filter {
      if (page < 0
          || size < 1
          || size > 100
          || (long) page * size > Integer.MAX_VALUE
          || from != null && to != null && from.isAfter(to))
        throw new com.agrosphere.operations.domain.Rules.Violation(
            "Invalid pagination or date range");
    }
  }

  public record Page<T>(java.util.List<T> content, int page, int size, long totalElements) {}

  public record AssignmentView(
      com.agrosphere.operations.domain.TappingAssignment assignment,
      com.agrosphere.operations.domain.TappingRecord execution,
      com.agrosphere.operations.domain.TappingMissedReason missedReason) {}
}
