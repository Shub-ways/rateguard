package com.portfolio.ratelimiter.service.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Example protected resource. Knows nothing about rate limiting; the filter enforces it. */
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    @GetMapping("/resource")
    public Map<String, String> resource() {
        return Map.of("status", "ok", "resource", "demo");
    }
}
