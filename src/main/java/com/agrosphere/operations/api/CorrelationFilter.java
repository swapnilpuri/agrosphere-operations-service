package com.agrosphere.operations.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public class CorrelationFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String raw = request.getHeader("X-Correlation-ID");
    String id;
    try {
      id = UUID.fromString(raw).toString();
    } catch (Exception e) {
      id = UUID.randomUUID().toString();
    }
    try (var scope = MDC.putCloseable("correlationId", id)) {
      response.setHeader("X-Correlation-ID", id);
      chain.doFilter(request, response);
    }
  }
}
