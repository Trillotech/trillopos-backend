package app.trillopos.catalog;

import static app.trillopos.support.Api.json;
import static app.trillopos.support.Api.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Ready-made categories (2026-09-29): Footwear, clothing, Bags, Drinks… are offered to every shop
 * and become its own the first time it uses one. Those whose goods come in sizes bring their size
 * table: Footwear brings the shoe table and takes only footwear tables.
 */
class ReadyMadeCategoryTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    Api api;

    @BeforeEach
    void client() {
        api = new Api(mvc);
    }

    @Test
    void theLibrarySaysWhichSizesFitEachCategory() throws Exception {
        Owner owner = api.signup();
        String body = api.call(get("/categories/library"), owner.token()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> keys = JsonPath.read(body, "$[*].key");
        assertThat(keys).contains("footwear", "womens_clothing", "mens_clothing", "kids_clothing", "bags", "food",
                "drinks");
        List<String> footwear = JsonPath.read(body, "$[?(@.key == 'footwear')].sizeKind");
        assertThat(footwear).containsExactly("FOOTWEAR");
        List<String> shoes = JsonPath.read(body, "$[?(@.key == 'footwear')].sizeTemplateKey");
        assertThat(shoes).containsExactly("shoes");
        // footwear offers footwear tables only, clothing its own tables
        List<List<String>> footwearTables = JsonPath.read(body, "$[?(@.key == 'footwear')].sizeTemplateKeys");
        assertThat(footwearTables.get(0)).containsExactly("shoes", "sneakers", "kids_shoes");
        List<List<String>> womensTables = JsonPath.read(body, "$[?(@.key == 'womens_clothing')].sizeTemplateKeys");
        assertThat(womensTables.get(0)).containsExactly("womens_clothes", "trousers", "bras");
        List<String> drinks = JsonPath.read(body, "$[?(@.key == 'drinks')].sizeTemplateKey");
        assertThat(drinks).allMatch(java.util.Objects::isNull); // null, or left out

        // every size table a ready-made category offers is in the size table library, of its kind
        for (CategoryLibrary.Template template : CategoryLibrary.all()) {
            for (String key : template.sizeTemplateKeys()) {
                assertThat(SizeChartLibrary.find(key)).as(template.key() + " " + key)
                        .hasValueSatisfying(table -> assertThat(table.kind()).isEqualTo(template.sizeKind()));
            }
        }
    }

    @Test
    void footwearBecomesTheShopsOwnOnceWithItsShoeTable() throws Exception {
        Owner owner = api.signup();
        String footwear = api.call(json(post("/categories"), """
                {"templateKey":"footwear","name":"ဖိနပ်","sizeChartName":"ဖိနပ် · EU ဆိုဒ်"}"""), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ဖိနပ်"))
                .andExpect(jsonPath("$.templateKey").value("footwear"))
                .andExpect(jsonPath("$.parentId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String footwearId = JsonPath.read(footwear, "$.id");
        String shoeTable = JsonPath.read(footwear, "$.sizeChartId");
        api.call(get("/size-charts"), owner.token())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(shoeTable))
                .andExpect(jsonPath("$[0].templateKey").value("shoes"))
                .andExpect(jsonPath("$[0].name").value("ဖိနပ် · EU ဆိုဒ်"))
                .andExpect(jsonPath("$[0].systems[0]").value("EU"));

        // taken again, from anywhere, it is the same category and the same table
        api.call(json(post("/categories"), "{\"templateKey\":\"footwear\"}"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(footwearId));
        api.call(json(post("/size-charts"), "{\"templateKey\":\"shoes\"}"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(shoeTable));

        // a sub-category of it, on the sneaker table
        String sneakers = read(api.call(json(post("/size-charts"), "{\"templateKey\":\"sneakers\"}"), owner.token()), "$.id");
        api.call(json(post("/categories"), """
                {"name":"Sneakers","parentId":"%s","sizeChartId":"%s"}""".formatted(footwearId, sneakers)), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(footwearId))
                .andExpect(jsonPath("$.sizeChartId").value(sneakers))
                .andExpect(jsonPath("$.templateKey").doesNotExist());

        // a category whose goods have no sizes brings no table
        api.call(json(post("/categories"), "{\"templateKey\":\"drinks\"}"), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Drinks"))
                .andExpect(jsonPath("$.sizeChartId").doesNotExist());
        api.call(get("/size-charts"), owner.token()).andExpect(jsonPath("$", hasSize(2)));

        api.call(json(post("/categories"), "{\"templateKey\":\"no_such_category\"}"), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("category_not_found"));
        api.call(json(post("/categories"), "{\"name\":\" \"}"), owner.token())
                .andExpect(status().isBadRequest());
    }

    @Test
    void eachShopMakesItsOwnAndOnlyManagersMakeThem() throws Exception {
        Owner owner = api.signup();
        String mine = read(api.call(json(post("/categories"), "{\"templateKey\":\"footwear\"}"), owner.token()), "$.id");

        Owner other = api.signup();
        String theirs = read(api.call(json(post("/categories"), "{\"templateKey\":\"footwear\"}"), other.token())
                .andExpect(status().isCreated()), "$.id");
        assertThat(theirs).isNotEqualTo(mine);

        String cashier = api.member(owner, "CASHIER");
        api.call(get("/categories/library"), cashier).andExpect(status().isOk());
        api.call(json(post("/categories"), "{\"templateKey\":\"drinks\"}"), cashier)
                .andExpect(status().isForbidden());
    }
}
