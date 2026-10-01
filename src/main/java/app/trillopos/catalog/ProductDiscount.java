package app.trillopos.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;

import app.trillopos.shared.web.ApiException;

/** A reusable per-unit discount. A null type explicitly removes the saved discount. */
public record ProductDiscount(Type type, BigDecimal value, Boolean enabled, Boolean includeWholesale) {
    public enum Type { PERCENT, FIXED }

    public ProductDiscount {
        if (type == null) {
            if (value != null || Boolean.TRUE.equals(enabled) || Boolean.TRUE.equals(includeWholesale)) {
                throw ApiException.badRequest("invalid_product_discount", "a removed discount has no value or enabled flags");
            }
            enabled = false;
            includeWholesale = false;
        } else {
            if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 4
                    || value.precision() - value.scale() > 15
                    || (type == Type.PERCENT && value.compareTo(new BigDecimal("100")) > 0)) {
                throw ApiException.badRequest("invalid_product_discount", "use a positive amount with at most four decimals, or a percentage up to 100");
            }
            enabled = enabled == null || enabled;
            includeWholesale = Boolean.TRUE.equals(includeWholesale);
        }
    }

    public boolean applies(boolean wholesale) {
        return type != null && Boolean.TRUE.equals(enabled) && (!wholesale || Boolean.TRUE.equals(includeWholesale));
    }

    /** Round once per line, like the sale calculator; never reduce a line below zero. */
    public BigDecimal amount(BigDecimal price, BigDecimal quantity, int minor) {
        BigDecimal perUnit = type == Type.PERCENT ? price.multiply(value).movePointLeft(2) : value.min(price);
        return perUnit.multiply(quantity).setScale(minor, RoundingMode.HALF_UP)
                .min(price.multiply(quantity).setScale(minor, RoundingMode.HALF_UP));
    }
}
