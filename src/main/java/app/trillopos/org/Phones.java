package app.trillopos.org;

import java.util.regex.Pattern;

import app.trillopos.shared.web.ApiException;

/**
 * Phone numbers are stored in E.164 ({@code +959…}). People type them the local way, so a
 * leading {@code 00} becomes {@code +} and a single leading {@code 0} — {@code 09 7xx xxx xxx}
 * — becomes Myanmar's {@code +95}. Myanmar is the first market; a Thai shop's local numbers
 * would need the organization's country, which arrives with the first Thai pilot.
 */
public final class Phones {

    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{6,14}");

    /** Where a number written without a country code is assumed to be. */
    static final String DEFAULT_COUNTRY_CODE = "95";

    private Phones() {
    }

    /**
     * For a phone a form may leave empty (a supplier, a location, a staff member): empty means no
     * phone, instead of an invalid one. Sign-up and sign-in keep {@link #normalize}, which refuses it.
     */
    public static String normalizeOptional(String phone) {
        return phone == null || phone.isBlank() ? null : normalize(phone);
    }

    public static String normalize(String phone) {
        if (phone == null) {
            return null;
        }
        String compact = phone.replaceAll("[\\s()\\-.]", "");
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        } else if (compact.startsWith("0")) {
            compact = "+" + DEFAULT_COUNTRY_CODE + compact.substring(1);
        }
        if (!E164.matcher(compact).matches()) {
            throw ApiException.badRequest("invalid_phone", "phone must look like 09 7xx xxx xxx or +959…");
        }
        return compact;
    }
}
