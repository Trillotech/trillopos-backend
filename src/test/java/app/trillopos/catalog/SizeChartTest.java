package app.trillopos.catalog;

import static app.trillopos.support.Api.json;
import static app.trillopos.support.Api.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import app.trillopos.support.Api;
import app.trillopos.support.Api.Owner;
import app.trillopos.support.IntegrationTest;

/**
 * Size charts (2026-09-29): one table per kind of goods, every sizing system side by side, so a
 * shop sees that EU 42 is UK 8, US men's 9, US women's 10.5 and 26.5 cm. Each size of a model is
 * still its own product (spec §4).
 */
class SizeChartTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    Api api;

    @BeforeEach
    void client() {
        api = new Api(mvc);
    }

    @Test
    void theLibraryLinesUpEverySizingSystem() throws Exception {
        Owner owner = api.signup();
        String body = body(api.call(get("/size-charts/library"), owner.token()).andExpect(status().isOk()));

        List<String> keys = JsonPath.read(body, "$[*].key");
        assertThat(keys).containsExactly("shoes", "sneakers", "kids_shoes", "womens_clothes", "mens_clothes",
                "kids_clothes", "trousers", "shirts", "bras", "one_size");
        assertThat(systemsOf(body, "shoes")).containsExactly("EU", "UK", "US M", "US W", "CM");
        assertThat(rowsOf(body, "shoes")).hasSize(14).contains(List.of("42", "8", "9", "10.5", "26.5"));
        assertThat(systemsOf(body, "sneakers")).containsExactly("US M", "US W", "UK", "EU", "CM");
        assertThat(rowsOf(body, "sneakers")).contains(List.of("9", "10.5", "8", "42.5", "27"), List.of("15", "", "14", "49.5", "33"));
        assertThat(rowsOf(body, "kids_shoes")).contains(List.of("32", "13", "1Y", "20"), List.of("33", "1", "1.5Y", "20.5"));
        assertThat(rowsOf(body, "womens_clothes")).contains(List.of("M", "38", "10", "6"));
        assertThat(rowsOf(body, "bras")).hasSize(30).contains(List.of("34B", "75B"), List.of("36DD", "80E"));

        // every library table is a valid table as it stands
        for (SizeChartLibrary.Template template : SizeChartLibrary.all()) {
            SizeChart.Sizes checked = SizeChart.check(template.systems(), template.rows());
            assertThat(checked.rows()).as(template.key()).isEqualTo(template.rows());
        }
    }

    @Test
    void takingALibraryChartTwiceGivesTheShopTheSameCopy() throws Exception {
        Owner owner = api.signup();
        String copy = read(api.call(json(post("/size-charts"), """
                {"templateKey":"shoes","name":"ဖိနပ် · EU ဆိုဒ်"}"""), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ဖိနပ် · EU ဆိုဒ်"))
                .andExpect(jsonPath("$.systems[0]").value("EU"))
                .andExpect(jsonPath("$.rows", hasSize(14)))
                .andExpect(jsonPath("$.kind").value("FOOTWEAR"))
                .andExpect(jsonPath("$.templateKey").value("shoes")), "$.id");

        api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes\"}"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(copy));
        api.call(get("/size-charts"), owner.token())
                .andExpect(jsonPath("$", hasSize(1)));
        api.call(json(post("/size-charts"), "{\"templateKey\":\"no_such_chart\"}"), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_chart_not_found"));

        // deleted, it no longer counts: the library gives a fresh copy
        api.call(delete("/size-charts/" + copy), owner.token()).andExpect(status().isNoContent());
        String fresh = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes\"}"), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Shoes · EU sizes")), "$.id");
        assertThat(fresh).isNotEqualTo(copy);
    }

    @Test
    void aShopLabelsByAnotherSystemTrimsItsTableAndTypesItsOwn() throws Exception {
        Owner owner = api.signup();
        String sneakers = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"sneakers\"}"), owner.token()),
                "$.id");

        // labelled by EU from now on, and without the two biggest sizes
        api.call(json(patch("/size-charts/" + sneakers), """
                {"systems":["EU","US M","US W","UK","CM"],
                 "rows":[["42","8.5","10","7.5","26.5"],["42.5","9","10.5","8","27"],["43","9.5","11","8.5","27.5"]]}"""),
                owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systems").value(contains("EU", "US M", "US W", "UK", "CM")))
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[1]").value(contains("42.5", "9", "10.5", "8", "27")));
        api.call(json(patch("/size-charts/" + sneakers), "{\"systems\":[\"EU\"]}"), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_size_chart"));

        // typed by the shop: trimmed, blank rows dropped, short rows filled with "no match"
        api.call(json(post("/size-charts"), """
                {"name":"Rings","systems":["US"," EU "],"rows":[[" 6 ","51.5"],["7","54"],["",""],["8"]]}"""),
                owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.systems").value(contains("US", "EU")))
                .andExpect(jsonPath("$.rows[0]").value(contains("6", "51.5")))
                .andExpect(jsonPath("$.rows[2]").value(contains("8", "")))
                .andExpect(jsonPath("$.kind").value("OTHER"))
                .andExpect(jsonPath("$.templateKey").doesNotExist());

        String table = "{\"name\":\"Bad\",\"systems\":%s,\"rows\":%s}";
        expectRefused(owner, table.formatted("[\"US\"]", "[[\"6\"],[\"6\"]]"), "duplicate_size");
        expectRefused(owner, table.formatted("[\"US\",\"EU\"]", "[[\"\",\"51.5\"]]"), "size_label_missing");
        expectRefused(owner, table.formatted("[\"US\",\"us\"]", "[[\"6\",\"6\"]]"), "duplicate_size_system");
        expectRefused(owner, table.formatted("[\"US\"]", "[[\"6\",\"51.5\"]]"), "invalid_size_chart");
        expectRefused(owner, table.formatted("[]", "[[\"6\"]]"), "invalid_size_chart");
        expectRefused(owner, table.formatted("[\"US\"]", "[[\"" + "9".repeat(21) + "\"]]"), "size_label_too_long");
        api.call(json(post("/size-charts"), "{\"systems\":[\"US\"],\"rows\":[[\"6\"]]}"), owner.token())
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCategoryOpensWithItsChartUntilTheChartIsRemoved() throws Exception {
        Owner owner = api.signup();
        String shoes = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes\"}"), owner.token()), "$.id");
        String footwear = read(api.call(json(post("/categories"), """
                {"name":"Footwear","sizeChartId":"%s"}""".formatted(shoes)), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sizeChartId").value(shoes)), "$.id");

        // renaming keeps the chart; clearing removes it; setting it again puts it back
        api.call(json(patch("/categories/" + footwear), "{\"name\":\"Shoes\"}"), owner.token())
                .andExpect(jsonPath("$.sizeChartId").value(shoes));
        api.call(json(patch("/categories/" + footwear), "{\"clearSizeChart\":true}"), owner.token())
                .andExpect(jsonPath("$.sizeChartId").doesNotExist());
        api.call(json(patch("/categories/" + footwear), "{\"sizeChartId\":\"%s\"}".formatted(shoes)), owner.token())
                .andExpect(jsonPath("$.sizeChartId").value(shoes));

        // a removed chart leaves the category without one
        api.call(delete("/size-charts/" + shoes), owner.token()).andExpect(status().isNoContent());
        api.call(get("/categories"), owner.token())
                .andExpect(jsonPath("$[0].sizeChartId").doesNotExist());
        api.call(json(patch("/categories/" + footwear), "{\"sizeChartId\":\"%s\"}".formatted(shoes)), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_chart_not_found"));
    }

    @Test
    void eachSizeIsItsOwnProductAndKnowsItsSizeInEverySystem() throws Exception {
        Owner owner = api.signup();
        String shoes = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes\"}"), owner.token()), "$.id");
        String footwear = read(api.call(json(post("/categories"), "{\"name\":\"Footwear\"}"), owner.token()), "$.id");

        String body = body(api.call(json(post("/products/sizes"), """
                {"name":"Converse Chuck 70 Black","categoryId":"%s","unit":"PIECE","retailPrice":180000,
                 "sizeChartId":"%s","locationId":"%s","unitCost":120000,
                 "sizes":[{"label":"42","quantity":2},{"label":"41","quantity":1},{"label":"43"}]}"""
                .formatted(footwear, shoes, owner.mainLocationId())), owner.token())
                .andExpect(status().isCreated()));

        // in the chart's order, named by the labelling system, the others beside it
        List<String> names = JsonPath.read(body, "$[*].name");
        assertThat(names).containsExactly("Converse Chuck 70 Black · EU 41", "Converse Chuck 70 Black · EU 42",
                "Converse Chuck 70 Black · EU 43");
        List<String> labels = JsonPath.read(body, "$[*].sizeLabel");
        assertThat(labels).containsExactly("41", "42", "43");
        List<String> equivalents = JsonPath.read(body, "$[*].sizeEquivalents");
        assertThat(equivalents).containsExactly("UK 7.5 · US M 8.5 · US W 10 · CM 26",
                "UK 8 · US M 9 · US W 10.5 · CM 26.5", "UK 9 · US M 10 · US W 11.5 · CM 27");
        List<String> groups = JsonPath.read(body, "$[*].productGroupKey");
        assertThat(groups).doesNotContainNull().containsOnly(groups.get(0));
        assertThat(groups.get(0)).startsWith("FOO-");
        List<String> skus = JsonPath.read(body, "$[*].sku");
        assertThat(skus).containsExactly(groups.get(0) + "-41", groups.get(0) + "-42", groups.get(0) + "-43");
        List<String> charts = JsonPath.read(body, "$[*].sizeChartId");
        assertThat(charts).containsOnly(shoes);

        // stock per size, from one opening document
        List<String> ids = JsonPath.read(body, "$[*].id");
        api.call(get("/stock-balances?productId=" + ids.get(0)), owner.token())
                .andExpect(jsonPath("$[0].quantity").value(1));
        api.call(get("/stock-balances?productId=" + ids.get(1)), owner.token())
                .andExpect(jsonPath("$[0].quantity").value(2));
        api.call(get("/stock-balances?productId=" + ids.get(2)), owner.token())
                .andExpect(jsonPath("$", hasSize(0)));
        List<String> documentIds = JsonPath.read(body(api.call(get("/stock-documents"), owner.token())), "$[*].id");
        assertThat(documentIds).hasSize(1);
        api.call(get("/stock-documents/" + documentIds.get(0)), owner.token())
                .andExpect(jsonPath("$.type").value("OPENING"))
                .andExpect(jsonPath("$.lines", hasSize(2)));

        // this brand runs half a size small: the shop corrects one product, not the chart
        api.call(json(patch("/products/" + ids.get(1)), "{\"sizeEquivalents\":\"UK 7.5 · US M 8.5\",\"retailPrice\":175000}"),
                owner.token())
                .andExpect(jsonPath("$.sizeEquivalents").value("UK 7.5 · US M 8.5"))
                .andExpect(jsonPath("$.retailPrice").value(175000))
                .andExpect(jsonPath("$.sizeLabel").value("42"));
        api.call(get("/products/" + ids.get(0)), owner.token())
                .andExpect(jsonPath("$.retailPrice").value(180000))
                .andExpect(jsonPath("$.sizeEquivalents").value("UK 7.5 · US M 8.5 · US W 10 · CM 26"));
        api.call(json(patch("/products/" + ids.get(1)), "{\"sizeEquivalents\":\"\"}"), owner.token())
                .andExpect(jsonPath("$.sizeEquivalents").doesNotExist());
    }

    @Test
    void theSizesAreSavedAllOrNothing() throws Exception {
        Owner owner = api.signup();
        String womens = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"womens_clothes\"}"),
                owner.token()), "$.id");
        String request = """
                {"name":"Silk Blouse","unit":"PIECE","retailPrice":45000,"sizeChartId":"%s",
                 "locationId":%s,"unitCost":30000,"sizes":%s}""";

        api.call(json(post("/products/sizes"), request.formatted(womens, "\"" + owner.mainLocationId() + "\"",
                "[{\"label\":\"M\",\"quantity\":3},{\"label\":\"XXXXL\",\"quantity\":1}]")), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_not_in_chart"));
        api.call(json(post("/products/sizes"), request.formatted(womens, "\"" + owner.mainLocationId() + "\"",
                "[{\"label\":\"M\"},{\"label\":\"m\"}]")), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("duplicate_size"));
        api.call(json(post("/products/sizes"), request.formatted(womens, "null",
                "[{\"label\":\"M\",\"quantity\":3}]")), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("location_required"));
        api.call(json(post("/products/sizes"), request.formatted(womens, "null", "[]")), owner.token())
                .andExpect(status().isBadRequest());
        api.call(get("/products"), owner.token()).andExpect(jsonPath("$", hasSize(0)));

        // no stock yet is fine: the sizes wait for a stock-in; a size typed in any case matches.
        // S, M, L need no system name in front of them
        api.call(json(post("/products/sizes"), request.formatted(womens, "null",
                "[{\"label\":\"s\"},{\"label\":\"M\",\"quantity\":0}]")), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[*].name").value(contains("Silk Blouse · S", "Silk Blouse · M")))
                .andExpect(jsonPath("$[0].sizeEquivalents").value("EU 36 · UK 8 · US 4"));

        // a removed chart makes no more products
        api.call(delete("/size-charts/" + womens), owner.token()).andExpect(status().isNoContent());
        api.call(json(post("/products/sizes"), request.formatted(womens, "null", "[{\"label\":\"L\"}]")),
                owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_chart_not_found"));
    }

    @Test
    void chartsBelongToTheirShopAndOnlyManagersChangeThem() throws Exception {
        Owner owner = api.signup();
        String shoes = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes\"}"), owner.token()), "$.id");

        Owner other = api.signup();
        api.call(get("/size-charts"), other.token()).andExpect(jsonPath("$", hasSize(0)));
        api.call(json(post("/products/sizes"), """
                {"name":"Borrowed","unit":"PIECE","retailPrice":1000,"sizeChartId":"%s","sizes":[{"label":"40"}]}"""
                .formatted(shoes)), other.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_chart_not_found"));
        api.call(json(patch("/size-charts/" + shoes), "{\"name\":\"Mine now\"}"), other.token())
                .andExpect(status().isNotFound());

        String cashier = api.member(owner, "CASHIER");
        api.call(get("/size-charts"), cashier).andExpect(jsonPath("$", hasSize(1)));
        api.call(json(post("/size-charts"), "{\"templateKey\":\"sneakers\"}"), cashier)
                .andExpect(status().isForbidden());
        api.call(json(post("/products/sizes"), """
                {"name":"Cashier shoe","unit":"PIECE","retailPrice":1000,"sizeChartId":"%s","sizes":[{"label":"40"}]}"""
                .formatted(shoes)), cashier)
                .andExpect(status().isForbidden());
        String manager = api.member(owner, "STOCK_MANAGER");
        api.call(json(patch("/size-charts/" + shoes), "{\"name\":\"EU shoes\"}"), manager)
                .andExpect(status().isOk());
    }

    private void expectRefused(Owner owner, String chart, String code) throws Exception {
        api.call(json(post("/size-charts"), chart), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));
    }

    private static List<String> systemsOf(String library, String key) {
        List<List<String>> found = JsonPath.read(library, "$[?(@.key == '" + key + "')].systems");
        return found.get(0);
    }

    private static List<List<String>> rowsOf(String library, String key) {
        List<List<List<String>>> found = JsonPath.read(library, "$[?(@.key == '" + key + "')].rows");
        return found.get(0);
    }

    private static String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
