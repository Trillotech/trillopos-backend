package app.trillopos.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The size tables a shop copies from: one per kind of goods, every sizing system side by side, so
 * EU 42 reads across as UK 8, US men's 9, US women's 10.5 and 26.5 cm. Keys are stable — the web
 * translates the names by key.
 *
 * Shoe conversions are a standard guide, not a law: brands differ by up to half a size, so a shop
 * corrects its copy, or one product's equivalents, from the box label.
 * - Shoes, by EU size: UK = EU / 1.27 − 25, US men = UK + 1, US women = US men + 1.5, and foot
 *   length in cm = EU / 1.5 − 1.5, each to the nearest half.
 * - Sneakers, by US size: the half-size grid printed on most sneaker boxes (US 9 = UK 8 = EU 42.5 =
 *   27 cm); US women = US men + 1.5.
 * - Kids' shoes: EU 16–35 with the children's UK scale (restarting at 1 from EU 33), US C then Y.
 */
public final class SizeChartLibrary {

    public record Template(String key, SizeChartKind kind, String name, List<String> systems,
            List<List<String>> rows) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("shoes", SizeChartKind.FOOTWEAR, "Shoes · EU sizes",
                    List.of("EU", "UK", "US M", "US W", "CM"), rows(
                            "35|2.5|3.5|5|22", "36|3.5|4.5|6|22.5", "37|4|5|6.5|23", "38|5|6|7.5|24",
                            "39|5.5|6.5|8|24.5", "40|6.5|7.5|9|25", "41|7.5|8.5|10|26", "42|8|9|10.5|26.5",
                            "43|9|10|11.5|27", "44|9.5|10.5|12|28", "45|10.5|11.5|13|28.5", "46|11|12|13.5|29",
                            "47|12|13|14.5|30", "48|13|14|15.5|30.5")),
            new Template("sneakers", SizeChartKind.FOOTWEAR, "Sneakers · US sizes",
                    List.of("US M", "US W", "UK", "EU", "CM"), rows(
                            "3.5|5|3|35.5|21.5", "4|5.5|3.5|36|22", "4.5|6|4|36.5|22.5", "5|6.5|4.5|37.5|23",
                            "5.5|7|5|38|23.5", "6|7.5|5.5|38.5|24", "6.5|8|6|39|24.5", "7|8.5|6|40|25",
                            "7.5|9|6.5|40.5|25.5", "8|9.5|7|41|26", "8.5|10|7.5|42|26.5", "9|10.5|8|42.5|27",
                            "9.5|11|8.5|43|27.5", "10|11.5|9|44|28", "10.5|12|9.5|44.5|28.5", "11|12.5|10|45|29",
                            "11.5|13|10.5|45.5|29.5", "12|13.5|11|46|30", "12.5|14|11.5|47|30.5",
                            "13|14.5|12|47.5|31", "14||13|48.5|32", "15||14|49.5|33")),
            new Template("kids_shoes", SizeChartKind.FOOTWEAR, "Kids’ shoes",
                    List.of("EU", "UK", "US", "CM"), rows(
                            "16|0.5|1.5C|9", "17|1.5|2.5C|10", "18|2|3C|10.5", "19|3|4C|11", "20|3.5|4.5C|12",
                            "21|4.5|5.5C|12.5", "22|5.5|6.5C|13", "23|6|7C|14", "24|7|8C|14.5", "25|7.5|8.5C|15",
                            "26|8.5|9.5C|16", "27|9.5|10.5C|16.5", "28|10|11C|17", "29|11|12C|18",
                            "30|11.5|12.5C|18.5", "31|12.5|13.5C|19", "32|13|1Y|20", "33|1|1.5Y|20.5",
                            "34|2|2.5Y|21", "35|2.5|3Y|22")),
            new Template("womens_clothes", SizeChartKind.CLOTHING, "Women’s clothes",
                    List.of("", "EU", "UK", "US"), rows(
                            "XXS|32|4|0", "XS|34|6|2", "S|36|8|4", "M|38|10|6", "L|40|12|8", "XL|42|14|10",
                            "XXL|44|16|12", "3XL|46|18|14", "4XL|48|20|16", "5XL|50|22|18")),
            new Template("mens_clothes", SizeChartKind.CLOTHING, "Men’s clothes",
                    List.of("", "EU", "US/UK"), rows(
                            "XS|44|34", "S|46|36", "M|48-50|38-40", "L|52-54|42-44", "XL|56|46", "XXL|58|48",
                            "3XL|60|50", "4XL|62|52")),
            new Template("kids_clothes", SizeChartKind.CLOTHING, "Kids’ clothes",
                    List.of("", "EU", "US"), rows(
                            "0-3M|62|0-3M", "3-6M|68|3-6M", "6-12M|80|12M", "12-18M|86|18M", "18-24M|92|24M",
                            "2-3Y|98|2T", "3-4Y|104|3T", "4-5Y|110|4T", "5-6Y|116|5", "6-7Y|122|6",
                            "7-8Y|128|7", "8-9Y|134|8", "9-10Y|140|10", "10-11Y|146|10-12", "11-12Y|152|12",
                            "12-13Y|158|14", "13-14Y|164|16")),
            new Template("trousers", SizeChartKind.CLOTHING, "Trousers · waist",
                    List.of("W", "CM"), rows(
                            "24|61", "25|64", "26|66", "27|69", "28|71", "29|74", "30|76", "31|79", "32|81",
                            "33|84", "34|86", "35|89", "36|91", "38|97", "40|102", "42|107", "44|112")),
            new Template("shirts", SizeChartKind.CLOTHING, "Shirts · collar",
                    List.of("Collar", "EU", ""), rows(
                            "14|36|S", "14.5|37|S", "15|38|M", "15.5|39|M", "16|41|L", "16.5|42|L",
                            "17|43|XL", "17.5|44|XL", "18|46|XXL")),
            new Template("bras", SizeChartKind.CLOTHING, "Bras",
                    List.of("US/UK", "EU"), bras()),
            new Template("one_size", SizeChartKind.OTHER, "One size",
                    List.of(""), rows("Free size")));

    private SizeChartLibrary() {
    }

    public static List<Template> all() {
        return TEMPLATES;
    }

    public static Optional<Template> find(String key) {
        return TEMPLATES.stream().filter(t -> t.key().equals(key)).findFirst();
    }

    /** "42|8|9" → ["42", "8", "9"]; an empty cell is a size with no match in that system. */
    private static List<List<String>> rows(String... lines) {
        return Arrays.stream(lines).map(line -> List.of(line.split("\\|", -1))).toList();
    }

    /** US/UK bands 30–40 are EU 65–90; cups A–D are the same, and US/UK DD is EU E. */
    private static List<List<String>> bras() {
        List<String> bands = List.of("30", "32", "34", "36", "38", "40");
        List<String> cups = List.of("A", "B", "C", "D", "DD");
        return bands.stream().flatMap(band -> cups.stream().map(cup -> List.of(band + cup,
                (Integer.parseInt(band) * 5 / 2 - 10) + (cup.equals("DD") ? "E" : cup)))).toList();
    }
}
