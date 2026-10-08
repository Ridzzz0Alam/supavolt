package dev.supavolt.api.features;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "timestamp", OffsetDateTime.now(ZoneOffset.UTC));
    }
}
