package com.portfolio.ratelimiter.service.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authenticated mode: the client must present one of the configured API keys.
 * Missing or unknown keys are refused; there is deliberately no fallback to
 * IP identification, which would let unauthenticated callers through.
 *
 * <p>Only SHA-256 digests of the keys are held. Lookups compare digests, so
 * response timing depends on the digest of the presented value, not on how
 * many leading characters of a real key it matches. The limiter key is
 * derived from the digest, so raw keys never reach the limiter's map, logs
 * or metrics.
 */
public final class ApiKeyClientIdentifier implements ClientIdentifier {

    private final String headerName;
    private final Set<String> validKeyDigests;

    public ApiKeyClientIdentifier(String headerName, List<String> apiKeys) {
        this.headerName = Objects.requireNonNull(headerName, "headerName");
        if (apiKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "rateguard.client-identification.mode=API_KEY requires at least one key in "
                            + "rateguard.client-identification.api-keys (e.g. env RATEGUARD_API_KEYS), "
                            + "or use mode=IP for local demos");
        }
        this.validKeyDigests = apiKeys.stream().map(ApiKeyClientIdentifier::sha256Hex)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public ClientIdentification identify(HttpServletRequest request) {
        String presented = request.getHeader(headerName);
        if (presented == null || presented.isBlank()) {
            return new ClientIdentification.Unidentified("missing_api_key", "An API key is required.");
        }
        String digest = sha256Hex(presented.trim());
        if (!validKeyDigests.contains(digest)) {
            return new ClientIdentification.Unidentified("invalid_api_key", "The API key is not valid.");
        }
        return new ClientIdentification.Identified("key:" + digest);
    }

    public String headerName() {
        return headerName;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
