package com.portfolio.ratelimiter.redis;

import com.portfolio.ratelimiter.core.RateLimitResult;
import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.core.RateLimiterUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Base for limiters whose per-client state lives in Redis and is updated by
 * exactly one Lua script call per request.
 *
 * <h2>Atomicity</h2>
 * Redis runs a Lua script to completion without interleaving other commands,
 * so each script's read, check, update and expiry are one atomic step. There
 * is no read-modify-write race between application instances or threads.
 * Each request is one script call on one key. Nothing here assumes atomicity
 * <em>across</em> requests, and none is needed.
 *
 * <h2>Keys</h2>
 * {@code <prefix>:<algorithm>:<policy>:{<client>}}. The policy segment means
 * changing the configured limits starts fresh state instead of reinterpreting
 * old state. The client part is used verbatim only if it is short and made of
 * safe characters; otherwise it is replaced by its SHA-256 digest. The braces
 * form a Redis Cluster hash tag. Every key gets a bounded TTL from the script.
 *
 * <h2>Failures</h2>
 * Any Redis error or unusable reply becomes a {@link RateLimiterUnavailableException},
 * never a fake allow or reject; the caller applies its fail-open/closed policy.
 */
public abstract class RedisRateLimiter implements RateLimiter {

    private static final Pattern SAFE_CLIENT_ID = Pattern.compile("[A-Za-z0-9:._-]{1,128}");
    /** Lua numbers are doubles: integers are exact below 2^53; stay a factor 2 below that. */
    static final long MAX_SCRIPT_INTEGER = 1L << 52;

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;
    private final String keyBase;
    private final RedisTime time;
    private final long limit;

    @SuppressWarnings("rawtypes")
    protected RedisRateLimiter(StringRedisTemplate redis, String scriptName, String keyPrefix,
                               String algorithmAndPolicy, RedisTime time, long limit) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.time = Objects.requireNonNull(time, "time");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        if (!SAFE_CLIENT_ID.matcher(keyPrefix).matches()) {
            throw new IllegalArgumentException("keyPrefix must match " + SAFE_CLIENT_ID.pattern());
        }
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(scriptName, RedisRateLimiter.class));
        s.setResultType(List.class);
        this.script = s;
        this.keyBase = keyPrefix + ":" + algorithmAndPolicy + ":";
        this.limit = limit;
    }

    /** Script arguments preceding the trailing time argument. */
    protected abstract List<String> policyArguments();

    @Override
    public final RateLimitResult tryAcquire(String clientId) {
        Objects.requireNonNull(clientId, "key");
        String key = redisKey(clientId);
        Object[] args = new Object[policyArguments().size() + 1];
        policyArguments().toArray(args);
        args[args.length - 1] = time.scriptArgument();

        List<?> reply;
        try {
            reply = redis.execute(script, List.of(key), args);
        } catch (DataAccessException e) {
            throw new RateLimiterUnavailableException("Redis rate-limit backend unavailable", e);
        }
        if (reply == null || reply.size() != 3 || !(reply.get(0) instanceof Long allowed)
                || !(reply.get(1) instanceof Long remaining) || !(reply.get(2) instanceof Long retryMs)) {
            throw new RateLimiterUnavailableException("Unexpected reply from rate-limit script");
        }
        return allowed == 1L
                ? RateLimitResult.allow(limit, remaining)
                : RateLimitResult.reject(limit, Duration.ofMillis(retryMs));
    }

    /** The Redis key holding {@code clientId}'s state (exposed for tests and operations). */
    public final String redisKey(String clientId) {
        String safe = SAFE_CLIENT_ID.matcher(clientId).matches() ? clientId : "h:" + sha256Hex(clientId);
        return keyBase + "{" + safe + "}";
    }

    static void requireScriptSafe(long value, String what) {
        if (value <= 0 || value >= MAX_SCRIPT_INTEGER) {
            throw new IllegalArgumentException(what + " must be in (0, 2^52)");
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
