package com.agrosphere.operations.crophealth.api;

import com.agrosphere.operations.api.Problems;
import com.agrosphere.operations.crophealth.domain.CropHealthRules.*;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=CropHealthController.class)
public class CropHealthProblems {
  @ExceptionHandler(Invalid.class) ProblemDetail invalid(Invalid e){return Problems.problem(400,e.getMessage());}
  @ExceptionHandler(Conflict.class) ProblemDetail conflict(Conflict e){return Problems.problem(409,e.getMessage());}
  @ExceptionHandler(Denied.class) ProblemDetail denied(Denied e){return Problems.problem(403,e.getMessage());}
  @ExceptionHandler(EmptyResultDataAccessException.class) ProblemDetail missing(EmptyResultDataAccessException e){return Problems.problem(404,"Crop evidence not found");}
  @ExceptionHandler(ResponseStatusException.class) ProblemDetail reference(ResponseStatusException e){return Problems.problem(e.getStatusCode().value(),e.getReason());}
  @ExceptionHandler({DataAccessException.class,org.springframework.transaction.TransactionException.class,IllegalStateException.class})
  ProblemDetail persistence(RuntimeException e) {
    Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
    boolean conflict=e instanceof DataIntegrityViolationException||e instanceof ConcurrencyFailureException;boolean retry=false;
    if(cause instanceof java.sql.SQLException sql){conflict|=java.util.Set.of("23505","23514","23503","55P03","40001","40P01").contains(sql.getSQLState());retry=java.util.Set.of("55P03","40001","40P01").contains(sql.getSQLState());}
    var result=Problems.problem(conflict?409:500,conflict?"Crop evidence version, link or concurrent command conflict":"Crop evidence transaction failed");
    if(retry)result.setProperty("retryable",true);return result;
  }
}
