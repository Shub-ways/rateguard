package com.portfolio.ratelimiter.service.identity;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Demo mode: one quota per client IP address.
 *
 * <p>Uses {@link HttpServletRequest#getRemoteAddr()} only. It does not parse
 * {@code X-Forwarded-For} itself, because any client can forge that header.
 * Behind a trusted reverse proxy, set {@code server.forward-headers-strategy}
 * so the servlet container rewrites the remote address from trusted proxies.
 */
public final class IpClientIdentifier implements ClientIdentifier {

    @Override
    public ClientIdentification identify(HttpServletRequest request) {
        return new ClientIdentification.Identified("ip:" + request.getRemoteAddr());
    }
}
