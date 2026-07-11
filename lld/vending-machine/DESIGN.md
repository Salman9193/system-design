# Vending Machine — Design

The design is a **finite state machine** wrapped in five classic patterns. Each pattern
earns its place by removing a specific kind of change-driven complexity.

---

## The State Machine

The machine's behaviour depends entirely on *what it's currently doing*. Modeling that as
explicit states removes the giant `if (status == …)` blocks.

```
              insert coin
             ┌───────────┐
             ▼           │
        ┌────────┐  insert coin   ┌───────────┐  select (valid)   ┌──────────────┐
  ─────▶│  Idle  │───────────────▶│  HasMoney │──────────────────▶│  Dispensing  │
        └────────┘                └───────────┘                   └──────────────┘
             ▲                       │      │                            │
             │  (all stock empty)    │      │ select (invalid: no stock, │ vend product
             ▼                       │      │ low balance) → stay        │ + change
        ┌──────────┐                 │      │                            │
        │ SoldOut  │◀────────────────┘      │                            │
        └──────────┘   cancel / refund ◀────┴──────── back to Idle ◀─────┘
```

### Transition table

| State | insertCoin | selectProduct | cancel |
|-------|-----------|---------------|--------|
| **Idle** | add balance → HasMoney | reject ("insert money") | no-op |
| **HasMoney** | add balance | validate → Dispensing, else stay | refund → Idle |
| **Dispensing** | reject (busy) | reject (busy) | reject |
| **SoldOut** | reject / return coin | reject | refund |

The `Dispensing` state performs the vend + change, then resets to `Idle` (or `SoldOut` if
the machine is now empty).

---

## Design Patterns

| Pattern | Applied to | Why |
|---------|-----------|-----|
| **State** | `Idle / HasMoney / Dispensing / SoldOut` | Behaviour varies by state; each state owns its transitions — no status flags |
| **Strategy** | `ChangeStrategy` | The change-making algorithm is pluggable (here: bounded-DP; could be greedy or cashless) |
| **Singleton** | `VendingMachine` | One physical machine ⇒ one instance; lazy, thread-safe via the holder idiom |
| **Factory** | `StateFactory` | Centralizes state creation and wiring to the machine, so states are made one way |
| **Observer** | `MachineObserver` | Low-stock / low-coin / change-unavailable events notify maintenance without coupling |

---

## Class Model

```
VendingMachine (Singleton)
├── state: VendingMachineState            ← State
│     ├── IdleState
│     ├── HasMoneyState
│     ├── DispensingState
│     └── SoldOutState
├── changeStrategy: ChangeStrategy        ← Strategy
│     └── BoundedDPChangeStrategy
├── observers: List<MachineObserver>      ← Observer
│     └── ConsoleAlerter
├── catalog / productStock / coinInventory
└── uses StateFactory                     ← Factory
```

States hold a reference back to the machine and call `machine.setState(...)`, mutate
balance, and trigger dispense — keeping each transition rule in exactly one place.

---

## Change-Making: the Coin Change Subproblem

When the machine owes `change = balance − price`, it must return **actual coins from a
finite inventory**. That is the **coin change problem**, and two facts make it non-trivial:

1. **Greedy can fail.** Always taking the largest coin works for canonical currencies but
   breaks for arbitrary denominations, and *especially* when a denomination has run out.
   (Same reason greedy fails in the DSA `coin-change` problem.)
2. **Supply is bounded.** Each coin exists in finite quantity, so this is the
   **limited-supply / bounded** variant of coin change.

So `BoundedDPChangeStrategy` runs a **bounded dynamic program** — minimum coins to form
`change` without exceeding any denomination's count — and then **reconstructs the actual
coins** to drop:

```
dp[i][a] = min coins to form amount a using the first i denominations,
           taking at most inventory[coin_i] copies of coin_i.

take[i][a] = how many of coin_i that optimum used   →  reconstruct by backtracking.
```

If `dp[·][change]` is unreachable, **no exact change is possible** → the sale is aborted
and the balance refunded. This is the vending-machine incarnation of the reconstruction
step (`choice[]` backtracking) in the DSA `coin-change` problem — the machine needs *which*
coins, not just *how many*.

---

## A Purchase, End to End

```
insertCoin(25), insertCoin(25)   Idle → HasMoney, balance = 50
selectProduct("A1")  (price 35)  HasMoney validates: in stock, 50 ≥ 35, change = 15
                                 → Dispensing
   changeStrategy.makeChange(15) → {10:1, 5:1}   (bounded DP, 2 coins)
   release product, remove {10,5} from coin inventory, notify observers
                                 → Idle,  balance = 0
```
