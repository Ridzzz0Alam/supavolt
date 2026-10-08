package dev.supavolt.api.features.sqleditor;

import dev.supavolt.contracts.Contracts.ExecuteQueryRequest;
import dev.supavolt.contracts.Contracts.QueryHistoryDto;
import dev.supavolt.contracts.Contracts.QueryResultDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orgs/{slug}/projects/{projectSlug}/sql")
@PreAuthorize("@orgAccess.member(#slug)")
@Tag(name = "SQL editor")
public class SqlEditorController {

    private final SqlEditorService editor;

    public SqlEditorController(SqlEditorService editor) {
        this.editor = editor;
    }

    @PostMapping
    public QueryResultDto execute(
            @PathVariable String slug, @PathVariable String projectSlug, @Valid @RequestBody ExecuteQueryRequest req) {
        return editor.execute(slug, projectSlug, req.sql());
    }

    @GetMapping("/history")
    public List<QueryHistoryDto> history(@PathVariable String slug, @PathVariable String projectSlug) {
        return editor.history(slug, projectSlug);
    }
}
