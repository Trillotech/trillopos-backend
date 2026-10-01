# Automatic product discounts

Owners and stock managers configure a product's discount when creating or editing it. The product page also provides Turn off, Turn on and Remove discount. Cashiers can see the setting and receive the discount automatically but cannot change it.

`ProductWrite` and `SizesWrite` accept `discount`:

```json
{"discount":{"type":"PERCENT","value":10,"enabled":true,"includeWholesale":false}}
```

Use `FIXED` for an amount per unit. Values are positive, have at most four decimal places, and percentages cannot exceed 100. Omitted flags default to enabled and retail-only. Omit `discount` on PATCH to leave it unchanged; send `{"discount":{"type":null}}` to remove it. Creation in several sizes applies the setting to every selected size. Each saved size remains independently editable.

The backend multiplies the discount by quantity and rounds once per line to the currency's minor unit. A fixed discount cannot exceed the current selling price. Wholesale uses a product discount only when explicitly enabled for wholesale, including when its price falls back to retail. Whole-cart discounts apply after product discounts, followed by existing tax and total-rounding rules. Money remains BigDecimal and NUMERIC(19,4).

`POST /sales/preview` takes the cart request and returns priced lines, totals and a `pricingFingerprint`. It creates no sale, receipt, payment or stock movement. The web app sends that fingerprint with checkout or hold. A changed price returns `409 pricing_changed`; the app refreshes its preview and requires another confirmation. Checkout still replays the original sale for an already successful idempotency key. Older API clients may omit the fingerprint. Legacy manual line discounts remain accepted for products without an applicable automatic discount; combining the two is rejected instead of stacking them.

Held and draft carts preserve their saved unit prices, discounts, tax and totals when completed. Replacing a cart explicitly reprices it. Completion still validates product availability and stock, captures current unit cost through the stock ledger, and records payments transactionally. Completed receipts and refunds use their original snapshots. A zero-total sale posts stock without a payment or receivable. Zero-value returns can restore free goods without inventing a money movement.

Migration V14 adds product discount fields and permits zero-value return documents. No existing migration is changed. Tests cover quantities, rounding, inclusive and exclusive tax, wholesale opt-in, pause/remove, tenant isolation, staff permissions, stale previews, idempotent retries, held carts and refunds.
