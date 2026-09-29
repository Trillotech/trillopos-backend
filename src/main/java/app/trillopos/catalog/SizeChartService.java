package app.trillopos.catalog;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.trillopos.catalog.SizeChartLibrary.Template;
import app.trillopos.shared.web.ApiException;

/** The shop's copies of library size tables: one per table, edits and all. */
@Service
public class SizeChartService {

    /** A copy, and whether this call made it. */
    public record Copy(SizeChart chart, boolean created) {
    }

    private final SizeChartRepository charts;

    public SizeChartService(SizeChartRepository charts) {
        this.charts = charts;
    }

    /**
     * The shop's copy of the library table {@code templateKey}: the one it already has, or a new one
     * named {@code name} (the library's English name when blank).
     */
    @Transactional
    public Copy copy(String templateKey, String name) {
        Template template = SizeChartLibrary.find(templateKey)
                .orElseThrow(() -> ApiException.badRequest("size_chart_not_found", "no such chart in the library"));
        Optional<SizeChart> existing = charts.findByTemplateKeyAndArchivedAtIsNull(template.key());
        if (existing.isPresent()) {
            return new Copy(existing.get(), false);
        }
        String named = name == null || name.isBlank() ? template.name() : name.strip();
        return new Copy(charts.save(new SizeChart(named, template.kind(), template.key(),
                SizeChart.check(template.systems(), template.rows()))), true);
    }
}
