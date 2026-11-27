package com.portfolio.ratelimiter.redis;

import java.time.Clock;
import java.util.Objects;

/**
 * Where a Redis limiter's "now" comes from.
 *
 * <ul>
 *   <li>{@link #server()} (recommended): the Lua script reads Redis {@code TIME}.
 *       Every application instance sees the same clock, so clock skew between
 *       instances cannot change a decision. Requires Redis 5+ (scripts are
 *       replicated by effects, so calling {@code TIME} before a write is allowed).</li>
 *   <li>{@link #application(Clock)}: the caller's clock is sent as an argument.
 *       Useful for deterministic tests. In production, instances with skewed
 *       clocks would disagree about window boundaries and refill amounts.</li>
 * </ul>
 */
public sealed interface RedisTime {

    /** Script argument: epoch millis, or empty to make the script call {@code TIME}. */
    String scriptArgument();

    static RedisTime server() {
        return Server.INSTANCE;
    }

    static RedisTime application(Clock clock) {
        return new Application(clock);
    }

    enum Server implements RedisTime {
        INSTANCE;

        @Override
        public String scriptArgument() {
            return "";
        }
    }

    record Application(Clock clock) implements RedisTime {
        public Application {
            Objects.requireNonNull(clock, "clock");
        }

        @Override
        public String scriptArgument() {
            return Long.toString(clock.millis());
        }
    }
}
