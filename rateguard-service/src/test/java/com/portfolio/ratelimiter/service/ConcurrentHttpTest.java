package com.portfolio.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ratelimiter.service.web.RateLimitFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Real HTTP over a real Tomcat on a random port: 2 API keys x 200 requests,
 * fired from 32 client threads at once against a limit of 50 per key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rateguard.algorithm=FIXED_WINDOW",
        "rateguard.limit=50",
        "rateguard.window=60s",
        "rateguard.client-identification.api-keys=client-one,client-two"
})
@Import(TestClockConfiguration.class)
class ConcurrentHttpTest {

    private static final int LIMIT = 50;
    private static final int REQUESTS_PER_KEY = 200;
    private static final int CLIENT_THREADS = 32;

    @LocalServerPort
    private int port;
    @Autowired
    private MutableClock clock;

    record Outcome(String key, int status, long remaining) {
    }

    @Test
    void eachKeyGetsExactlyItsLimitUnderConcurrentLoad() throws Exception {
        clock.set((clock.millis() / 60_000 + 1) * 60_000 + 1_000); // frozen mid-window

        HttpClient http = HttpClient.newBuilder().build();
        URI uri = URI.create("http://localhost:" + port + "/api/v1/demo/resource");
        ExecutorService pool = Executors.newFixedThreadPool(CLIENT_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < REQUESTS_PER_KEY; i++) {
                for (String key : List.of("client-one", "client-two")) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        HttpResponse<Void> response = http.send(
                                HttpRequest.newBuilder(uri).header("X-API-Key", key).GET().build(),
                                HttpResponse.BodyHandlers.discarding());
                        long remaining = Long.parseLong(
                                response.headers().firstValue(RateLimitFilter.REMAINING_HEADER).orElseThrow());
                        return new Outcome(key, response.statusCode(), remaining);
                    }));
                }
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }

            Map<String, List<Outcome>> byKey = outcomes.stream().collect(Collectors.groupingBy(Outcome::key));
            for (List<Outcome> perKey : byKey.values()) {
                List<Outcome> accepted = perKey.stream().filter(o -> o.status() == 200).toList();
                List<Outcome> rejected = perKey.stream().filter(o -> o.status() == 429).toList();
                assertThat(accepted).hasSize(LIMIT);
                assertThat(rejected).hasSize(REQUESTS_PER_KEY - LIMIT);
                // Every accepted request saw a distinct Remaining value 49..0: no two
                // concurrent requests were granted the same slot.
                Set<Long> remainingSeen = accepted.stream().map(Outcome::remaining)
                        .collect(Collectors.toCollection(TreeSet::new));
                assertThat(remainingSeen).containsExactlyElementsOf(
                        LongStream.range(0, LIMIT).boxed().toList());
                assertThat(rejected).allMatch(o -> o.remaining() == 0);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
