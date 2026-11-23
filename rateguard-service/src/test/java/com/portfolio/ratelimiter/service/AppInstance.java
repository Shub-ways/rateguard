package com.portfolio.ratelimiter.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** A real RateGuard application (embedded Tomcat on a random port) started inside the test JVM. */
final class AppInstance implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    final String name;
    final int port;
    private final ConfigurableApplicationContext context;

    private AppInstance(String name, ConfigurableApplicationContext context) {
        this.name = name;
        this.context = context;
        this.port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    }

    static AppInstance start(String name, Map<String, String> properties) {
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");
        args.add("--rateguard.instance-id=" + name);
        properties.forEach((k, v) -> args.add("--" + k + "=" + v));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(RateGuardApplication.class)
                .run(args.toArray(String[]::new));
        return new AppInstance(name, ctx);
    }

    HttpResponse<String> get(String path, String apiKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (apiKey != null) {
            request.header("X-API-Key", apiKey);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> resource(String apiKey) throws Exception {
        return get("/api/v1/demo/resource", apiKey);
    }

    @Override
    public void close() {
        context.close();
    }
}
