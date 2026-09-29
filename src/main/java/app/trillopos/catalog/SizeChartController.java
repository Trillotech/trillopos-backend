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
 * The shop's size tables, and the library they are copied from. Taking a table from the library
 * twice returns the copy the shop already has, edits and all.
 */
@RestController
@RequestMapping("/size-charts")
class SizeChartController {

    /** {@code systems}: the column names, the labelling one first; {@code rows}: one per size, a cell per system. */
    record SizeChartView(UUID id, String name, SizeChartKind kind, String templateKey, List<String> systems,
            List<List<String>> rows) {

        static SizeChartView of(SizeChart c) {
            return new SizeChartView(c.getId(), c.getName(), c.getKind(), c.getTemplateKey(), c.getSystems(),
                    c.rows());
        }
    }

    record SizeChartTemplateView(String key, SizeChartKind kind, String name, List<String> systems,
            List<List<String>> rows) {

        static SizeChartTemplateView of(Template t) {
            return new SizeChartTemplateView(t.key(), t.kind(), t.name(), t.systems(), t.rows());
        }
    }

    /**
     * From the library: {@code templateKey}, and {@code name} in the shop's language if wanted.
     * Typed by the shop: {@code name}, {@code systems} and {@code rows}, and optionally {@code kind}.
     */
    record SizeChartCreate(@Size(max = 40) String templateKey, @Size(max = 80) String name, SizeChartKind kind,
            List<String> systems, List<List<String>> rows) {
    }

    /** Every field optional; {@code systems} and {@code rows} replace the table together. */
    record SizeChartUpdate(@Size(min = 1, max = 80) String name, SizeChartKind kind, List<String> systems,
            List<List<String>> rows) {
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
            SizeChart copy = charts.save(new SizeChart(name == null ? template.name() : name, template.kind(),
                    template.key(), SizeChart.check(template.systems(), template.rows())));
            return ResponseEntity.status(HttpStatus.CREATED).body(SizeChartView.of(copy));
        }
        String name = blankToNull(request.name());
        if (name == null) {
            throw ApiException.badRequest("validation_failed", "a size chart needs a name");
        }
        SizeChart chart = charts.save(new SizeChart(name,
                request.kind() == null ? SizeChartKind.OTHER : request.kind(), null,
                SizeChart.check(request.systems(), request.rows())));
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
        if (request.kind() != null) {
            chart.setKind(request.kind());
        }
        if (request.systems() != null || request.rows() != null) {
            if (request.systems() == null || request.rows() == null) {
                throw ApiException.badRequest("invalid_size_chart", "systems and rows change together");
            }
            chart.setSizes(SizeChart.check(request.systems(), request.rows()));
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
