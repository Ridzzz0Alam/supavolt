package dev.supavolt.api.features.realtime;

import dev.supavolt.contracts.Contracts.MessageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orgs/{slug}/projects/{projectSlug}/realtime")
@PreAuthorize("@orgAccess.member(#slug)")
@Tag(name = "Realtime")
public class RealtimeController {

    private final TriggerService triggers;

    public RealtimeController(TriggerService triggers) {
        this.triggers = triggers;
    }

    @GetMapping
    public List<String> enabled(@PathVariable String slug, @PathVariable String projectSlug) {
        return triggers.enabledTables(slug, projectSlug);
    }

    @PostMapping("/{table}/enable")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public MessageResponse enable(@PathVariable String slug, @PathVariable String projectSlug, @PathVariable String table) {
        triggers.enable(slug, projectSlug, table);
        return new MessageResponse("Realtime enabled for " + table);
    }

    @DeleteMapping("/{table}/disable")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public MessageResponse disable(@PathVariable String slug, @PathVariable String projectSlug, @PathVariable String table) {
        triggers.disable(slug, projectSlug, table);
        return new MessageResponse("Realtime disabled for " + table);
    }
}
