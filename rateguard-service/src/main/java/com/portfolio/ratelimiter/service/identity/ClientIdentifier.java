package com.portfolio.ratelimiter.service.identity;

import jakarta.servlet.http.HttpServletRequest;

/** Strategy for deciding which quota a request counts against. */
public interface ClientIdentifier {

    ClientIdentification identify(HttpServletRequest request);
}
