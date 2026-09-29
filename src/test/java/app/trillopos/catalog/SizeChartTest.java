package app.trillopos.catalog;

import static app.trillopos.support.Api.json;
import static app.trillopos.support.Api.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
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
 * Size charts (2026-09-29): a shoe shop labels sizes in EU, UK, US men, US women, kids or cm; a
 * clothes shop in letters or numbers. Each size of a model is still its own product (spec §4).
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
    void theLibraryCoversEverySizingSystemWithHalfSizes() throws Exception {
        Owner owner = api.signup();
        api.call(get("/size-charts/library"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key").value(hasItems("shoes_eu", "shoes_uk", "shoes_us_men",
                        "shoes_us_women", "shoes_cm", "kids_shoes_eu", "kids_shoes_uk", "kids_shoes_us",
                        "clothes_letter", "clothes_eu", "clothes_uk_women", "clothes_us_women", "trousers_waist",
                        "kids_clothes_age", "one_size")))
                .andExpect(jsonPath("$[?(@.key == 'shoes_eu')].labels[0]").value("35"))
                .andExpect(jsonPath("$[?(@.key == 'shoes_eu')].labels[*]").value(hasItems("35.5", "42", "42.5", "48")))
                .andExpect(jsonPath("$[?(@.key == 'shoes_us_men')].shortName").value("US M"))
                .andExpect(jsonPath("$[?(@.key == 'kids_shoes_us')].labels[*]").value(hasItems("10.5C", "3.5Y")))
                .andExpect(jsonPath("$[?(@.key == 'clothes_letter')].labels[*]").value(hasItems("S", "M", "XL")));

        // every chart in the library is a valid chart: no blank, repeated or too-long size
        for (SizeChartLibrary.Template template : SizeChartLibrary.all()) {
            assertThat(SizeChart.normalizeLabels(template.labels())).as(template.key()).isEqualTo(template.labels());
        }
    }

    @Test
    void takingALibraryChartTwiceGivesTheShopTheSameCopy() throws Exception {
        Owner owner = api.signup();
        String copy = read(api.call(json(post("/size-charts"), """
                {"templateKey":"shoes_eu","name":"ဖိနပ် · EU"}"""), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ဖိနပ် · EU"))
                .andExpect(jsonPath("$.shortName").value("EU"))
                .andExpect(jsonPath("$.kind").value("FOOTWEAR"))
                .andExpect(jsonPath("$.templateKey").value("shoes_eu")), "$.id");

        api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes_eu\"}"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(copy));
        api.call(get("/size-charts"), owner.token())
                .andExpect(jsonPath("$", hasSize(1)));
        api.call(json(post("/size-charts"), "{\"templateKey\":\"no_such_chart\"}"), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_chart_not_found"));

        // deleted, it no longer counts: the library gives a fresh copy
        api.call(delete("/size-charts/" + copy), owner.token()).andExpect(status().isNoContent());
        String fresh = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes_eu\"}"), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Shoes · EU")), "$.id");
        assertThat(fresh).isNotEqualTo(copy);
    }

    @Test
    void aShopTrimsItsCopyAndTypesItsOwnCharts() throws Exception {
        Owner owner = api.signup();
        String eu = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes_eu\"}"), owner.token()), "$.id");

        // whole sizes only, and a 49 for the big feet
        api.call(json(patch("/size-charts/" + eu), """
                {"labels":["38","39","40","41","42","43","44","45","49"],"shortName":""}"""), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels", hasSize(9)))
                .andExpect(jsonPath("$.shortName").doesNotExist());

        // typed by the shop: spaces trimmed, blanks and repeats dropped, order kept
        api.call(json(post("/size-charts"), """
                {"name":"Rings","shortName":"No.","labels":[" 6 ","7","","7","8"]}"""), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.labels").value(contains("6", "7", "8")))
                .andExpect(jsonPath("$.kind").value("OTHER"))
                .andExpect(jsonPath("$.templateKey").doesNotExist());

        api.call(json(post("/size-charts"), "{\"name\":\"Empty\",\"labels\":[\" \"]}"), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_size_labels"));
        api.call(json(post("/size-charts"), "{\"labels\":[\"S\"]}"), owner.token())
                .andExpect(status().isBadRequest());
        api.call(json(patch("/size-charts/" + eu), "{\"labels\":[\"" + "9".repeat(33) + "\"]}"), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_label_too_long"));
    }

    @Test
    void aCategoryOpensWithItsChartUntilTheChartIsRemoved() throws Exception {
        Owner owner = api.signup();
        String usMen = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes_us_men\"}"),
                owner.token()), "$.id");
        String footwear = read(api.call(json(post("/categories"), """
                {"name":"Footwear","sizeChartId":"%s"}""".formatted(usMen)), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sizeChartId").value(usMen)), "$.id");

        // renaming keeps the chart; clearing removes it; setting it again puts it back
        api.call(json(patch("/categories/" + footwear), "{\"name\":\"Shoes\"}"), owner.token())
                .andExpect(jsonPath("$.sizeChartId").value(usMen));
        api.call(json(patch("/categories/" + footwear), "{\"clearSizeChart\":true}"), owner.token())
                .andExpect(jsonPath("$.sizeChartId").doesNotExist());
        api.call(json(patch("/categories/" + footwear), "{\"sizeChartId\":\"%s\"}".formatted(usMen)), owner.token())
                .andExpect(jsonPath("$.sizeChartId").value(usMen));

        // a removed chart leaves the category without one
        api.call(delete("/size-charts/" + usMen), owner.token()).andExpect(status().isNoContent());
        api.call(get("/categories"), owner.token())
                .andExpect(jsonPath("$[0].sizeChartId").doesNotExist());
        api.call(json(patch("/categories/" + footwear), "{\"sizeChartId\":\"%s\"}".formatted(usMen)), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_chart_not_found"));
    }

    @Test
    void oneModelInManySizesIsOneProductPerSizeWithItsOwnOpeningStock() throws Exception {
        Owner owner = api.signup();
        String usMen = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes_us_men\"}"),
                owner.token()), "$.id");
        String footwear = read(api.call(json(post("/categories"), "{\"name\":\"Footwear\"}"), owner.token()), "$.id");

        String body = api.call(json(post("/products/sizes"), """
                {"name":"Converse Chuck 70 Black","categoryId":"%s","unit":"PIECE","retailPrice":180000,
                 "sizeChartId":"%s","locationId":"%s","unitCost":120000,
                 "sizes":[{"label":"9","quantity":2},{"label":"8.5","quantity":1},{"label":"10"}]}"""
                .formatted(footwear, usMen, owner.mainLocationId())), owner.token())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // in the chart's order, each its own product with the size in its name
        List<String> names = JsonPath.read(body, "$[*].name");
        assertThat(names).containsExactly("Converse Chuck 70 Black · US M 8.5", "Converse Chuck 70 Black · US M 9",
                "Converse Chuck 70 Black · US M 10");
        List<String> labels = JsonPath.read(body, "$[*].sizeLabel");
        assertThat(labels).containsExactly("8.5", "9", "10");
        List<String> groups = JsonPath.read(body, "$[*].productGroupKey");
        assertThat(groups).doesNotContainNull().containsOnly(groups.get(0));
        assertThat(groups.get(0)).startsWith("FOO-");
        List<String> skus = JsonPath.read(body, "$[*].sku");
        assertThat(skus).containsExactly(groups.get(0) + "-8.5", groups.get(0) + "-9", groups.get(0) + "-10");
        List<String> charts = JsonPath.read(body, "$[*].sizeChartId");
        assertThat(charts).containsOnly(usMen);

        // stock per size, from one opening document
        List<String> ids = JsonPath.read(body, "$[*].id");
        api.call(get("/stock-balances?productId=" + ids.get(0)), owner.token())
                .andExpect(jsonPath("$[0].quantity").value(1));
        api.call(get("/stock-balances?productId=" + ids.get(1)), owner.token())
                .andExpect(jsonPath("$[0].quantity").value(2));
        api.call(get("/stock-balances?productId=" + ids.get(2)), owner.token())
                .andExpect(jsonPath("$", hasSize(0)));
        String documents = api.call(get("/stock-documents"), owner.token()).andReturn().getResponse()
                .getContentAsString();
        List<String> documentIds = JsonPath.read(documents, "$[*].id");
        assertThat(documentIds).hasSize(1);
        api.call(get("/stock-documents/" + documentIds.get(0)), owner.token())
                .andExpect(jsonPath("$.type").value("OPENING"))
                .andExpect(jsonPath("$.lines", hasSize(2)));

        // a size is a product like any other: it sells, and editing one leaves the others alone
        api.call(json(patch("/products/" + ids.get(1)), "{\"retailPrice\":175000}"), owner.token())
                .andExpect(jsonPath("$.retailPrice").value(175000))
                .andExpect(jsonPath("$.sizeLabel").value("9"));
        api.call(get("/products/" + ids.get(0)), owner.token())
                .andExpect(jsonPath("$.retailPrice").value(180000));
    }

    @Test
    void theSizesAreSavedAllOrNothing() throws Exception {
        Owner owner = api.signup();
        String letters = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"clothes_letter\"}"),
                owner.token()), "$.id");
        String request = """
                {"name":"Silk Blouse","unit":"PIECE","retailPrice":45000,"sizeChartId":"%s",
                 "locationId":%s,"unitCost":30000,"sizes":%s}""";

        api.call(json(post("/products/sizes"), request.formatted(letters, "\"" + owner.mainLocationId() + "\"",
                "[{\"label\":\"M\",\"quantity\":3},{\"label\":\"XXXXL\",\"quantity\":1}]")), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_not_in_chart"));
        api.call(json(post("/products/sizes"), request.formatted(letters, "\"" + owner.mainLocationId() + "\"",
                "[{\"label\":\"M\"},{\"label\":\"m\"}]")), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("duplicate_size"));
        api.call(json(post("/products/sizes"), request.formatted(letters, "null",
                "[{\"label\":\"M\",\"quantity\":3}]")), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("location_required"));
        api.call(json(post("/products/sizes"), request.formatted(letters, "null", "[]")), owner.token())
                .andExpect(status().isBadRequest());
        api.call(get("/products"), owner.token()).andExpect(jsonPath("$", hasSize(0)));

        // no stock yet is fine: the sizes exist and wait for a stock-in; a size typed in any case matches
        api.call(json(post("/products/sizes"), request.formatted(letters, "null",
                "[{\"label\":\"s\"},{\"label\":\"M\",\"quantity\":0}]")), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[*].name").value(contains("Silk Blouse · S",
                        "Silk Blouse · M")));

        // a removed chart makes no more products
        api.call(delete("/size-charts/" + letters), owner.token()).andExpect(status().isNoContent());
        api.call(json(post("/products/sizes"), request.formatted(letters, "null", "[{\"label\":\"L\"}]")),
                owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_chart_not_found"));
    }

    @Test
    void chartsBelongToTheirShopAndOnlyManagersChangeThem() throws Exception {
        Owner owner = api.signup();
        String eu = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes_eu\"}"), owner.token()), "$.id");

        Owner other = api.signup();
        api.call(get("/size-charts"), other.token()).andExpect(jsonPath("$", hasSize(0)));
        api.call(json(post("/products/sizes"), """
                {"name":"Borrowed","unit":"PIECE","retailPrice":1000,"sizeChartId":"%s","sizes":[{"label":"40"}]}"""
                .formatted(eu)), other.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("size_chart_not_found"));
        api.call(json(patch("/size-charts/" + eu), "{\"name\":\"Mine now\"}"), other.token())
                .andExpect(status().isNotFound());

        String cashier = api.member(owner, "CASHIER");
        api.call(get("/size-charts"), cashier).andExpect(jsonPath("$", hasSize(1)));
        api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes_uk\"}"), cashier)
                .andExpect(status().isForbidden());
        api.call(json(post("/products/sizes"), """
                {"name":"Cashier shoe","unit":"PIECE","retailPrice":1000,"sizeChartId":"%s","sizes":[{"label":"40"}]}"""
                .formatted(eu)), cashier)
                .andExpect(status().isForbidden());
        String manager = api.member(owner, "STOCK_MANAGER");
        api.call(json(patch("/size-charts/" + eu), "{\"name\":\"EU shoes\"}"), manager)
                .andExpect(status().isOk());
    }
}
