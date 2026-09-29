package app.trillopos.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
 * A shop's size table: every sizing system side by side, one row per size, so EU 42 reads across
 * as UK 8, US M 9, US W 10.5, CM 26.5. The first system is the one the shop labels its stock with;
 * its column holds each size's label, unique in the chart. Copied from the {@link SizeChartLibrary}
 * or typed by the shop, and editable either way. Products made from it keep the name, size label
 * and equivalents they were made with.
 */
@Entity
@Table(name = "size_chart")
public class SizeChart extends TenantEntity {

    public static final int MAX_SYSTEMS = 8;
    public static final int MAX_ROWS = 80;
    public static final int MAX_SYSTEM_LENGTH = 10;
    public static final int MAX_LABEL_LENGTH = 20;

    /** Systems and rows, checked: what a chart is made of. */
    public record Sizes(List<String> systems, List<List<String>> rows) {
    }

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private SizeChartKind kind;

    /** The library entry it was copied from; null for a chart the shop typed. */
    @Column(name = "template_key", length = 40)
    private String templateKey;

    /** Column names, the labelling system first; "" for a column without a name (S, M, L). */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "systems", nullable = false, columnDefinition = "text[]")
    private List<String> systems;

    /** The rows one after another, {@code systems.size()} cells each; "" where there is no match. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "cells", nullable = false, columnDefinition = "text[]")
    private List<String> cells;

    protected SizeChart() {
    }

    public SizeChart(String name, SizeChartKind kind, String templateKey, Sizes sizes) {
        this.name = name;
        this.kind = kind;
        this.templateKey = templateKey;
        setSizes(sizes);
    }

    /**
     * Trimmed and checked: 1–8 systems with different names, 1–80 sizes, each size labelled once in
     * the first system, no cell or name too long. Blank rows are dropped; a short row is padded.
     */
    public static Sizes check(List<String> systems, List<List<String>> rows) {
        List<String> names = systems == null ? List.of() : systems.stream().map(SizeChart::clean).toList();
        if (names.isEmpty() || names.size() > MAX_SYSTEMS) {
            throw ApiException.badRequest("invalid_size_chart",
                    "a size chart has 1 to " + MAX_SYSTEMS + " systems and 1 to " + MAX_ROWS + " sizes");
        }
        Set<String> seenNames = new HashSet<>();
        for (String system : names) {
            if (system.length() > MAX_SYSTEM_LENGTH) {
                throw ApiException.badRequest("size_label_too_long",
                        "a system name is at most " + MAX_SYSTEM_LENGTH + " characters: " + system);
            }
            if (!seenNames.add(system.toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("duplicate_size_system", "two systems are called " + system);
            }
        }
        List<List<String>> checked = new ArrayList<>();
        Set<String> seenLabels = new HashSet<>();
        for (List<String> row : rows == null ? List.<List<String>>of() : rows) {
            List<String> cells = row == null ? List.of() : row.stream().map(SizeChart::clean).toList();
            if (cells.size() > names.size()) {
                throw ApiException.badRequest("invalid_size_chart", "a row has more sizes than there are systems");
            }
            if (cells.stream().allMatch(String::isEmpty)) {
                continue;
            }
            List<String> padded = new ArrayList<>(cells);
            while (padded.size() < names.size()) {
                padded.add("");
            }
            for (String cell : padded) {
                if (cell.length() > MAX_LABEL_LENGTH) {
                    throw ApiException.badRequest("size_label_too_long",
                            "a size is at most " + MAX_LABEL_LENGTH + " characters: " + cell);
                }
            }
            String label = padded.get(0);
            if (label.isEmpty()) {
                throw ApiException.badRequest("size_label_missing",
                        "every size needs its " + (names.get(0).isEmpty() ? "" : names.get(0) + " ") + "size");
            }
            if (!seenLabels.add(label.toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("duplicate_size", label + " is listed twice");
            }
            checked.add(List.copyOf(padded));
        }
        if (checked.isEmpty() || checked.size() > MAX_ROWS) {
            throw ApiException.badRequest("invalid_size_chart",
                    "a size chart has 1 to " + MAX_SYSTEMS + " systems and 1 to " + MAX_ROWS + " sizes");
        }
        return new Sizes(names, List.copyOf(checked));
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    public List<List<String>> rows() {
        int width = systems.size();
        return IntStream.range(0, cells.size() / width)
                .mapToObj(i -> List.copyOf(cells.subList(i * width, (i + 1) * width)))
                .toList();
    }

    /** The first column: each size's label, in order. */
    public List<String> labels() {
        return rows().stream().map(row -> row.get(0)).toList();
    }

    /** The row whose label this is, matched case-insensitively; null when it is not in the chart. */
    public List<String> row(String label) {
        String wanted = label == null ? "" : label.strip();
        return rows().stream().filter(row -> row.get(0).equalsIgnoreCase(wanted)).findFirst().orElse(null);
    }

    /** "EU 42", or "M" when the labelling system has no name. */
    public String display(String label) {
        return named(systems.get(0), label);
    }

    /** The same size in the other systems: "UK 8 · US M 9 · CM 26.5"; null when there are none. */
    public String equivalents(List<String> row) {
        String text = IntStream.range(1, systems.size())
                .filter(i -> !row.get(i).isEmpty())
                .mapToObj(i -> named(systems.get(i), row.get(i)))
                .collect(Collectors.joining(" · "));
        return text.isEmpty() ? null : text;
    }

    private static String named(String system, String label) {
        return system.isEmpty() ? label : system + " " + label;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public List<String> getSystems() {
        return systems;
    }

    public void setSizes(Sizes sizes) {
        this.systems = List.copyOf(sizes.systems());
        this.cells = sizes.rows().stream().flatMap(List::stream).toList();
    }
}
