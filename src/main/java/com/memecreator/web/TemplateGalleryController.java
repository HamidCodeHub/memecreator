package com.memecreator.web;

import com.memecreator.template.MemeTemplate;
import com.memecreator.template.TemplateRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/** The templates page: every template, to look at and download. Signed-in people. */
@Tag(name = "Web app", description = "Endpoints for the signed-in web app")
@RestController
@RequestMapping("/api/web/templates")
public class TemplateGalleryController {

    private final TemplateRepository templates;

    public TemplateGalleryController(TemplateRepository templates) {
        this.templates = templates;
    }

    /** @param url the image (blank template, no text) */
    public record TemplateCard(String id, String name, String url) {
    }

    @Operation(summary = "All templates", description = "Sorted by name.")
    @GetMapping
    public List<TemplateCard> all() {
        return templates.findAll().stream()
                .sorted(Comparator.comparing(MemeTemplate::name, String.CASE_INSENSITIVE_ORDER))
                .map(t -> new TemplateCard(t.id(), t.name(),
                        "/api/web/templates/" + UriUtils.encodePathSegment(t.id(), StandardCharsets.UTF_8) + "/image"))
                .toList();
    }

    @Operation(summary = "A template's image")
    @GetMapping("/{id}/image")
    public ResponseEntity<FileSystemResource> image(@PathVariable String id) {
        return templates.findAll().stream()
                .filter(t -> t.id().equals(id) && Files.isRegularFile(t.path()))
                .findFirst()
                .map(t -> ResponseEntity.ok()
                        .contentType(MediaTypeFactory.getMediaType(t.path().getFileName().toString())
                                .orElse(MediaType.IMAGE_JPEG))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                        .body(new FileSystemResource(t.path())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
