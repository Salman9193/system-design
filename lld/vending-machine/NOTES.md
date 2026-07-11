# Vending Machine — Notes & Trade-offs

> **Design-patterns reference:** for the intent, structure, and trade-offs of each pattern
> used here — State, Strategy, Singleton, Factory, Observer — see
> [LLD Fundamentals → Design Patterns](#lf-design-patterns).

## Why State beats status flags

The naive version threads a `status` enum through every method:
`if (status == HAS_MONEY) … else if (status == IDLE) …`. Every new state multiplies those
branches across every method. The **State pattern** localizes each state's rules in one
class, so adding (say) a `MaintenanceState` touches one file and can't corrupt the others.
The trade-off is more classes and a little indirection — worth it the moment there are more
than two or three states with distinct behaviour.

## The Strategy seam

`ChangeStrategy` isolates the one part most likely to change: *how* change is computed.
Today it's bounded-DP; a canonical-coin deployment could drop in a greedy strategy, and a
cashless machine could return an empty map and settle electronically — all without touching
the state machine. Strategy is the extension point for **payment method** too.

## The DSA bridge — coin change

The change-dispenser *is* the **coin change** problem, and this LLD reuses the exact ideas
from the DSA problem of the same name:

- **Greedy fails** for arbitrary denominations or when a coin runs out → use DP.
- **Bounded / limited supply**: each coin is finite, so it's the bounded variant (the
  "Coin Change with limited supply" extension of the DSA problem).
- **Reconstruction**: the machine must dispense *which* coins, not just *how many* — the
  same `choice[]` / `take[][]` backtracking added to the DSA `coin-change` solution.
- **Impossible change**: when the DP target is unreachable, the sale is aborted and the
  balance refunded — the real-world consequence of `coinChange` returning `-1`.

> Revision hook: read the
> [Coin Change problem (dsa-problems)](https://salman9193.github.io/dsa-problems/) — Dynamic
> Programming → Coin Change — alongside `BoundedDPChangeStrategy` here. Same recurrence: one
> returns a count, the other returns the coins to physically drop. Source:
> [github.com/Salman9193/dsa-problems](https://github.com/Salman9193/dsa-problems/tree/main/dynamic-programming/coin-change).

## Thread-safety

A real machine has concurrent inputs (coin sensor, keypad, dispenser). The singleton uses
the **holder idiom** (lazy, no locking). For live operation, guard the mutating API
(`insertCoin / selectProduct / cancel`) with a lock or make the machine an actor that
serializes events — the State pattern makes this easy because transitions are already
funneled through `setState`.

## Observer

`MachineObserver` decouples "something ran low / change failed" from *who cares*
(maintenance dashboard, telemetry, restocking service). New reactions are new observers, no
core changes.

## Edge cases handled

| Case | Behaviour |
|------|-----------|
| exact money, no change due | dispense, empty change map |
| change owed but not makeable | abort + refund, notify `onChangeUnavailable` |
| product out of stock | reject at selection, stay in `HasMoney` |
| whole machine empty | transition to `SoldOut` |
| cancel mid-transaction | refund from the coin float, back to `Idle` |

## Testing checklist

- State transitions: every (state × action) cell of the table.
- Change: exact, no-change, impossible-change, and greedy-would-fail denominations
  (e.g. coins `{1,3,4}`, change `6` → DP gives `3+3`, greedy gives `4+1+1`).
- Bounded correctness: a denomination running out mid-run forces an alternative.
- Concurrency: interleaved inserts and selects under a lock.

## Extension points

- **Cashless payment** → a new `ChangeStrategy` / payment abstraction.
- **State creation** already funnels through `StateFactory`, so new states are one-liners.
- **Maintenance mode**, **multi-currency**, **product recommendations** via more observers
  or strategies — none require touching the state machine core.
