import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// =============================================================================
// Vending Machine — LLD
// Patterns: State, Strategy, Singleton, Factory, Observer.
// Change-making: bounded-DP coin change with reconstruction (finite coin supply).
// =============================================================================

// ---------- Product ----------
class Product {
    final String code;
    final String name;
    final int price;                       // in smallest currency unit (e.g. cents)
    Product(String code, String name, int price) {
        this.code = code; this.name = name; this.price = price;
    }
}

// ---------- Observer pattern ----------
interface MachineObserver {
    void onLowStock(String productCode, int remaining);
    void onLowCoins(int denomination, int remaining);
    void onChangeUnavailable(int amount);
}

// A simple observer that logs maintenance-worthy events.
class ConsoleAlerter implements MachineObserver {
    public void onLowStock(String code, int remaining) {
        System.out.println("[ALERT] product " + code + " low: " + remaining + " left");
    }
    public void onLowCoins(int denom, int remaining) {
        System.out.println("[ALERT] coin " + denom + " low: " + remaining + " left");
    }
    public void onChangeUnavailable(int amount) {
        System.out.println("[ALERT] cannot make change for " + amount + " — sale aborted");
    }
}

// ---------- Strategy pattern: change-making ----------
interface ChangeStrategy {
    // Returns the coins to dispense as {denomination -> count}, or null if exact
    // change cannot be made from the given (finite) coin inventory.
    Map<Integer, Integer> makeChange(int amount, Map<Integer, Integer> coinInventory);
}

// Bounded (limited-supply) coin change via DP, minimizing coin count, with
// reconstruction of the actual coins. This is the coin-change problem: greedy can
// fail for arbitrary denominations or when a coin runs out, so we use DP.
class BoundedDPChangeStrategy implements ChangeStrategy {
    private static final int INF = Integer.MAX_VALUE / 2;

    public Map<Integer, Integer> makeChange(int amount, Map<Integer, Integer> inv) {
        if (amount == 0) return new LinkedHashMap<>();

        List<Integer> denoms = new ArrayList<>(inv.keySet());
        denoms.sort(null);
        int n = denoms.size();

        // dp[i][a] = min coins to form amount a using the first i denominations,
        // using at most inv[denom_{i-1}] copies of that denomination.
        int[][] dp = new int[n + 1][amount + 1];
        int[][] take = new int[n + 1][amount + 1];   // copies of denom i used to reach dp[i][a]
        for (int[] row : dp) Arrays.fill(row, INF);
        for (int i = 0; i <= n; i++) dp[i][0] = 0;

        for (int i = 1; i <= n; i++) {
            int coin = denoms.get(i - 1);
            int cnt = inv.get(coin);
            for (int a = 0; a <= amount; a++) {
                dp[i][a] = dp[i - 1][a];             // use zero of this coin
                take[i][a] = 0;
                for (int k = 1; k <= cnt && k * coin <= a; k++) {
                    int prev = dp[i - 1][a - k * coin];
                    if (prev != INF && prev + k < dp[i][a]) {
                        dp[i][a] = prev + k;
                        take[i][a] = k;
                    }
                }
            }
        }

        if (dp[n][amount] >= INF) return null;       // exact change impossible

        // Reconstruct which coins were used by backtracking the take[][] table.
        Map<Integer, Integer> result = new LinkedHashMap<>();
        int a = amount;
        for (int i = n; i >= 1; i--) {
            int k = take[i][a];
            if (k > 0) {
                result.put(denoms.get(i - 1), k);
                a -= k * denoms.get(i - 1);
            }
        }
        return result;
    }
}

// ---------- State pattern ----------
interface VendingMachineState {
    void insertCoin(int coin);
    void selectProduct(String code);
    void cancel();
}

class IdleState implements VendingMachineState {
    private final VendingMachine m;
    IdleState(VendingMachine m) { this.m = m; }
    public void insertCoin(int coin) {
        m.addBalance(coin);
        m.setState(StateFactory.hasMoney(m));
    }
    public void selectProduct(String code) {
        System.out.println("Insert money before selecting a product.");
    }
    public void cancel() { /* nothing to refund */ }
}

class HasMoneyState implements VendingMachineState {
    private final VendingMachine m;
    HasMoneyState(VendingMachine m) { this.m = m; }
    public void insertCoin(int coin) { m.addBalance(coin); }
    public void selectProduct(String code) {
        if (!m.isAvailable(code)) {
            System.out.println("Product " + code + " is unavailable.");
            return;
        }
        if (m.getBalance() < m.priceOf(code)) {
            System.out.println("Insufficient balance for " + code + ".");
            return;
        }
        m.setSelected(code);
        m.setState(StateFactory.dispensing(m));
        m.dispense();                         // trigger the vend in the new state
    }
    public void cancel() {
        m.refund();
        m.setState(StateFactory.idle(m));
    }
}

class DispensingState implements VendingMachineState {
    private final VendingMachine m;
    DispensingState(VendingMachine m) { this.m = m; }
    public void insertCoin(int coin)      { System.out.println("Please wait — dispensing."); }
    public void selectProduct(String code){ System.out.println("Please wait — dispensing."); }
    public void cancel()                  { System.out.println("Cannot cancel — dispensing."); }
}

class SoldOutState implements VendingMachineState {
    private final VendingMachine m;
    SoldOutState(VendingMachine m) { this.m = m; }
    public void insertCoin(int coin) { System.out.println("Machine sold out — coin returned."); }
    public void selectProduct(String code) { System.out.println("Machine sold out."); }
    public void cancel() { m.refund(); }
}

// ---------- Factory pattern: state creation ----------
class StateFactory {
    static VendingMachineState idle(VendingMachine m)       { return new IdleState(m); }
    static VendingMachineState hasMoney(VendingMachine m)   { return new HasMoneyState(m); }
    static VendingMachineState dispensing(VendingMachine m) { return new DispensingState(m); }
    static VendingMachineState soldOut(VendingMachine m)    { return new SoldOutState(m); }
}

// ---------- Singleton: the machine ----------
class VendingMachine {
    // Bill Pugh holder idiom — lazy and thread-safe without explicit locking.
    private static class Holder { static final VendingMachine INSTANCE = new VendingMachine(); }
    public static VendingMachine getInstance() { return Holder.INSTANCE; }

    private final Map<String, Product> catalog = new LinkedHashMap<>();
    private final Map<String, Integer> productStock = new LinkedHashMap<>();
    private final Map<Integer, Integer> coinInventory = new TreeMap<>();
    private final List<MachineObserver> observers = new ArrayList<>();

    private ChangeStrategy changeStrategy = new BoundedDPChangeStrategy();
    private VendingMachineState state;
    private int balance = 0;
    private String selected = null;

    private static final int LOW_STOCK = 2;
    private static final int LOW_COINS = 5;

    private VendingMachine() {
        // seed a demo catalog, stock, and coin float
        addProduct(new Product("A1", "Water", 35), 5);
        addProduct(new Product("A2", "Soda", 55), 3);
        for (int d : new int[]{1, 5, 10, 25}) coinInventory.put(d, 10);
        this.state = StateFactory.idle(this);
    }

    private void addProduct(Product p, int qty) {
        catalog.put(p.code, p);
        productStock.put(p.code, qty);
    }

    // ----- configuration -----
    public void setChangeStrategy(ChangeStrategy s) { this.changeStrategy = s; }
    public void addObserver(MachineObserver o) { observers.add(o); }

    // ----- public API (delegated to the current state) -----
    public void insertCoin(int coin) {
        if (!coinInventory.containsKey(coin)) { System.out.println("Unknown coin " + coin); return; }
        state.insertCoin(coin);
    }
    public void selectProduct(String code) { state.selectProduct(code); }
    public void cancel() { state.cancel(); }

    // ----- helpers used by states -----
    void setState(VendingMachineState s) { this.state = s; }
    int getBalance() { return balance; }
    void setSelected(String code) { this.selected = code; }
    boolean isAvailable(String code) {
        return catalog.containsKey(code) && productStock.getOrDefault(code, 0) > 0;
    }
    int priceOf(String code) { return catalog.get(code).price; }

    void addBalance(int coin) {
        balance += coin;
        coinInventory.merge(coin, 1, Integer::sum);   // inserted coin enters the float
    }

    void refund() {
        if (balance == 0) return;
        Map<Integer, Integer> coins = changeStrategy.makeChange(balance, coinInventory);
        // the just-inserted coins are in the float, so the balance is always returnable
        removeCoins(coins);
        System.out.println("Refunded " + balance + " → " + coins);
        balance = 0;
        selected = null;
    }

    // Called in DispensingState: perform the vend + change, or abort and refund.
    void dispense() {
        int price = priceOf(selected);
        int change = balance - price;
        Map<Integer, Integer> coins = change == 0
                ? new LinkedHashMap<>()
                : changeStrategy.makeChange(change, coinInventory);

        if (coins == null) {                          // cannot make exact change
            for (MachineObserver o : observers) o.onChangeUnavailable(change);
            refund();
            setState(StateFactory.idle(this));
            return;
        }

        // commit: remove change coins, decrement stock, release product
        removeCoins(coins);
        productStock.merge(selected, -1, Integer::sum);
        System.out.println("Dispensed " + catalog.get(selected).name +
                (coins.isEmpty() ? " (no change)" : " + change " + coins));

        notifyLowStock(selected);
        notifyLowCoins();

        balance = 0;
        selected = null;
        setState(anyStockLeft() ? StateFactory.idle(this) : StateFactory.soldOut(this));
    }

    private void removeCoins(Map<Integer, Integer> coins) {
        if (coins == null) return;
        for (Map.Entry<Integer, Integer> e : coins.entrySet()) {
            coinInventory.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
    }

    private boolean anyStockLeft() {
        for (int q : productStock.values()) if (q > 0) return true;
        return false;
    }

    private void notifyLowStock(String code) {
        int left = productStock.getOrDefault(code, 0);
        if (left <= LOW_STOCK) for (MachineObserver o : observers) o.onLowStock(code, left);
    }
    private void notifyLowCoins() {
        for (Map.Entry<Integer, Integer> e : coinInventory.entrySet())
            if (e.getValue() <= LOW_COINS)
                for (MachineObserver o : observers) o.onLowCoins(e.getKey(), e.getValue());
    }
}

// ---------- Demo ----------
class Solution {
    public static void main(String[] args) {
        VendingMachine vm = VendingMachine.getInstance();
        vm.addObserver(new ConsoleAlerter());

        // Buy Water (35) with two 25s → change 15 = 10 + 5
        vm.insertCoin(25);
        vm.insertCoin(25);
        vm.selectProduct("A1");

        // Cancel flow: insert then refund
        vm.insertCoin(10);
        vm.cancel();
    }
}
