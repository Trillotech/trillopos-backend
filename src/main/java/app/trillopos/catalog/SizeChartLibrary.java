package app.trillopos.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The built-in size charts a shop copies from, one per sizing system: shoes in EU, UK, US men,
 * US women and centimetres, children's shoes, clothes in letters and in EU, UK and US numbers,
 * waist, collar, bras, children's ages. Half sizes are in; a shop removes what it never stocks
 * from its own copy. Keys are stable — the web translates the names by key.
 */
public final class SizeChartLibrary {

    public record Template(String key, SizeChartKind kind, String name, String shortName, List<String> labels) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("shoes_eu", SizeChartKind.FOOTWEAR, "Shoes · EU", "EU",
                    concat(steps("35", "46", "0.5"), List.of("47", "48"))),
            new Template("shoes_uk", SizeChartKind.FOOTWEAR, "Shoes · UK", "UK",
                    concat(steps("1", "13", "0.5"), List.of("14"))),
            new Template("shoes_us_men", SizeChartKind.FOOTWEAR, "Shoes · US men", "US M",
                    concat(steps("4", "13", "0.5"), List.of("14", "15"))),
            new Template("shoes_us_women", SizeChartKind.FOOTWEAR, "Shoes · US women", "US W",
                    steps("4", "12", "0.5")),
            new Template("shoes_cm", SizeChartKind.FOOTWEAR, "Shoes · CM", "CM",
                    steps("21.5", "31", "0.5")),
            new Template("kids_shoes_eu", SizeChartKind.FOOTWEAR, "Kids' shoes · EU", "EU",
                    steps("16", "35", "1")),
            new Template("kids_shoes_uk", SizeChartKind.FOOTWEAR, "Kids' shoes · UK", "UK kids",
                    steps("2", "13.5", "0.5")),
            new Template("kids_shoes_us", SizeChartKind.FOOTWEAR, "Kids' shoes · US", "US",
                    List.of("2C", "3C", "4C", "5C", "6C", "7C", "8C", "9C", "10C", "10.5C", "11C", "11.5C", "12C",
                            "12.5C", "13C", "13.5C", "1Y", "1.5Y", "2Y", "2.5Y", "3Y", "3.5Y", "4Y", "4.5Y", "5Y",
                            "5.5Y", "6Y", "6.5Y", "7Y")),
            new Template("kids_shoes_cm", SizeChartKind.FOOTWEAR, "Kids' shoes · CM", "CM",
                    steps("10", "21", "0.5")),
            new Template("clothes_letter", SizeChartKind.CLOTHING, "Clothes · S M L", null,
                    List.of("XXS", "XS", "S", "M", "L", "XL", "XXL", "3XL", "4XL", "5XL")),
            new Template("clothes_eu", SizeChartKind.CLOTHING, "Clothes · EU", "EU",
                    steps("32", "58", "2")),
            new Template("clothes_uk_women", SizeChartKind.CLOTHING, "Women's clothes · UK", "UK",
                    steps("4", "26", "2")),
            new Template("clothes_us_women", SizeChartKind.CLOTHING, "Women's clothes · US", "US",
                    concat(List.of("00"), steps("0", "22", "2"))),
            new Template("trousers_waist", SizeChartKind.CLOTHING, "Trousers · waist", "W",
                    concat(steps("24", "36", "1"), steps("38", "44", "2"))),
            new Template("shirts_collar", SizeChartKind.CLOTHING, "Shirts · collar", "Collar",
                    steps("14", "18", "0.5")),
            new Template("bras_us_uk", SizeChartKind.CLOTHING, "Bras · US/UK", null,
                    cross(List.of("30", "32", "34", "36", "38", "40"), List.of("A", "B", "C", "D", "DD"))),
            new Template("bras_eu", SizeChartKind.CLOTHING, "Bras · EU/Asia", null,
                    cross(List.of("65", "70", "75", "80", "85", "90"), List.of("A", "B", "C", "D", "E"))),
            new Template("kids_clothes_age", SizeChartKind.CLOTHING, "Kids' clothes · age", null,
                    List.of("0-3M", "3-6M", "6-12M", "12-18M", "18-24M", "2-3Y", "3-4Y", "4-5Y", "5-6Y", "6-7Y",
                            "7-8Y", "8-9Y", "9-10Y", "10-11Y", "11-12Y", "12-13Y", "13-14Y")),
            new Template("kids_clothes_us", SizeChartKind.CLOTHING, "Kids' clothes · US", "US",
                    List.of("0-3M", "3-6M", "6-9M", "12M", "18M", "24M", "2T", "3T", "4T", "5", "6", "7", "8", "10",
                            "12", "14", "16")),
            new Template("one_size", SizeChartKind.OTHER, "One size", null, List.of("Free size")));

    private SizeChartLibrary() {
    }

    public static List<Template> all() {
        return TEMPLATES;
    }

    public static Optional<Template> find(String key) {
        return TEMPLATES.stream().filter(t -> t.key().equals(key)).findFirst();
    }

    /** "35", "35.5", "36" … "46": from and to inclusive, written without trailing zeros. */
    private static List<String> steps(String from, String to, String step) {
        List<String> labels = new ArrayList<>();
        BigDecimal end = new BigDecimal(to);
        BigDecimal by = new BigDecimal(step);
        for (BigDecimal size = new BigDecimal(from); size.compareTo(end) <= 0; size = size.add(by)) {
            labels.add(size.stripTrailingZeros().toPlainString());
        }
        return labels;
    }

    private static List<String> cross(List<String> bands, List<String> cups) {
        return bands.stream().flatMap(band -> cups.stream().map(cup -> band + cup)).toList();
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        return Arrays.stream(parts).flatMap(List::stream).toList();
    }
}
