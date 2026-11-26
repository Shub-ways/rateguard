package com.portfolio.ratelimiter.bench;

/**
 * Which client keys a benchmark uses.
 *
 * <ul>
 *   <li>{@link #HOT}: every thread hits the same key (maximum contention). With the
 *       benchmark policy most calls are <em>rejected</em>.</li>
 *   <li>{@link #SPREAD}: threads walk a large pool of distinct keys, each far below
 *       its limit, so almost every call is <em>allowed</em>.</li>
 * </ul>
 * The two modes therefore exercise different code paths; compare algorithms
 * within a mode, not modes with each other. Allowed/rejected rates are
 * reported for every run via {@link Outcomes}.
 */
public enum KeyMode {
    HOT,
    SPREAD;

    static final String HOT_KEY = "hot-client";

    static String[] pool(int size) {
        if (Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("key pool size must be a power of two");
        }
        String[] keys = new String[size];
        for (int i = 0; i < size; i++) {
            keys[i] = "client-" + i;
        }
        return keys;
    }
}
