# Java Concurrent Collections — A Practical Guide

This guide covers the concurrent collections that actually matter for backend engineering, in priority order. The goal isn't to memorize APIs — it's to understand each collection as a **concurrency strategy** you reach for when you recognize a specific shape of problem.

```
ConcurrentHashMap      → concurrent shared state (key-value lookups under contention)
BlockingQueue          → work coordination + backpressure (producer-consumer)
ConcurrentLinkedQueue  → non-blocking work/event queue (fire-and-forget handoff)
CopyOnWriteArrayList   → immutable-ish snapshots for read-heavy state
ConcurrentSkipListMap  → concurrent + ordered state
```

We'll build up each one with a mental model, the API you actually need, common mistakes, and a working example — then close with a combined mini order-processing pipeline that uses all of them together, so you see *why* you'd pick one over another in the same system.

---

## Tier 1 — Must Know Deeply

## 1. `ConcurrentHashMap` ⭐⭐⭐⭐⭐

### Mental Model
A `Map` that many threads can read and write **at the same time**, without one thread's operation blocking an unrelated key's operation. Internally it uses fine-grained locking / CAS on buckets rather than locking the whole map.

### Why not `Collections.synchronizedMap(new HashMap<>())`?

```java
Map<String, Integer> map = Collections.synchronizedMap(new HashMap<>());
```
This locks the **entire map** on every single call — `get`, `put`, everything. Ten threads reading ten different keys still serialize behind one lock. `ConcurrentHashMap` was built specifically to remove this bottleneck.

### The Problem With "Just Use get/put"

```java
ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();

// RACE CONDITION — even on a ConcurrentHashMap!
if (counts.get("apple") == null) {
    counts.put("apple", 1);
} else {
    counts.put("apple", counts.get("apple") + 1);
}
```
`ConcurrentHashMap` makes *individual* operations thread-safe, but a **sequence** of `get()` then `put()` is still two separate operations — another thread can sneak in between them. This is the single most common misunderstanding people have about this class.

### The Real API You Need: Atomic Compound Operations

```java
ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();

// Atomic increment-or-initialize — the correct way to do the above
counts.merge("apple", 1, Integer::sum);

// Atomic "insert only if key is absent" — great for registries/caches
counts.putIfAbsent("apple", 0);

// Atomic compute — full control over the read-modify-write
counts.compute("apple", (key, currentValue) ->
    currentValue == null ? 1 : currentValue + 1
);

// Atomic computeIfAbsent — THE pattern for lazy-initialized caches
Map<String, List<String>> categoryIndex = new ConcurrentHashMap<>();
categoryIndex.computeIfAbsent("electronics", k -> new ArrayList<>())
             .add("laptop");
```

Each of these executes as **one atomic step** from the map's point of view — no other thread can interleave in the middle.

### Weakly Consistent Iteration

```java
ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
map.put("a", 1);
map.put("b", 2);

// Safe to iterate WHILE another thread modifies the map — never throws
// ConcurrentModificationException. But the iterator may or may not see
// changes made by other threads after iteration started — no guarantee either way.
for (Map.Entry<String, Integer> entry : map.entrySet()) {
    System.out.println(entry.getKey() + " = " + entry.getValue());
}
```
Regular `HashMap` throws `ConcurrentModificationException` if modified mid-iteration by another thread. `ConcurrentHashMap` never throws — it just gives you a "best effort" snapshot-ish view. Don't rely on it reflecting a precise point-in-time state.

### Working Example: Thread-Safe Word Frequency Counter

```java
import java.util.concurrent.*;
import java.util.List;

public class WordFrequencyDemo {
    public static void main(String[] args) throws InterruptedException {
        ConcurrentHashMap<String, Integer> frequency = new ConcurrentHashMap<>();
        List<String> words = List.of("java", "thread", "java", "map", "thread", "java");

        ExecutorService executor = Executors.newFixedThreadPool(4);

        for (String word : words) {
            executor.submit(() -> frequency.merge(word, 1, Integer::sum));
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        frequency.forEach((word, count) -> System.out.println(word + ": " + count));
        // java: 3, thread: 2, map: 1  — correct every time, no race conditions
    }
}
```

---

## 2. `BlockingQueue` ⭐⭐⭐⭐⭐

### Mental Model
A queue where **adding blocks when full** and **removing blocks when empty**. This is the backbone of producer-consumer coordination — it gives you built-in **backpressure** for free: if consumers can't keep up, producers automatically slow down instead of overwhelming memory.

### Core Methods

| Method | Behavior when full/empty |
|---|---|
| `put(e)` / `take()` | **Blocks** until space/item is available |
| `offer(e)` / `poll()` | Returns immediately — `false`/`null` if full/empty |
| `offer(e, timeout, unit)` / `poll(timeout, unit)` | Blocks up to timeout, then gives up |

### Bounded vs Unbounded

```java
BlockingQueue<String> bounded = new ArrayBlockingQueue<>(10);   // capacity fixed at 10 — provides backpressure
BlockingQueue<String> unbounded = new LinkedBlockingQueue<>();  // grows without limit — no backpressure, risk of OOM
```
**Always prefer bounded queues in production.** An unbounded queue means a slow consumer can let memory grow forever.

### Working Example: Producer-Consumer with Backpressure

```java
import java.util.concurrent.*;

public class ProducerConsumerDemo {
    public static void main(String[] args) {
        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(5); // small — forces backpressure

        Runnable producer = () -> {
            try {
                for (int i = 1; i <= 20; i++) {
                    queue.put(i); // BLOCKS here once queue hits 5 items
                    System.out.println("Produced: " + i + " (queue size: " + queue.size() + ")");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Runnable consumer = () -> {
            try {
                while (true) {
                    Integer item = queue.take(); // BLOCKS if queue is empty
                    Thread.sleep(200); // simulate slow processing
                    System.out.println("Consumed: " + item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        new Thread(producer).start();
        new Thread(consumer).start();
    }
}
```
Run this and watch: the producer races ahead, fills the queue to 5, then **stalls** — printing pauses until the consumer catches up. That stall *is* backpressure working correctly, not a bug.

---

## 3. `ConcurrentLinkedQueue` ⭐⭐⭐⭐

### Mental Model
A **non-blocking**, unbounded queue built on CAS (compare-and-swap) operations instead of locks. There's no `put()`/`take()` — only `offer()`/`poll()`, which never block. If the queue is empty, `poll()` just returns `null` immediately.

### When to Reach for This Instead of `BlockingQueue`

- You want a shared work/event queue but **don't need consumers to block-wait** — they'll check back later, or you're polling in a loop with other work to do in between.
- High-throughput scenarios where lock-free CAS operations outperform the locking used inside `BlockingQueue` implementations.
- You explicitly don't want backpressure — e.g., an unbounded event log where producers should never be slowed down.

### API

```java
ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

queue.offer("event-1");  // never blocks, always succeeds (unbounded)
queue.offer("event-2");

String next = queue.poll(); // returns null if empty — does NOT block
System.out.println(next);   // "event-1"

String peeked = queue.peek(); // look without removing, null if empty
```

### Working Example: Non-Blocking Event Collector

```java
import java.util.concurrent.*;

public class EventQueueDemo {
    public static void main(String[] args) throws InterruptedException {
        ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(4);

        // Multiple threads push events concurrently — no blocking, no coordination needed
        for (int i = 1; i <= 10; i++) {
            int eventId = i;
            executor.submit(() -> events.offer("event-" + eventId));
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Drain it — poll() returns null once empty, that's our stopping condition
        String event;
        while ((event = events.poll()) != null) {
            System.out.println("Draining: " + event);
        }
    }
}
```

### `BlockingQueue` vs `ConcurrentLinkedQueue` — The Real Decision

| Question | Answer points to... |
|---|---|
| Do consumers need to *wait* for work to arrive? | `BlockingQueue` |
| Do you need bounded capacity / backpressure? | `BlockingQueue` |
| Do consumers poll periodically, or do other work between checks? | `ConcurrentLinkedQueue` |
| Is raw throughput under heavy contention your top priority? | `ConcurrentLinkedQueue` |

---

## Tier 2 — Know Well

## 4. `CopyOnWriteArrayList` ⭐⭐⭐

### Mental Model
Every **write** (`add`, `remove`, `set`) copies the entire underlying array. Every **read** operates on an immutable snapshot, with zero locking and zero risk of `ConcurrentModificationException` — because iterators work against the array as it was at the moment they were created.

```
many reads
few writes
     ↓
CopyOnWriteArrayList
```

If writes are frequent, this is a bad choice — you're copying a growing array on every single mutation. This is strictly a **read-heavy, write-rare** tool.

### Classic Use Cases
- **Listener/subscriber lists** — registered rarely, iterated constantly on every event.
- **Configuration snapshots** — config changes are rare; reads happen on every request.
- **Registries** — plugin/handler registries that are populated at startup and read forever after.

### Working Example: Event Listener Registry

```java
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ListenerRegistryDemo {
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<String> listener) {
        listeners.add(listener); // rare — full array copy, that's fine
    }

    public void publish(String event) {
        // Frequent — safe to iterate even if another thread subscribes DURING this loop.
        // The iterator sees the snapshot from when iteration started; no exception, no lock.
        for (Consumer<String> listener : listeners) {
            listener.accept(event);
        }
    }

    public static void main(String[] args) {
        ListenerRegistryDemo registry = new ListenerRegistryDemo();

        registry.subscribe(event -> System.out.println("Logger received: " + event));
        registry.subscribe(event -> System.out.println("Metrics received: " + event));

        // A listener that subscribes ANOTHER listener mid-publish — would break a plain ArrayList
        registry.subscribe(event -> {
            System.out.println("Dynamic listener received: " + event);
            registry.subscribe(e -> System.out.println("Newly added listener: " + e));
        });

        registry.publish("user-signed-up"); // safe, no ConcurrentModificationException
        registry.publish("user-signed-up"); // the dynamically-added listener now also fires
    }
}
```

**Don't** memorize the full API surface here — just internalize the trade-off: *pay on write, get essentially free/lock-free reads.*

---

## Tier 3 — Know Conceptually

## 5. `ConcurrentSkipListMap` / `ConcurrentSkipListSet` ⭐⭐

### Mental Model
The one-line summary is really all you need day-to-day:

> "I need a concurrent collection that also maintains **sorted order**."

`ConcurrentHashMap` gives you no ordering guarantees at all. If you need thread-safe access **and** keys/elements maintained in sorted order (e.g., always being able to efficiently get the smallest/largest key, or a sorted range), reach for the skip-list variants instead. They're implemented as a concurrent skip list (a probabilistic, layered linked-structure) giving `O(log n)` operations without a single global lock.

### Quick Look at the API

```java
import java.util.concurrent.ConcurrentSkipListMap;

ConcurrentSkipListMap<Integer, String> leaderboard = new ConcurrentSkipListMap<>();

leaderboard.put(150, "playerA");
leaderboard.put(90, "playerB");
leaderboard.put(200, "playerC");

// Always iterates in sorted key order — thread-safe, no manual sorting needed
leaderboard.forEach((score, player) -> System.out.println(score + ": " + player));
// 90: playerB, 150: playerA, 200: playerC

System.out.println("Top score: " + leaderboard.lastEntry());   // 200=playerC
System.out.println("Lowest score: " + leaderboard.firstEntry()); // 90=playerB

// Range queries — genuinely the killer feature over ConcurrentHashMap
System.out.println(leaderboard.headMap(150)); // everything below 150: {90=playerB}
System.out.println(leaderboard.tailMap(150)); // 150 and above: {150=playerA, 200=playerC}
```

### When You'd Actually Reach for This
- A live leaderboard or ranking system updated concurrently.
- A time-ordered event index where you need range queries (e.g., "all events between timestamp X and Y") under concurrent writes.
- Anything where you'd otherwise be tempted to use a `TreeMap` wrapped in manual synchronization.

You don't need `ConcurrentHashMap`-level depth here — just recognize the shape of the problem ("concurrent + sorted") and know this is the tool.

---

## Putting It All Together — Mini Order Processing Pipeline

This example uses **all four major collections** in one realistic flow, so you can see *why* each one was chosen rather than just recognizing their names in isolation.

**Scenario:** Orders come in from multiple producer threads. We need to:
1. Queue orders for processing with backpressure (`BlockingQueue`)
2. Track running totals per customer, safely under concurrency (`ConcurrentHashMap`)
3. Fire off non-blocking audit events for every processed order (`ConcurrentLinkedQueue`)
4. Notify registered listeners (e.g., a dashboard) whenever an order completes (`CopyOnWriteArrayList`)

```java
import java.util.concurrent.*;
import java.util.function.Consumer;

public class OrderProcessorDemo {

    record Order(String customerId, double amount) {}

    // 1. ConcurrentHashMap — concurrent shared state: running total per customer
    private final ConcurrentHashMap<String, Double> customerTotals = new ConcurrentHashMap<>();

    // 2. BlockingQueue — work coordination + backpressure: incoming orders to process
    private final BlockingQueue<Order> incomingOrders = new ArrayBlockingQueue<>(20);

    // 3. ConcurrentLinkedQueue — non-blocking audit log, producers never wait on it
    private final ConcurrentLinkedQueue<String> auditLog = new ConcurrentLinkedQueue<>();

    // 4. CopyOnWriteArrayList — rarely-changing listener list, read on every order
    private final CopyOnWriteArrayList<Consumer<Order>> completionListeners = new CopyOnWriteArrayList<>();

    public void subscribeToCompletions(Consumer<Order> listener) {
        completionListeners.add(listener);
    }

    public void submitOrder(Order order) throws InterruptedException {
        incomingOrders.put(order); // blocks if the queue is full — natural backpressure
    }

    public void processOrders() {
        try {
            while (true) {
                Order order = incomingOrders.take(); // blocks until an order arrives

                // Atomic update — safe even with many processor threads
                customerTotals.merge(order.customerId(), order.amount(), Double::sum);

                // Fire-and-forget audit entry — never blocks the processing thread
                auditLog.offer("Processed order for " + order.customerId() + ": $" + order.amount());

                // Notify listeners — safe to iterate even if someone subscribes mid-loop
                for (Consumer<Order> listener : completionListeners) {
                    listener.accept(order);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        OrderProcessorDemo processor = new OrderProcessorDemo();

        processor.subscribeToCompletions(order ->
            System.out.println("[Dashboard] Order completed: " + order));

        ExecutorService processingPool = Executors.newFixedThreadPool(2);
        processingPool.submit(processor::processOrders);
        processingPool.submit(processor::processOrders);

        ExecutorService producerPool = Executors.newFixedThreadPool(4);
        String[] customers = {"alice", "bob", "alice", "carol", "bob"};
        for (String customer : customers) {
            producerPool.submit(() -> {
                try {
                    processor.submitOrder(new Order(customer, 25.0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        producerPool.shutdown();
        producerPool.awaitTermination(5, TimeUnit.SECONDS);
        Thread.sleep(1000); // let processors finish draining

        System.out.println("\n--- Customer Totals ---");
        processor.customerTotals.forEach((customer, total) ->
            System.out.println(customer + ": $" + total));

        System.out.println("\n--- Audit Log ---");
        processor.auditLog.forEach(System.out::println);

        processingPool.shutdownNow();
    }
}
```

### Why Each Collection Was Chosen Here

| Need in the pipeline | Collection | Why not something else |
|---|---|---|
| Slow down producers if processors fall behind | `BlockingQueue` | `ConcurrentLinkedQueue` has no backpressure — memory could grow unbounded |
| Accumulate per-customer totals safely | `ConcurrentHashMap` with `merge` | A plain `HashMap` would corrupt totals under concurrent writes |
| Log every processed order without slowing processing | `ConcurrentLinkedQueue` | `BlockingQueue` would be overkill — nothing needs to *wait* on the log |
| Notify dashboard listeners on every order (read constantly, written rarely) | `CopyOnWriteArrayList` | A plain `ArrayList` would throw `ConcurrentModificationException` if a listener subscribed mid-iteration |

---

## Summary Table

| Collection | Strategy | Blocking? | Ordered? | Best for |
|---|---|---|---|---|
| `ConcurrentHashMap` | Concurrent shared state | No | No | Counters, caches, registries keyed by ID |
| `BlockingQueue` | Work coordination + backpressure | Yes (`put`/`take`) | Insertion order | Producer-consumer pipelines |
| `ConcurrentLinkedQueue` | Non-blocking work/event queue | No | Insertion order | High-throughput queues, event logs |
| `CopyOnWriteArrayList` | Read-heavy snapshots | No | Insertion order | Listener lists, config, registries |
| `ConcurrentSkipListMap/Set` | Concurrent + ordered state | No | Sorted | Leaderboards, ranged/sorted lookups |

## Questions Worth Exploring

**Why does `ConcurrentHashMap` still need `merge`/`compute` if it's already thread-safe?**
Because *atomicity of individual calls* is not the same as *atomicity of your business logic across multiple calls*. A single `get()` or `put()` is safe on its own, but stringing them together (`get` then `put`) reopens a race condition even on a fully thread-safe map.

**What breaks if we swap `BlockingQueue` for `ConcurrentLinkedQueue` in the order pipeline?**
Producers never slow down, since `ConcurrentLinkedQueue` has no capacity limit and never blocks. A burst of orders could grow the queue unbounded and exhaust memory before consumers catch up — backpressure is lost entirely.

**What breaks if `completionListeners` were a plain `ArrayList`?**
A listener that subscribes another listener mid-notification would throw `ConcurrentModificationException`, since a plain `ArrayList`'s iterator isn't tolerant of structural changes during iteration.

**What changes if `customerTotals` becomes a `ConcurrentSkipListMap`?**
Swapping it in and adding a `.firstEntry()`/`.lastEntry()` call lets you find the lowest- or highest-spending customer live, with results always in sorted order — a natural bridge from Tier 1 into Tier 3.