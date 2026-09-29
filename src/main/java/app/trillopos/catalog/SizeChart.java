package app.trillopos.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import app.trillopos.shared.persistence.TenantEntity;
import app.trillopos.shared.web.ApiException;

/**
 * A shop's list of sizes, smallest first: "EU" 35…48, "US men" 4…15, S/M/L/XL. Copied from the
 * {@link SizeChartLibrary} or typed by the shop, and editable either way. Products created from
 * it keep the name and size label they were created with.
 */
@Entity
@Table(name = "size_chart")
public class SizeChart extends TenantEntity {

    public static final int MAX_LABELS = 80;
    public static final int MAX_LABEL_LENGTH = 32;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** Put before the size in a product's name: "EU" makes "Converse · EU 38". Null for S/M/L. */
    @Column(name = "short_name", length = 10)
    private String shortName;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private SizeChartKind kind;

    /** The library entry it was copied from; null for a chart the shop typed. */
    @Column(name = "template_key", length = 40)
    private String templateKey;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "labels", nullable = false, columnDefinition = "text[]")
    private List<String> labels;

    protected SizeChart() {
    }

    public SizeChart(String name, String shortName, SizeChartKind kind, String templateKey, List<String> labels) {
        this.name = name;
        this.shortName = shortName;
        this.kind = kind;
        this.templateKey = templateKey;
        this.labels = List.copyOf(labels);
    }

    /**
     * Trimmed, blanks dropped, each size once (case-insensitively), in the order given. Refused when
     * nothing is left, when there are too many, or when one is too long.
     */
    public static List<String> normalizeLabels(List<String> labels) {
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String label : labels == null ? List.<String>of() : labels) {
            String trimmed = label == null ? "" : label.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_LABEL_LENGTH) {
                throw ApiException.badRequest("size_label_too_long",
                        "a size is at most " + MAX_LABEL_LENGTH + " characters: " + trimmed);
            }
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                normalized.add(trimmed);
            }
        }
        if (normalized.isEmpty() || normalized.size() > MAX_LABELS) {
            throw ApiException.badRequest("invalid_size_labels", "a size chart has 1 to " + MAX_LABELS + " sizes");
        }
        return normalized;
    }

    /** The label as written in this chart, matched case-insensitively; null when it is not in it. */
    public String findLabel(String label) {
        String wanted = label == null ? "" : label.strip();
        return labels.stream().filter(l -> l.equalsIgnoreCase(wanted)).findFirst().orElse(null);
    }

    /** "EU 38", or "M" for a chart without a short name. */
    public String display(String label) {
        return shortName == null ? label : shortName + " " + label;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public SizeChartKind getKind() {
        return kind;
    }

    public void setKind(SizeChartKind kind) {
        this.kind = kind;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = List.copyOf(labels);
    }
}
