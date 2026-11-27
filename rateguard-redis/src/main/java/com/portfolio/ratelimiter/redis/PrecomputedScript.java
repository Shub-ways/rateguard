package com.portfolio.ratelimiter.redis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Immutable Lua script whose text and SHA-1 are computed once, at construction.
 *
 * <p>Why not {@code DefaultRedisScript} with a resource location: its
 * {@code getSha1()} takes a lock and calls {@code ScriptSource.isModified()}
 * on <em>every</em> execution, which for a classpath resource inside a jar
 * re-reads the resource's last-modified time. Under load this serialized all
 * request threads on one lock: thread dumps of the service under the k6 Redis
 * scenario showed 47 of 81 sampled request threads parked in
 * {@code DefaultRedisScript.getSha1} (see docs/BENCHMARKS.md). The scripts
 * never change at runtime, so nothing needs to be checked per call.
 */
@SuppressWarnings("rawtypes")
final class PrecomputedScript implements RedisScript<List> {

    private final String text;
    private final String sha1;

    private PrecomputedScript(String text) {
        this.text = text;
        this.sha1 = sha1Hex(text);
    }

    /** Loads {@code name} from this package on the classpath. */
    static PrecomputedScript load(String name) {
        try (InputStream in = Objects.requireNonNull(
                PrecomputedScript.class.getResourceAsStream(name), "missing script " + name)) {
            return new PrecomputedScript(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read script " + name, e);
        }
    }

    @Override
    public String getSha1() {
        return sha1;
    }

    @Override
    public Class<List> getResultType() {
        return List.class;
    }

    @Override
    public String getScriptAsString() {
        return text;
    }

    private static String sha1Hex(String text) {
        try {
            // SHA-1 is what Redis uses to identify scripts (EVALSHA); not a security use.
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
