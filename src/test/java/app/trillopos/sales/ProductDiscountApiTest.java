package app.trillopos.sales;

import static app.trillopos.support.Api.json;
import static app.trillopos.support.Api.read;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import app.trillopos.support.Api;
import app.trillopos.support.IntegrationTest;

class ProductDiscountApiTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    Api api;
    Api.Owner owner;
    String product;

    @BeforeEach void setup() throws Exception {
        api = new Api(mvc);
        owner = api.signup();
        product = api.stockedProduct(owner, "Discount shoe", 20000, 20, 8000);
        update("\"sellOnline\":true,\"wholesalePrice\":15000");
    }

    ResultActions update(String fields) throws Exception {
        return api.call(json(patch("/products/" + product), "{" + fields + "}"), owner.token())
                .andExpect(status().isOk());
    }
    void discount(String type, String value, boolean enabled, boolean wholesale) throws Exception {
        update("\"discount\":{\"type\":\"%s\",\"value\":%s,\"enabled\":%s,\"includeWholesale\":%s}"
                .formatted(type, value, enabled, wholesale));
    }
    String cart(String extra) {
        return "\"locationId\":\"%s\",\"channel\":\"ONLINE\",\"lines\":[{\"productId\":\"%s\",\"quantity\":2}]%s"
                .formatted(owner.mainLocationId(), product, extra);
    }
    ResultActions preview(String extra) throws Exception {
        return api.call(json(post("/sales/preview"), "{" + cart(extra) + "}"), owner.token());
    }
    ResultActions checkout(String key, String extra) throws Exception {
        return api.call(json(post("/sales/checkout"), "{" + cart(extra)
                + ",\"idempotencyKey\":\"" + key + "\",\"payments\":[{\"method\":\"CASH\"}]}"), owner.token());
    }

    @Test void percentDiscountAndCartDiscountArePricedBeforeTaxAndPreviewWritesNothing() throws Exception {
        discount("PERCENT", "10", true, false);
        preview(",\"cartDiscountAmount\":1000").andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.subtotal").value(40000))
                .andExpect(jsonPath("$.totals.lineDiscountTotal").value(4000))
                .andExpect(jsonPath("$.totals.total").value(35000))
                .andExpect(jsonPath("$.totals.taxAmount").value(1666.67));
        api.call(get("/sales"), owner.token()).andExpect(jsonPath("$.length()").value(0));
        checkout("percent", ",\"cartDiscountAmount\":1000").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].discountAmount").value(4000))
                .andExpect(jsonPath("$.total").value(35000));
    }

    @Test void fixedDiscountMultipliesQuantityAndWholesaleIsOptIn() throws Exception {
        discount("FIXED", "1500", true, false);
        preview("").andExpect(jsonPath("$.totals.total").value(37000));
        preview(",\"priceType\":\"WHOLESALE\"").andExpect(jsonPath("$.totals.total").value(30000));
        discount("FIXED", "1500", true, true);
        preview(",\"priceType\":\"WHOLESALE\"").andExpect(jsonPath("$.totals.total").value(27000));
        discount("FIXED", "1500", false, true);
        preview("").andExpect(jsonPath("$.totals.total").value(40000));
        update("\"discount\":{\"type\":null}").andExpect(jsonPath("$.discount.enabled").value(false));
        preview("").andExpect(jsonPath("$.totals.lineDiscountTotal").value(0));
    }

    @Test void stalePreviewRequiresReviewButAnIdempotentRetryReturnsTheOriginal() throws Exception {
        discount("PERCENT", "10", true, false);
        String fingerprint = read(preview(""), "$.pricingFingerprint");
        String confirmed = ",\"pricingFingerprint\":\"" + fingerprint + "\"";
        checkout("replay", confirmed).andExpect(status().isCreated());
        discount("PERCENT", "20", true, false);
        checkout("new", confirmed).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("pricing_changed"));
        checkout("replay", confirmed).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(36000));
        api.call(get("/sales"), owner.token()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test void heldCartHonorsSavedPricesAndDiscountWithoutApplyingItTwice() throws Exception {
        discount("PERCENT", "10", true, false);
        String held = read(api.call(json(post("/sales"), "{" + cart(",\"hold\":true") + "}"), owner.token())
                .andExpect(status().isCreated()), "$.id");
        update("\"retailPrice\":30000,\"discount\":{\"type\":null}");
        api.call(json(post("/sales/" + held + "/complete"),
                "{\"idempotencyKey\":\"held\",\"payments\":[{\"method\":\"CASH\"}]}"), owner.token())
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(36000))
                .andExpect(jsonPath("$.lines[0].discountAmount").value(4000))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(20000))
                .andExpect(jsonPath("$.lines[0].unitCost").value(8000));
    }

    @Test void discountsCannotCrossTenantsOrBeEditedByCashiersAndBadValuesFail() throws Exception {
        String cashier = api.member(owner, "CASHIER");
        api.call(json(patch("/products/" + product), "{\"discount\":{\"type\":\"PERCENT\",\"value\":10}}"), cashier)
                .andExpect(status().isForbidden());
        Api.Owner other = api.signup();
        api.call(json(patch("/products/" + product), "{\"discount\":{\"type\":\"PERCENT\",\"value\":10}}"), other.token())
                .andExpect(status().isNotFound());
        for (String value : new String[] {"-1", "0", "101", "1.12345", "1000000000000000"}) {
            api.call(json(patch("/products/" + product), "{\"discount\":{\"type\":\"PERCENT\",\"value\":" + value + "}}"), owner.token())
                    .andExpect(status().isBadRequest());
        }
        discount("PERCENT", "10", true, false);
        String manual = cart("").replace("\"quantity\":2", "\"quantity\":2,\"discountAmount\":1");
        api.call(json(post("/sales/preview"), "{" + manual + "}"), owner.token()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("discount_conflict"));
    }

    @Test void fullDiscountCompletesWithoutRecordingMoney() throws Exception {
        discount("PERCENT", "100", true, false);
        checkout(UUID.randomUUID().toString(), "").andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.payments.length()").value(0));
    }
    @Test void refundUsesTheOriginalDiscountEvenAfterProductPricesChange() throws Exception {
        discount("PERCENT", "10", true, false);
        ResultActions sold = checkout("refundable", "").andExpect(status().isCreated());
        String saleId = read(sold, "$.id");
        String lineId = read(sold, "$.lines[0].id");
        update("\"retailPrice\":40000,\"discount\":{\"type\":null}");
        for (int i = 0; i < 2; i++) {
            api.call(json(post("/returns"), """
                    {"saleId":"%s","locationId":"%s","refundMethod":"CASH","idempotencyKey":"part-%s",
                    "lines":[{"saleLineId":"%s","quantity":1,"restock":true}]}"""
                    .formatted(saleId, owner.mainLocationId(), i, lineId)), owner.token())
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.refundAmount").value(18000));
        }
        api.call(get("/sales/" + saleId), owner.token()).andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.total").value(36000));
    }

    @Test void freeDiscountedItemsCanBeReturnedAndRestocked() throws Exception {
        discount("FIXED", "99999", true, false);
        ResultActions sold = checkout("free-return", "").andExpect(status().isCreated());
        api.call(json(post("/returns"), """
                {"saleId":"%s","locationId":"%s","refundMethod":"CASH","idempotencyKey":"free",
                "lines":[{"saleLineId":"%s","quantity":2,"restock":true}]}"""
                .formatted(read(sold, "$.id"), owner.mainLocationId(), read(sold, "$.lines[0].id"))), owner.token())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.refundAmount").value(0));
    }

    @Test void creationPersistsDiscountAndExclusiveTaxUsesTheDiscountedAmount() throws Exception {
        String created = read(api.call(json(post("/products"), """
                {"name":"Discount service","unit":"PIECE","retailPrice":100,"trackInventory":false,
                "sellOnline":true,"discount":{"type":"FIXED","value":12.5}}"""), owner.token())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.discount.enabled").value(true)), "$.id");
        product = created;
        api.call(json(patch("/organization"), "{\"taxInclusivePricing\":false,\"defaultTaxRate\":10}"), owner.token())
                .andExpect(status().isOk());
        preview("").andExpect(status().isOk()).andExpect(jsonPath("$.totals.total").value(192.5))
                .andExpect(jsonPath("$.totals.taxAmount").value(17.5));
    }

}
