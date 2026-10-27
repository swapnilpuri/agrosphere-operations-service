package com.agrosphere.operations.api;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=LatexCollectionController.class)
public class CollectionProblems {
  @ExceptionHandler(DataAccessException.class)
  ProblemDetail persistence(DataAccessException ex){return Problems.problem(ex instanceof DataIntegrityViolationException||ex instanceof ConcurrencyFailureException?409:500,
      ex instanceof DataIntegrityViolationException||ex instanceof ConcurrencyFailureException?"Collection version, reference or concurrency conflict":"Collection persistence failed");}
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException ex){return Problems.problem(400,"Invalid collection input");}
  @ExceptionHandler({org.springframework.transaction.TransactionException.class,IllegalStateException.class})
  ProblemDetail transaction(RuntimeException ex){return Problems.problem(500,"Collection transaction failed");}
}
