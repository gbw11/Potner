package com.potner.health;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthResponse health() {
        return new HealthResponse("potner", "UP");
    }
}
