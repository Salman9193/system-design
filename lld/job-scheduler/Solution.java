import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

// =============================================================================
// Job / Task Scheduler — LLD
// Patterns: Strategy (scheduling + retry policy), State (job lifecycle), Observer,
//           Builder, Factory, Template Method (recurring), Singleton, Command (Job).
// DSA:      priority heap (ready queue), topological sort (dependencies, Kahn),
//           cooldown greedy (LeetCode #621 Task Scheduler).
// =============================================================================

// ---------- Job lifecycle (State) ----------
enum JobState { PENDING, BLOCKED, READY, RUNNING, SUCCEEDED, FAILED, CANCELLED }

// ---------- Observer ----------
interface JobListener {
    void onStart(Job job);
    void onSuccess(Job job);
    void onFailure(Job job, Exception e, int attempt);
    void onRetry(Job job, int attempt, long backoffMs);
}

class ConsoleListener implements JobListener {
    public void onStart(Job j)   { System.out.println("[START]   " + j.id + " (" + j.type + ")"); }
    public void onSuccess(Job j) { System.out.println("[SUCCESS] " + j.id); }
    public void onFailure(Job j, Exception e, int attempt) {
        System.out.println("[FAILED]  " + j.id + " attempt " + attempt + ": " + e.getMessage());
    }
    public void onRetry(Job j, int attempt, long backoffMs) {
        System.out.println("[RETRY]   " + j.id + " attempt " + attempt + " in " + backoffMs + "ms");
    }
}

// ---------- Strategy: retry ----------
interface RetryPolicy {
    boolean shouldRetry(int attempt);
    long backoffMillis(int attempt);
}

class ExponentialBackoff implements RetryPolicy {
    private final int maxAttempts;
    private final long baseMs;
    ExponentialBackoff(int maxAttempts, long baseMs) {
        this.maxAttempts = maxAttempts;
        this.baseMs = baseMs;
    }
    public boolean shouldRetry(int attempt) { return attempt < maxAttempts; }
    public long backoffMillis(int attempt)  { return baseMs * (1L << (attempt - 1)); }  // 1x,2x,4x...
}

class NoRetry implements RetryPolicy {
    public boolean shouldRetry(int attempt) { return false; }
    public long backoffMillis(int attempt)  { return 0; }
}

// ---------- Strategy: scheduling policy (ordering of the ready queue) ----------
interface SchedulingPolicy {
    // negative => a before b
    int compare(Job a, Job b);
}

// Time first (a job isn't eligible early), then priority, then FIFO by sequence.
class PriorityPolicy implements SchedulingPolicy {
    public int compare(Job a, Job b) {
        int t = Long.compare(a.nextRunAt, b.nextRunAt);
        if (t != 0) return t;
        int p = Integer.compare(b.priority, a.priority);   // higher priority first
        if (p != 0) return p;
        return Long.compare(a.seq, b.seq);                 // FIFO tiebreak — no starvation among equals
    }
}

// Earliest-deadline-first: same shape, different key — that is the point of Strategy.
class EdfPolicy implements SchedulingPolicy {
    public int compare(Job a, Job b) {
        int t = Long.compare(a.nextRunAt, b.nextRunAt);
        if (t != 0) return t;
        int d = Long.compare(a.deadline, b.deadline);
        if (d != 0) return d;
        return Long.compare(a.seq, b.seq);
    }
}

// ---------- Command: the job itself, built via Builder ----------
class Job {
    final String id;
    final String type;                 // for per-type cooldown (rate limiting)
    final Runnable body;
    final int priority;                // higher = more important
    final long intervalMs;             // > 0 => recurring
    final long cooldownMs;             // per-type cooldown (LeetCode #621's n)
    final long deadline;               // for EDF
    final RetryPolicy retryPolicy;

    volatile JobState state = JobState.PENDING;
    volatile long nextRunAt;           // when this job becomes eligible
    volatile int attempt = 0;
    long seq;                          // submission order (FIFO tiebreak)

    private Job(Builder b) {
        this.id = b.id; this.type = b.type; this.body = b.body;
        this.priority = b.priority; this.intervalMs = b.intervalMs;
        this.cooldownMs = b.cooldownMs; this.deadline = b.deadline;
        this.retryPolicy = b.retryPolicy;
        this.nextRunAt = System.currentTimeMillis() + b.delayMs;
    }

    boolean isRecurring() { return intervalMs > 0; }

    // Template Method: the reschedule skeleton is fixed; subclasses/strategies vary the rule.
    long nextFireTime() { return System.currentTimeMillis() + intervalMs; }

    // ---------- Builder ----------
    static class Builder {
        private final String id;
        private final Runnable body;
        private String type = "default";
        private int priority = 0;
        private long delayMs = 0, intervalMs = 0, cooldownMs = 0;
        private long deadline = Long.MAX_VALUE;
        private RetryPolicy retryPolicy = new NoRetry();

        Builder(String id, Runnable body) { this.id = id; this.body = body; }
        Builder type(String t)        { this.type = t; return this; }
        Builder priority(int p)       { this.priority = p; return this; }
        Builder delayMs(long d)       { this.delayMs = d; return this; }
        Builder everyMs(long i)       { this.intervalMs = i; return this; }
        Builder cooldownMs(long c)    { this.cooldownMs = c; return this; }
        Builder deadline(long d)      { this.deadline = d; return this; }
        Builder retry(RetryPolicy rp) { this.retryPolicy = rp; return this; }
        Job build()                   { return new Job(this); }
    }
}

// ---------- Singleton: the scheduler engine ----------
class Scheduler {
    private static class Holder { static final Scheduler INSTANCE = new Scheduler(4); }
    static Scheduler getInstance() { return Holder.INSTANCE; }

    private final SchedulingPolicy policy = new PriorityPolicy();     // Strategy
    private final PriorityBlockingQueue<Job> ready;                   // DSA: binary heap
    private final List<Thread> workers = new ArrayList<>();
    private final List<JobListener> listeners = new CopyOnWriteArrayList<>();  // Observer

    // Dependency graph (Kahn's topological sort, executed incrementally)
    private final Map<String, Set<String>> dependents = new HashMap<>();   // A -> jobs waiting on A
    private final Map<String, Integer> remainingDeps = new HashMap<>();    // job -> in-degree
    private final Map<String, Job> allJobs = new ConcurrentHashMap<>();

    private final Map<String, Long> lastRunAtByType = new ConcurrentHashMap<>();  // cooldown (#621)
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong seqGen = new AtomicLong();
    private final int workerCount;

    private Scheduler(int workerCount) {
        this.workerCount = workerCount;
        this.ready = new PriorityBlockingQueue<>(16, policy::compare);
    }

    void addListener(JobListener l) { listeners.add(l); }

    // ----- submission -----
    String submit(Job job) {
        job.seq = seqGen.incrementAndGet();
        allJobs.put(job.id, job);
        lock.lock();
        try {
            int deps = remainingDeps.getOrDefault(job.id, 0);
            if (deps > 0) {
                job.state = JobState.BLOCKED;      // waits for dependencies
            } else {
                job.state = JobState.READY;
                ready.offer(job);                  // heap insert: O(log n)
            }
        } finally {
            lock.unlock();
        }
        return job.id;
    }

    // B runs only after A succeeds:  edge A -> B (in-degree of B += 1)
    void addDependency(String jobId, String dependsOnId) {
        lock.lock();
        try {
            dependents.computeIfAbsent(dependsOnId, k -> new HashSet<>()).add(jobId);
            remainingDeps.merge(jobId, 1, Integer::sum);
            Job j = allJobs.get(jobId);
            if (j != null && j.state == JobState.READY) {
                ready.remove(j);                   // it must wait now
                j.state = JobState.BLOCKED;
            }
        } finally {
            lock.unlock();
        }
    }

    boolean cancel(String jobId) {
        Job j = allJobs.get(jobId);
        if (j == null) return false;
        lock.lock();
        try {
            if (j.state == JobState.RUNNING || j.state == JobState.SUCCEEDED) return false;
            ready.remove(j);
            j.state = JobState.CANCELLED;
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ----- engine -----
    void start() {
        if (!running.compareAndSet(false, true)) return;
        for (int i = 0; i < workerCount; i++) {
            Thread w = new Thread(this::workerLoop, "worker-" + i);
            w.start();
            workers.add(w);
        }
    }

    private void workerLoop() {
        while (running.get()) {
            try {
                Job job = ready.take();                 // blocks; heap head = highest priority due
                long now = System.currentTimeMillis();

                // not yet due -> put it back and wait (the queue orders by nextRunAt)
                if (job.nextRunAt > now) {
                    ready.offer(job);
                    Thread.sleep(Math.min(50, job.nextRunAt - now));
                    continue;
                }
                // per-type cooldown (LeetCode #621): defer if this type ran too recently
                Long last = lastRunAtByType.get(job.type);
                if (job.cooldownMs > 0 && last != null && now - last < job.cooldownMs) {
                    job.nextRunAt = last + job.cooldownMs;
                    ready.offer(job);
                    continue;
                }
                execute(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void execute(Job job) {
        job.state = JobState.RUNNING;
        job.attempt++;
        lastRunAtByType.put(job.type, System.currentTimeMillis());
        listeners.forEach(l -> l.onStart(job));

        try {
            job.body.run();                              // run OUTSIDE any lock
            job.state = JobState.SUCCEEDED;
            listeners.forEach(l -> l.onSuccess(job));
            onSuccess(job);
        } catch (Exception e) {
            listeners.forEach(l -> l.onFailure(job, e, job.attempt));
            if (job.retryPolicy.shouldRetry(job.attempt)) {
                long backoff = job.retryPolicy.backoffMillis(job.attempt);
                job.nextRunAt = System.currentTimeMillis() + backoff;
                job.state = JobState.READY;
                listeners.forEach(l -> l.onRetry(job, job.attempt, backoff));
                ready.offer(job);                        // re-enqueue after backoff
            } else {
                job.state = JobState.FAILED;             // dependents stay blocked forever
            }
        }
    }

    // Kahn's algorithm, executed incrementally: a success decrements dependents' in-degrees.
    private void onSuccess(Job job) {
        lock.lock();
        try {
            for (String depId : dependents.getOrDefault(job.id, Set.of())) {
                int left = remainingDeps.merge(depId, -1, Integer::sum);
                if (left == 0) {
                    Job d = allJobs.get(depId);
                    if (d != null && d.state == JobState.BLOCKED) {
                        d.state = JobState.READY;
                        ready.offer(d);                  // in-degree hit zero -> now runnable
                    }
                }
            }
            if (job.isRecurring() && job.state == JobState.SUCCEEDED) {
                job.nextRunAt = job.nextFireTime();      // Template Method
                job.attempt = 0;
                job.state = JobState.READY;
                ready.offer(job);
            }
        } finally {
            lock.unlock();
        }
    }

    void shutdown() throws InterruptedException {
        running.set(false);
        for (Thread w : workers) w.interrupt();
        for (Thread w : workers) w.join(TimeUnit.SECONDS.toMillis(2));
    }
}

// ---------- Demo ----------
class Solution {
    public static void main(String[] args) throws Exception {
        Scheduler s = Scheduler.getInstance();
        s.addListener(new ConsoleListener());

        Job extract = new Job.Builder("extract", () -> System.out.println("  extracting..."))
                .priority(5).build();
        Job transform = new Job.Builder("transform", () -> System.out.println("  transforming..."))
                .priority(5).build();
        Job load = new Job.Builder("load", () -> System.out.println("  loading..."))
                .priority(5).build();

        // DAG: extract -> transform -> load  (topological execution)
        s.addDependency("transform", "extract");
        s.addDependency("load", "transform");

        s.submit(extract);
        s.submit(transform);
        s.submit(load);

        // a flaky job with exponential backoff
        s.submit(new Job.Builder("flaky", () -> { throw new RuntimeException("boom"); })
                .retry(new ExponentialBackoff(3, 100)).build());

        s.start();
        Thread.sleep(1500);
        s.shutdown();
    }
}
