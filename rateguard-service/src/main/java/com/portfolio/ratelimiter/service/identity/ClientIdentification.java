package com.portfolio.ratelimiter.service.identity;

/** Result of mapping an HTTP request to a rate-limit client. */
public sealed interface ClientIdentification {

    /**
     * @param clientId opaque limiter key; never a raw credential, safe to keep in memory
     */
    record Identified(String clientId) implements ClientIdentification {
    }

    /**
     * The request cannot be attributed to a client and must be refused.
     *
     * @param error   machine-readable code for the JSON body
     * @param message human-readable explanation (must not echo the credential)
     */
    record Unidentified(String error, String message) implements ClientIdentification {
    }
}
