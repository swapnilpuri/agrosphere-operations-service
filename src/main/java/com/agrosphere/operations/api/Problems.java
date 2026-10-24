package com.agrosphere.operations.api;

import com.agrosphere.operations.domain.Rules.Violation;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class Problems {
  @ExceptionHandler({
    Violation.class,
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public ProblemDetail invalid(Exception e) {
    return problem(400, e instanceof Violation ? e.getMessage() : "Invalid request");
  }

  @ExceptionHandler({
    OptimisticLockingFailureException.class,
    DataIntegrityViolationException.class
  })
  public ProblemDetail conflict(Exception e) {
    return problem(409, "Resource version or uniqueness conflict");
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail denied(Exception e) {
    return problem(403, e.getMessage());
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ProblemDetail response(ResponseStatusException e) {
    return problem(e.getStatusCode().value(), e.getReason());
  }

  public static ProblemDetail problem(int code, String detail) {
    var p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code), detail);
    p.setProperty("correlationId", org.slf4j.MDC.get("correlationId"));
    return p;
  }
}
