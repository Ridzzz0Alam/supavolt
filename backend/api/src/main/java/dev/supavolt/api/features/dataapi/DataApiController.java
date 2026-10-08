package dev.supavolt.api.features.dataapi;

import dev.supavolt.api.features.auth.ProjectKey;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Reads take any project key; writes need the service role (enforced in SecurityConfig). */
@RestController
@RequestMapping("/projects/{projectSlug}/rest")
@Tag(name = "Data API")
public class DataApiController {

    private final DataApiService data;

    public DataApiController(DataApiService data) {
        this.data = data;
    }

    @GetMapping("/{table}")
    public List<Map<String, Object>> select(
            @PathVariable String projectSlug, @PathVariable String table,
            @AuthenticationPrincipal ProjectKey key, HttpServletRequest request) {
        return data.select(key, projectSlug, table, request.getParameterMap());
    }

    @PostMapping("/{table}")
    public ResponseEntity<Map<String, Object>> insert(
            @PathVariable String projectSlug, @PathVariable String table,
            @RequestBody(required = false) JsonNode body, @AuthenticationPrincipal ProjectKey key) {
        return ResponseEntity.status(HttpStatus.CREATED).body(data.insert(key, projectSlug, table, body));
    }

    @PatchMapping("/{table}/{id}")
    public Map<String, Object> update(
            @PathVariable String projectSlug, @PathVariable String table, @PathVariable String id,
            @RequestBody(required = false) JsonNode body, @AuthenticationPrincipal ProjectKey key) {
        return data.update(key, projectSlug, table, id, body);
    }

    @DeleteMapping("/{table}/{id}")
    public Map<String, Object> delete(
            @PathVariable String projectSlug, @PathVariable String table, @PathVariable String id,
            @AuthenticationPrincipal ProjectKey key) {
        return data.delete(key, projectSlug, table, id);
    }
}
