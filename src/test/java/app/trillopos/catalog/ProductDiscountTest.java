package app.trillopos.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductDiscountTest {
    @Test void fractionalQuantitiesRoundOnlyOnceAtLineLevel() {
        var percent = new ProductDiscount(ProductDiscount.Type.PERCENT, new BigDecimal("12.5"), true, false);
        assertThat(percent.amount(new BigDecimal("19.99"), new BigDecimal("2.5"), 2)).isEqualByComparingTo("6.25");
        var fixed = new ProductDiscount(ProductDiscount.Type.FIXED, new BigDecimal("0.015"), true, true);
        assertThat(fixed.amount(new BigDecimal("1"), new BigDecimal("3"), 2)).isEqualByComparingTo("0.05");
    }

    @Test void discountCannotExceedTheLineEvenAfterAPriceReduction() {
        var discount = new ProductDiscount(ProductDiscount.Type.FIXED, new BigDecimal("100"), true, false);
        assertThat(discount.amount(new BigDecimal("1.2345"), new BigDecimal("2.5"), 2)).isEqualByComparingTo("3.09");
    }
}
