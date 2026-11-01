package com.agrosphere.operations.alerts.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AlertNoStoreFilter extends OncePerRequestFilter {
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
    String p=request.getRequestURI();if(p.equals("/api/v1/alerts")||p.startsWith("/api/v1/alerts/")||p.equals("/api/v2/alerts")||p.startsWith("/api/v2/alerts/"))response.setHeader("Cache-Control","no-store");chain.doFilter(request,response);
  }
}
