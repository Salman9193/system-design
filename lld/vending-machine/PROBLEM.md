# Vending Machine — Problem

Design the control software for a coin-operated vending machine. It's the canonical
low-level-design interview problem because it exercises a clean **finite state machine**
and several **design patterns** at once — and its change-dispensing is a real instance of
the **coin change** algorithm.

---

## Functional Requirements

1. **Accept coins** of fixed denominations (e.g. 1, 5, 10, 25) one at a time, accumulating
   a balance.
2. **Select a product** by code. The purchase proceeds only if the product exists, is in
   stock, and the balance covers its price.
3. **Dispense the product and the correct change.** Change must be made from the coins
   currently inside the machine.
4. **Refund / cancel** — the user can cancel before dispensing and get their balance back.
5. **Handle sold-out** — a product with zero stock cannot be selected; a machine with no
   stock at all rejects coins.
6. **Reject when change can't be made** — if exact change isn't available in the coin
   inventory, abort the sale and refund rather than short-change the customer.

---

## Non-Functional Requirements

- **Extensible** — new payment methods, new states, or new change algorithms should slot
  in without rewriting the core.
- **Maintainable** — no giant `if/else` on machine status; behavior localized per state.
- **Thread-safe-ready** — a real machine has concurrent inputs (coin sensor, keypad); the
  design should make synchronization straightforward.
- **Observable** — operations should be able to react to low stock / low coins.

---

## Public API

```
insertCoin(int denomination)      // add a coin to the current balance
selectProduct(String code)        // choose a product; triggers vend if valid
cancel()                          // refund the balance, return to idle
```

All three are delegated to the **current state**, which decides what happens and what the
next state is.

---

## Assumptions & Constraints

- Coin denominations are fixed and known; each is held in **finite quantity** (this is why
  change-making is *bounded*, not unbounded).
- Single-currency, integer amounts (work in the smallest unit, e.g. cents).
- One product dispensed per transaction.

## Out of Scope

- Cashless/card payment (noted as an extension point via the Strategy seam).
- Physical hardware drivers, networking/telemetry, multi-machine fleets.
