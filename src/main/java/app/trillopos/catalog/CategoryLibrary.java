package app.trillopos.catalog;

import java.util.List;
import java.util.Optional;

/**
 * The categories TrilloPOS offers ready-made. Those whose goods come in sizes bring their size
 * tables: {@code sizeTemplateKeys} are the library tables that fit, the first being the one it
 * opens with (Footwear: shoes, then sneakers and kids’ shoes — never clothes), and
 * {@code sizeKind} is the kind a table the shop makes itself must be to fit. Keys are stable —
 * the web translates the names by key.
 */
public final class CategoryLibrary {

    public record Template(String key, String name, SizeChartKind sizeKind, List<String> sizeTemplateKeys) {

        /** The table it opens with; null for goods without sizes. */
        public String sizeTemplateKey() {
            return sizeTemplateKeys.isEmpty() ? null : sizeTemplateKeys.get(0);
        }
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("footwear", "Footwear", SizeChartKind.FOOTWEAR, List.of("shoes", "sneakers", "kids_shoes")),
            new Template("womens_clothing", "Women’s clothing", SizeChartKind.CLOTHING,
                    List.of("womens_clothes", "trousers", "bras")),
            new Template("mens_clothing", "Men’s clothing", SizeChartKind.CLOTHING,
                    List.of("mens_clothes", "trousers", "shirts")),
            new Template("kids_clothing", "Kids’ clothing", SizeChartKind.CLOTHING, List.of("kids_clothes")),
            new Template("bags", "Bags", null, List.of()),
            new Template("accessories", "Accessories", null, List.of()),
            new Template("jewellery", "Jewellery & watches", null, List.of()),
            new Template("beauty", "Beauty & personal care", null, List.of()),
            new Template("health", "Health & medicine", null, List.of()),
            new Template("phones", "Phones & accessories", null, List.of()),
            new Template("electronics", "Electronics", null, List.of()),
            new Template("food", "Food", null, List.of()),
            new Template("drinks", "Drinks", null, List.of()),
            new Template("household", "Household", null, List.of()),
            new Template("stationery", "Stationery & books", null, List.of()),
            new Template("toys", "Toys & baby", null, List.of()),
            new Template("sports", "Sports", null, List.of()));

    private CategoryLibrary() {
    }

    public static List<Template> all() {
        return TEMPLATES;
    }

    public static Optional<Template> find(String key) {
        return TEMPLATES.stream().filter(t -> t.key().equals(key)).findFirst();
    }
}
