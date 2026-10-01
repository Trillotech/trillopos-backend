# Sale progress and payment

Option 2 adds work progress to posted sales; checkout still posts stock and revenue immediately.
It does not reserve stock or introduce an order, shipment, courier or COD settlement lifecycle.

## Progress

- **Open**: work remains. New online sales start here.
- **Closed**: work is finished. New counter sales and existing posted sales start here.
- **Canceled**: the remaining sale has been reversed. Terminal; no reopening or further collection.

Closing and reopening change no stock or money. They require a reason and append a tenant-owned
`sale_progress_event` with the membership, timestamp and before/after states. A full return closes
an open sale automatically; it becomes Canceled only through the cancellation action.
Draft, held and void carts keep their existing lifecycle and have no payment state in the API.

## Payment: a ledger projection, never an editable label

`SalePaymentState` is shared by receipts and log filters. Money received is non-CREDIT checkout
payments plus non-CREDIT receivable settlements. Money refunded is return headers minus their
credit releases. CREDIT return settlements clear debt; they are not customer payments.

- **Paid**: net money received covers the remaining sale value; also a free sale with nothing owed.
- **Deposit**: some net money received, but the remaining value is not fully paid.
- **Unpaid**: no net money received. An unpaid cancellation stays Unpaid, with no debt remaining.
- **Refunded**: all goods returned and all collected money refunded, with an actual money refund.

Partial returns retain Paid/Deposit/Unpaid as appropriate. The receipt separately shows money
returned, credit released and debt written off. Write-offs never count as payment. Frozen
`sale.paidAmount`/`dueAmount` remain checkout history for existing API clients.

## API and permissions

- `GET /sales`: optional `progress` and `paymentStatus`, applied **before** the result limit.
- Sale detail and summary: `progress`, `paymentState` (received/refunded/net received,
  credit released, written off, outstanding and receivable ID).
- `POST /sales/{id}/progress`: Open/Closed plus reason. Owner, stock manager or cashier.
- `GET /sales/{id}/progress`: audit history, same roles.
- `POST /sales/{id}/payments`: collect against the existing receivable, with a required retry key.
  Same roles; the payment location must be the sale's location. Existing receivable repayments
  also update the sale's payment state.
- `POST /sales/{id}/cancel`: owner or stock manager; reason, explicit restock choice and retry key.
  Select a non-CREDIT refund method when money must go back. The web supplies the reviewed
  `expectedNetReceivedAmount`/`expectedOutstandingAmount`; stale amounts return 409
  `cancellation_changed`, with no postings. API clients should supply both as well.

All actions enforce tenant and session location scope. Packers cannot access these sales APIs.

## Atomic cancellation and returns

Lock the sale, then its receivable, before calculating remaining quantities and money. Returns
also acquire that receivable lock before reading outstanding debt, serializing repayment and
write-off with the cancellation. Restock only remaining quantities and only when explicitly
chosen, through the stock ledger at the original unit cost. Create one Return document to refund
net money and release outstanding credit, then mark Canceled in the same transaction. Retry after
cancellation posts nothing. Partially returned goods are never restocked/refunded again.

V15 adds `credit_refund_amount` and `rounding_refund_amount` to Return headers. Only the cash
portion (`refund_amount - credit_refund_amount`) leaves a drawer. The final return includes the
receipt's header rounding, so all returns add up to the original receipt total. Line revenue/tax
and cost reversals keep their existing snapshots. Ordinary cash/wallet returns cannot refund
more money than was received; unpaid value must be released as CREDIT instead.

Cancellation of written-off debt is deliberately refused: that needs a separate accounting
correction rather than silently treating a write-off as payment or creating an invented refund.
For existing fully returned sales with unreconciled credit or money, cancellation is also refused.

## Web flow

New sale offers Paid, Deposit and Unpaid. Deposit takes a positive amount below the server-priced
total and records the remainder as credit. Deposit/Unpaid require a customer and obey their credit
limit. Existing split tenders remain supported, with payment state derived from what was received.
Held carts remain unposted until charged.

The sales log shows separate Progress and Payment badges and filters. Receipts provide Close,
Reopen, Record payment and (authorized roles) Cancel sale. Cancellation shows the cash refund and
credit release separately, requires a goods choice and confirmation, and keeps the retry key when
a request fails. No production data is touched by the end-to-end tests.

## Verification

Backend API tests cover defaults, payment collection/replay, independent progress/audit, deposit
and unpaid cancellations, stock/drawer amounts, partial returns, no-restock, positive/negative
rounding, zero-price sales, write-offs, stale cancellation review, RBAC, tenants and filters before
limit. Browser tests cover creation, receipt actions, history, filters, cancellation confirmation,
stock and validation on iPhone Safari, Android and desktop Chrome.
