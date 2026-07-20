import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// =============================================================================
// Chinese Word Segmenter (jieba-style) — LLD
//
// Pipeline: regex pre-split -> DAG build -> route DP (max log-probability path)
//           -> walk route -> HMM/Viterbi on unknown runs -> merge
//
// Patterns: Strategy (SegmentMode, UnknownWordModel), Singleton (lazy holder),
//           Builder, Decorator (user dict overlay), Template Method, Flyweight.
// DSA:      prefix hash map (Trie alternative), DAG longest path, Viterbi DP.
// =============================================================================

// ---------- Token with offsets ----------
class Token {
    final String word;
    final int start;
    final int end;

    Token(String word, int start, int end) {
        this.word = word;
        this.start = start;
        this.end = end;
    }

    @Override
    public String toString() {
        return word + "(" + start + "," + end + ")";
    }
}

// ---------- The prefix dictionary (Flyweight: one instance, shared, immutable after load) ----------
//
// Every PREFIX of every word is a key:
//    freq > 0  -> a real word
//    freq == 0 -> prefix only, keep extending
//    absent    -> dead end, STOP extending   <-- the O(1) early termination
//
// This is the deliberate alternative to a Trie: flat, cache-friendly, trivially serializable,
// at the cost of storing each prefix as its own key.
class PrefixDictionary {
    private final Map<String, Integer> freq;
    private final Map<String, Integer> userOverlay = new ConcurrentHashMap<>(); // Decorator
    private double total;

    PrefixDictionary(Map<String, Integer> words) {
        this.freq = new HashMap<>(words.size() * 4);
        double sum = 0;
        for (Map.Entry<String, Integer> e : words.entrySet()) {
            String word = e.getKey();
            int f = e.getValue();
            freq.put(word, f);
            sum += f;
            for (int i = 1; i < word.length(); i++) {      // register every proper prefix
                freq.putIfAbsent(word.substring(0, i), 0);
            }
        }
        this.total = sum;
    }

    // absent => not even a prefix => the DAG walk stops
    boolean isCandidate(String frag) {
        return userOverlay.containsKey(frag) || freq.containsKey(frag);
    }

    // 0 => prefix only (not a word)
    int frequencyOf(String frag) {
        Integer u = userOverlay.get(frag);
        if (u != null) return u;
        Integer f = freq.get(frag);
        return f == null ? 0 : f;
    }

    double total() { return total; }

    // Decorator: user words layer over the base dictionary without mutating it.
    void addWord(String word, int f) {
        userOverlay.put(word, f);
        total += f;
        for (int i = 1; i < word.length(); i++) {
            String p = word.substring(0, i);
            if (!freq.containsKey(p)) userOverlay.putIfAbsent(p, 0);
        }
    }

    void delWord(String word) { userOverlay.put(word, 0); }

    // Force a specific segmentation by raising a word's frequency above the competing path.
    void suggestFreq(String word) {
        addWord(word, Math.max(frequencyOf(word) + 1, (int) (total / 2000)));
    }
}

// ---------- Strategy: unknown-word recovery ----------
interface UnknownWordModel {
    List<String> recover(String run);
}

class NoUnknownWordModel implements UnknownWordModel {
    public List<String> recover(String run) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < run.length(); i++) out.add(String.valueOf(run.charAt(i)));
        return out;
    }
}

// HMM over B/M/E/S positions-within-a-word, decoded by Viterbi.
//   B = begin, M = middle, E = end, S = single-character word
// Only legal transitions are allowed (B->M/E, M->M/E, E->B/S, S->B/S), which is what keeps the
// tag sequence well-formed.
class HmmUnknownWordModel implements UnknownWordModel {
    private static final double MIN_FLOAT = -3.14e100;
    private static final char[] STATES = {'B', 'M', 'E', 'S'};

    // legal predecessors of each state
    private static final Map<Character, char[]> PREV = Map.of(
            'B', new char[]{'E', 'S'},
            'M', new char[]{'M', 'B'},
            'S', new char[]{'S', 'E'},
            'E', new char[]{'B', 'M'});

    private final Map<Character, Double> startP;
    private final Map<Character, Map<Character, Double>> transP;
    private final Map<Character, Map<Character, Double>> emitP;

    HmmUnknownWordModel(Map<Character, Double> startP,
                        Map<Character, Map<Character, Double>> transP,
                        Map<Character, Map<Character, Double>> emitP) {
        this.startP = startP;
        this.transP = transP;
        this.emitP = emitP;
    }

    // Viterbi: V[t][state] = max over legal prev of ( V[t-1][prev] + trans + emit )
    // Time O(n * |S|^2) with |S| = 4  =>  linear.  Space O(n * |S|).
    private char[] viterbi(String obs) {
        int n = obs.length();
        Map<Character, Double> v = new HashMap<>();
        Map<Character, StringBuilder> path = new HashMap<>();

        for (char y : STATES) {                                   // t = 0
            v.put(y, startP.getOrDefault(y, MIN_FLOAT) + emit(y, obs.charAt(0)));
            path.put(y, new StringBuilder().append(y));
        }

        for (int t = 1; t < n; t++) {
            Map<Character, Double> nv = new HashMap<>();
            Map<Character, StringBuilder> npath = new HashMap<>();
            for (char y : STATES) {
                double em = emit(y, obs.charAt(t));
                double bestProb = MIN_FLOAT;
                char bestPrev = 'S';
                for (char y0 : PREV.get(y)) {                     // only LEGAL predecessors
                    double p = v.getOrDefault(y0, MIN_FLOAT)
                             + transP.getOrDefault(y0, Map.of()).getOrDefault(y, MIN_FLOAT)
                             + em;
                    if (p > bestProb) { bestProb = p; bestPrev = y0; }
                }
                nv.put(y, bestProb);
                npath.put(y, new StringBuilder(path.get(bestPrev)).append(y));
            }
            v = nv;
            path = npath;
        }

        // a valid segmentation must END on E (end of a word) or S (single-char word)
        char last = v.getOrDefault('E', MIN_FLOAT) >= v.getOrDefault('S', MIN_FLOAT) ? 'E' : 'S';
        return path.get(last).toString().toCharArray();
    }

    private double emit(char state, char ch) {
        return emitP.getOrDefault(state, Map.of()).getOrDefault(ch, MIN_FLOAT);
    }

    // decode the BMES tag sequence back into words
    public List<String> recover(String run) {
        List<String> out = new ArrayList<>();
        if (run.isEmpty()) return out;
        if (run.length() == 1) { out.add(run); return out; }

        char[] tags = viterbi(run);
        int begin = 0, next = 0;
        for (int i = 0; i < run.length(); i++) {
            char pos = tags[i];
            if (pos == 'B') {
                begin = i;
            } else if (pos == 'E') {
                out.add(run.substring(begin, i + 1));
                next = i + 1;
            } else if (pos == 'S') {
                out.add(String.valueOf(run.charAt(i)));
                next = i + 1;
            }
        }
        if (next < run.length()) out.add(run.substring(next));    // trailing B/M with no E
        return out;
    }
}

// ---------- Strategy: segmentation mode ----------
enum SegmentMode { DEFAULT, NO_HMM, FULL, SEARCH }

// ---------- The engine ----------
class Tokenizer {
    private static final Pattern HAN_BLOCK = Pattern.compile("([\\u4E00-\\u9FD5]+)");
    private static final Pattern SKIP = Pattern.compile("(\\r\\n|\\s)");

    private final PrefixDictionary dict;
    private final UnknownWordModel unknownModel;

    private Tokenizer(Builder b) {
        this.dict = b.dict;
        this.unknownModel = b.unknownModel;
    }

    // ---------- Singleton: lazy holder idiom (no synchronization on the hot path) ----------
    private static class Holder {
        static final Tokenizer INSTANCE = new Builder(DefaultDictionary.load()).build();
    }

    static Tokenizer getDefault() { return Holder.INSTANCE; }

    // ---------- ② DAG: position -> all end positions of dictionary words starting there ----------
    Map<Integer, List<Integer>> buildDAG(String s) {
        Map<Integer, List<Integer>> dag = new LinkedHashMap<>();
        int n = s.length();
        for (int k = 0; k < n; k++) {
            List<Integer> ends = new ArrayList<>();
            int i = k;
            String frag = s.substring(k, k + 1);
            while (i < n && dict.isCandidate(frag)) {        // absent => stop extending
                if (dict.frequencyOf(frag) > 0) ends.add(i); // a real word ends at i
                i++;
                if (i < n) frag = s.substring(k, i + 1);
            }
            if (ends.isEmpty()) ends.add(k);                 // single-char fallback keeps the DAG connected
            dag.put(k, ends);
        }
        return dag;
    }

    // ---------- ③ Route DP: maximum log-probability path (longest path in a weighted DAG) ----------
    //
    // Probabilities MULTIPLY and underflow, so we work in log space, turning the product into a
    // sum:  log P(w) = log freq(w) - log total.
    //
    // Character positions are ALREADY a topological order, so one backward sweep suffices.
    // route[i] = (bestScore, bestEnd)
    double[][] calcRoute(String s, Map<Integer, List<Integer>> dag) {
        int n = s.length();
        double[][] route = new double[n + 1][2];
        route[n][0] = 0.0;
        route[n][1] = 0.0;
        double logTotal = Math.log(dict.total());

        for (int i = n - 1; i >= 0; i--) {
            double best = Double.NEGATIVE_INFINITY;
            int bestEnd = i;
            for (int x : dag.get(i)) {
                int f = dict.frequencyOf(s.substring(i, x + 1));
                double score = Math.log(f > 0 ? f : 1) - logTotal + route[x + 1][0];
                if (score > best) { best = score; bestEnd = x; }
            }
            route[i][0] = best;
            route[i][1] = bestEnd;
        }
        return route;
    }

    // ---------- ④ + ⑤ walk the route, buffering unknown single-char runs for the HMM ----------
    private List<String> cutDAG(String s, boolean useHmm) {
        Map<Integer, List<Integer>> dag = buildDAG(s);
        double[][] route = calcRoute(s, dag);

        List<String> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int x = 0;
        int n = s.length();

        while (x < n) {
            int y = (int) route[x][1] + 1;
            String word = s.substring(x, y);
            if (y - x == 1 && dict.frequencyOf(word) == 0) {
                buffer.append(word);                     // unknown single char -> buffer the run
            } else {
                flush(buffer, out, useHmm);
                out.add(word);
            }
            x = y;
        }
        flush(buffer, out, useHmm);
        return out;
    }

    private void flush(StringBuilder buffer, List<String> out, boolean useHmm) {
        if (buffer.length() == 0) return;
        String run = buffer.toString();
        buffer.setLength(0);
        if (useHmm) out.addAll(unknownModel.recover(run));       // Viterbi re-segmentation
        else for (int i = 0; i < run.length(); i++) out.add(String.valueOf(run.charAt(i)));
    }

    // ---------- FULL mode: every word in the DAG, overlapping ----------
    private List<String> cutAll(String s) {
        Map<Integer, List<Integer>> dag = buildDAG(s);
        List<String> out = new ArrayList<>();
        int lastEnd = -1;
        for (int k = 0; k < s.length(); k++) {
            List<Integer> ends = dag.get(k);
            if (ends.size() == 1 && k > lastEnd) {
                out.add(s.substring(k, ends.get(0) + 1));
                lastEnd = ends.get(0);
            } else {
                for (int j : ends) {
                    if (j > k) { out.add(s.substring(k, j + 1)); lastEnd = j; }
                }
            }
        }
        return out;
    }

    // ---------- Template Method: fixed skeleton, mode-specific step ----------
    List<String> cut(String text, SegmentMode mode) {
        List<String> result = new ArrayList<>();
        Matcher m = HAN_BLOCK.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) addNonHan(text.substring(last, m.start()), result);
            result.addAll(cutBlock(m.group(), mode));    // ① Han block
            last = m.end();
        }
        if (last < text.length()) addNonHan(text.substring(last), result);
        return result;
    }

    private List<String> cutBlock(String block, SegmentMode mode) {
        switch (mode) {
            case FULL:   return cutAll(block);
            case NO_HMM: return cutDAG(block, false);
            case SEARCH: return finerForSearch(cutDAG(block, true), block);
            default:     return cutDAG(block, true);
        }
    }

    // SEARCH mode: default segmentation, plus 2- and 3-char sub-words of long tokens.
    private List<String> finerForSearch(List<String> words, String block) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            for (int size : new int[]{2, 3}) {
                if (w.length() > size) {
                    for (int i = 0; i + size <= w.length(); i++) {
                        String gram = w.substring(i, i + size);
                        if (dict.frequencyOf(gram) > 0) out.add(gram);
                    }
                }
            }
            out.add(w);
        }
        return out;
    }

    private void addNonHan(String chunk, List<String> result) {
        for (String piece : SKIP.split(chunk)) {
            if (!piece.isEmpty()) result.add(piece);
        }
    }

    // ---------- tokenize with offsets (for highlighting / NER) ----------
    List<Token> tokenize(String text, SegmentMode mode) {
        List<Token> tokens = new ArrayList<>();
        int start = 0;
        for (String w : cut(text, mode)) {
            int idx = text.indexOf(w, start);
            if (idx < 0) idx = start;
            tokens.add(new Token(w, idx, idx + w.length()));
            start = idx + w.length();
        }
        return tokens;
    }

    void addWord(String w, int f) { dict.addWord(w, f); }
    void delWord(String w)        { dict.delWord(w); }
    void suggestFreq(String w)    { dict.suggestFreq(w); }

    // ---------- Builder ----------
    static class Builder {
        private final PrefixDictionary dict;
        private UnknownWordModel unknownModel = new NoUnknownWordModel();

        Builder(PrefixDictionary dict) { this.dict = dict; }

        Builder withHmm(HmmUnknownWordModel m) { this.unknownModel = m; return this; }

        Builder withUserDict(Map<String, Integer> words) {
            words.forEach(dict::addWord);
            return this;
        }

        Tokenizer build() { return new Tokenizer(this); }
    }
}

// ---------- Stub for the packaged dictionary (real impl: load + mtime-checked cache file) ----------
class DefaultDictionary {
    static PrefixDictionary load() {
        Map<String, Integer> words = new HashMap<>();
        words.put("\u6211", 328);        // wo   (I)
        words.put("\u7231", 1000);       // ai   (love)
        words.put("\u5317\u4EAC", 2000); // Beijing
        words.put("\u5929\u5B89\u95E8", 1500); // Tiananmen
        return new PrefixDictionary(words);
    }
}

// ---------- Demo ----------
class Solution {
    public static void main(String[] args) {
        Tokenizer t = Tokenizer.getDefault();
        String s = "\u6211\u7231\u5317\u4EAC\u5929\u5B89\u95E8";   // 我爱北京天安门
        System.out.println("default: " + t.cut(s, SegmentMode.DEFAULT));
        System.out.println("full:    " + t.cut(s, SegmentMode.FULL));
        System.out.println("tokens:  " + t.tokenize(s, SegmentMode.DEFAULT));
    }
}
