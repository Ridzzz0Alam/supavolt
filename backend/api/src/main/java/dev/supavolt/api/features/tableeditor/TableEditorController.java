package dev.supavolt.api.features.tableeditor;

import dev.supavolt.contracts.Contracts.AddColumnRequest;
import dev.supavolt.contracts.Contracts.CreateTableRequest;
import dev.supavolt.contracts.Contracts.TableInfoDto;
import dev.supavolt.contracts.Contracts.TableRowsDto;
import dev.supavolt.contracts.Contracts.UpdateRowRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orgs/{slug}/projects/{projectSlug}/tables")
@PreAuthorize("@orgAccess.member(#slug)")
@Tag(name = "Table editor")
public class TableEditorController {

    private final TableEditorService tables;

    public TableEditorController(TableEditorService tables) {
        this.tables = tables;
    }

    @GetMapping
    public List<String> list(@PathVariable String slug, @PathVariable String projectSlug) {
        return tables.listTables(slug, projectSlug);
    }

    @GetMapping("/{table}")
    public TableInfoDto get(@PathVariable String slug, @PathVariable String projectSlug, @PathVariable String table) {
        return tables.getTable(slug, projectSlug, table);
    }

    @GetMapping("/{table}/rows")
    public TableRowsDto rows(
            @PathVariable String slug, @PathVariable String projectSlug, @PathVariable String table,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return tables.getRows(slug, projectSlug, table, limit, offset);
    }

    @PostMapping
    @PreAuthorize("@orgAccess.admin(#slug)")
    public ResponseEntity<Void> create(
            @PathVariable String slug, @PathVariable String projectSlug, @Valid @RequestBody CreateTableRequest req) {
        tables.createTable(slug, projectSlug, req);
        return ResponseEntity.created(URI.create("/orgs/" + slug + "/projects/" + projectSlug + "/tables/" + req.name())).build();
    }

    @DeleteMapping("/{table}")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public ResponseEntity<Void> drop(@PathVariable String slug, @PathVariable String projectSlug, @PathVariable String table) {
        tables.dropTable(slug, projectSlug, table);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{table}/columns")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public ResponseEntity<Void> addColumn(
            @PathVariable String slug, @PathVariable String projectSlug, @PathVariable String table,
            @Valid @RequestBody AddColumnRequest req) {
        tables.addColumn(slug, projectSlug, table, req);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{table}/columns/{column}")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public ResponseEntity<Void> dropColumn(
            @PathVariable String slug, @PathVariable String projectSlug, @PathVariable String table,
            @PathVariable String column) {
        tables.dropColumn(slug, projectSlug, table, column);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{table}/rows/{pkValue}")
    public ResponseEntity<Void> updateRow(
            @PathVariable String slug, @PathVariable String projectSlug, @PathVariable String table,
            @PathVariable String pkValue, @Valid @RequestBody UpdateRowRequest req) {
        tables.updateRow(slug, projectSlug, table, pkValue, req);
        return ResponseEntity.noContent().build();
    }
}
