package com.sporthub.booking.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdmissionPrivacyFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getRequestURI().equals("/api/v1/bookings/admission"))
      response.setHeader("Cache-Control", "private, no-store, max-age=0");
    chain.doFilter(request, response);
  }
}
