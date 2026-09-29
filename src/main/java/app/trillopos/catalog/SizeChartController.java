package app.trillopos.catalog;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.trillopos.catalog.SizeChartLibrary.Template;
import app.trillopos.shared.web.ApiException;

/**
 * The shop's size charts, and the library they are copied from. Taking a chart from the library
 * twice returns the copy the shop already has, edits and all.
 */
@RestController
@RequestMapping("/size-charts")
class SizeChartController {

    record SizeChartView(UUID id, String name, String shortName, SizeChartKind kind, String templateKey,
            List<String> labels) {

        static SizeChartView of(SizeChart c) {
            return new SizeChartView(c.getId(), c.getName(), c.getShortName(), c.getKind(), c.getTemplateKey(),
                    c.getLabels());
        }
    }

    record SizeChartTemplateView(String key, SizeChartKind kind, String name, String shortName, List<String> labels) {

        static SizeChartTemplateView of(Template t) {
            return new SizeChartTemplateView(t.key(), t.kind(), t.name(), t.shortName(), t.labels());
        }
    }

    /**
     * From the library: {@code templateKey}, and {@code name} in the shop's language if wanted.
     * Typed by the shop: {@code name}, {@code labels} smallest first, and optionally {@code shortName}
     * and {@code kind}.
     */
    record SizeChartCreate(@Size(max = 40) String templateKey, @Size(max = 80) String name,
            @Size(max = 10) String shortName, SizeChartKind kind, List<String> labels) {
    }

    /** Every field optional; an empty {@code shortName} removes it. */
    record SizeChartUpdate(@Size(min = 1, max = 80) String name, @Size(max = 10) String shortName,
            SizeChartKind kind, List<String> labels) {
    }

    private final SizeChartRepository charts;
    private final CategoryRepository categories;
    private final Clock clock;

    SizeChartController(SizeChartRepository charts, CategoryRepository categories, Clock clock) {
        this.charts = charts;
        this.categories = categories;
        this.clock = clock;
    }

    @GetMapping
    List<SizeChartView> list() {
        return charts.findAllByArchivedAtIsNullOrderByKindAscNameAsc().stream().map(SizeChartView::of).toList();
    }

    @GetMapping("/library")
    List<SizeChartTemplateView> library() {
        return SizeChartLibrary.all().stream().map(SizeChartTemplateView::of).toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    @Transactional
    ResponseEntity<SizeChartView> create(@Valid @RequestBody SizeChartCreate request) {
        if (request.templateKey() != null) {
            Template template = SizeChartLibrary.find(request.templateKey())
                    .orElseThrow(() -> ApiException.badRequest("size_chart_not_found", "no such chart in the library"));
            Optional<SizeChart> existing = charts.findByTemplateKeyAndArchivedAtIsNull(template.key());
            if (existing.isPresent()) {
                return ResponseEntity.ok(SizeChartView.of(existing.get()));
            }
            String name = blankToNull(request.name());
            SizeChart copy = charts.save(new SizeChart(name == null ? template.name() : name, template.shortName(),
                    template.kind(), template.key(), template.labels()));
            return ResponseEntity.status(HttpStatus.CREATED).body(SizeChartView.of(copy));
        }
        String name = blankToNull(request.name());
        if (name == null) {
            throw ApiException.badRequest("validation_failed", "a size chart needs a name");
        }
        SizeChart chart = charts.save(new SizeChart(name, blankToNull(request.shortName()),
                request.kind() == null ? SizeChartKind.OTHER : request.kind(), null,
                SizeChart.normalizeLabels(request.labels())));
        return ResponseEntity.status(HttpStatus.CREATED).body(SizeChartView.of(chart));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    @Transactional
    SizeChartView update(@PathVariable UUID id, @Valid @RequestBody SizeChartUpdate request) {
        SizeChart chart = find(id);
        if (request.name() != null && !request.name().isBlank()) {
            chart.setName(request.name().strip());
        }
        if (request.shortName() != null) {
            chart.setShortName(blankToNull(request.shortName()));
        }
        if (request.kind() != null) {
            chart.setKind(request.kind());
        }
        if (request.labels() != null) {
            chart.setLabels(SizeChart.normalizeLabels(request.labels()));
        }
        return SizeChartView.of(chart);
    }

    /**
     * Archive: products made from it keep their sizes. Categories that opened with it open with no
     * chart; taking the same library chart again makes a fresh copy.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    @Transactional
    void archive(@PathVariable UUID id) {
        SizeChart chart = find(id);
        for (Category category : categories.findAllBySizeChartId(id)) {
            category.setSizeChartId(null);
        }
        chart.archive(clock.instant());
    }

    private SizeChart find(UUID id) {
        return charts.findById(id).filter(chart -> !chart.isArchived())
                .orElseThrow(() -> ApiException.notFound("size_chart_not_found", "no such size chart"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
