# Lesson 15: Concurrent Collections

## What you'll learn

- Why `Collections.synchronizedMap` isn't enough, and what concurrent collections do differently
- `ConcurrentHashMap` and its atomic compound operations (`merge`, `compute`, `computeIfAbsent`)
- `BlockingQueue`: the producer-consumer queue you built by hand in Lesson 08, ready-made
- When to use `CopyOnWriteArrayList`, `ConcurrentLinkedQueue` and `ConcurrentSkipListMap`

---

## Why this matters

Lesson 05 showed `ArrayList` and `HashMap` corrupting themselves under concurrent use. Wrapping everything in `synchronized` works, but it makes every thread queue for one lock, even threads touching completely different keys.

The concurrent collections in `java.util.concurrent` are designed for many threads from the start. They let unrelated operations run in parallel, they offer *atomic compound operations* so you don't need check-then-act, and their iterators never throw `ConcurrentModificationException`.

> This lesson is the overview. For deeper material, see [`CC.MD`](../../src/main/java/org/javaguy/concurrentcollections/CC.MD) (a guide to the same collections, ending in an order-processing pipeline) and [`docs/map-internals.md`](../../docs/map-internals.md) (how `ConcurrentHashMap` works inside: CAS, per-bin locks, cooperative resizing).

---

## The concept

### Synchronized wrappers vs concurrent collections

| | `Collections.synchronizedMap(new HashMap<>())` | `ConcurrentHashMap` |
|---|---|---|
| Locking | One lock for the whole map | Lock-free reads, and writes lock only one bin |
| Reads | Wait for each other | Never block |
| Iteration | You must hold the lock manually, or risk `ConcurrentModificationException` | Weakly consistent: never throws, and may or may not show changes made during iteration |
| Compound actions | You write them under the lock yourself | Built in: `merge`, `compute`, `putIfAbsent`... |
| `null` keys or values | Allowed | **Not allowed** |

### Choosing a collection

| You need... | Use |
|---|---|
| A shared key-value map | `ConcurrentHashMap` |
| Producer-consumer hand-off with backpressure | `BlockingQueue`: `ArrayBlockingQueue` (bounded) or `LinkedBlockingQueue` |
| A non-blocking queue for fire-and-forget events | `ConcurrentLinkedQueue` |
| A list that is read constantly and rarely changed, such as listeners or config | `CopyOnWriteArrayList` |
| A sorted concurrent map, with range queries | `ConcurrentSkipListMap` |
| A concurrent set | `ConcurrentHashMap.newKeySet()` |

---

## Hands-on

### 1. A tour

`CollectionsTour.java`:

```java
void main() throws InterruptedException {
    var wordCounts = new ConcurrentHashMap<String, Integer>();
    var listeners = new CopyOnWriteArrayList<String>(List.of("audit", "email"));
    var events = new ConcurrentLinkedQueue<String>();
    var leaderboard = new ConcurrentSkipListMap<Integer, String>(Comparator.reverseOrder());

    List<String> words = List.of("java", "thread", "java", "lock", "thread", "java");
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
        for (String word : words) {
            pool.submit(() -> {
                wordCounts.merge(word, 1, Integer::sum);
                events.offer("counted " + word);
            });
        }
        pool.submit(() -> leaderboard.put(870, "alice"));
        pool.submit(() -> leaderboard.put(920, "bob"));
        pool.submit(() -> leaderboard.put(640, "carol"));
    }

    for (String listener : listeners) {
        listeners.add(listener + "-copy");
    }

    IO.println("word counts:  " + new TreeMap<>(wordCounts));
    IO.println("events:       " + events.size());
    IO.println("listeners:    " + listeners);
    IO.println("top player:   " + leaderboard.firstEntry());
    IO.println("scores > 700: " + leaderboard.headMap(700));
}
```

```
word counts:  {java=3, lock=1, thread=2}
events:       6
listeners:    [audit, email, audit-copy, email-copy]
top player:   920=bob
scores > 700: {920=bob, 870=alice}
```

- **`merge(word, 1, Integer::sum)`** says "put 1 if absent, otherwise add 1", as one atomic operation. This is the idiomatic concurrent counter. It is what the repository demo [`ConcurrentHashMapDemo.java`](../../src/main/java/org/javaguy/concurrentcollections/ConcurrentHashMapDemo.java) uses too.
- **`CopyOnWriteArrayList`**: adding to the list *while iterating over it* works, because every write copies the whole backing array and the loop keeps iterating over the old snapshot. That is why the loop saw only the two original items. Try the same with an `ArrayList` and you get `ConcurrentModificationException`.
- **`ConcurrentSkipListMap`**: sorted (here highest score first), thread-safe, and supports range views like `headMap`. It's the concurrent counterpart of `TreeMap`.
- `new TreeMap<>(wordCounts)` is only there to print the keys in sorted order.

### 2. `computeIfAbsent`: load once, even under contention

Lesson 05's cache check-then-act bug, fixed with one method call.

`ComputeIfAbsent.java`:

```java
void main() throws InterruptedException {
    var cache = new ConcurrentHashMap<String, String>();
    var loads = new AtomicInteger();

    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> cache.computeIfAbsent("user-42", key -> {
                loads.incrementAndGet();
                pause(100);
                return "profile of " + key;
            }));
        }
    }
    IO.println(cache.get("user-42") + ", loaded " + loads.get() + " time(s)");
}

void pause(long millis) {
    try {
        Thread.sleep(millis);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

```
profile of user-42, loaded 1 time(s)
```

Eight threads asked at the same moment, and the value was loaded exactly once. The other seven waited for the first load and then used its result. `ConcurrentHashMap` guarantees the mapping function runs **at most once per key**.

It does this by locking that key's bin while the function runs, so:

- **Keep the function short.** Other keys in the same bin wait too.
- **Never modify the same map from inside the function.** It can deadlock or throw `IllegalStateException("Recursive update")`.

The atomic methods you should reach for:

| Instead of... | Use |
|---|---|
| `if (!map.containsKey(k)) map.put(k, v)` | `map.putIfAbsent(k, v)` |
| `if (!map.containsKey(k)) map.put(k, load(k))` | `map.computeIfAbsent(k, this::load)` |
| `map.put(k, map.getOrDefault(k, 0) + 1)` | `map.merge(k, 1, Integer::sum)` |
| get, change, put back | `map.compute(k, (key, old) -> ...)` |
| `if (map.get(k) == old) map.put(k, new)` | `map.replace(k, old, new)` |

For high-contention counters, `map.computeIfAbsent(k, key -> new LongAdder()).increment()` combines this lesson with Lesson 12.

### 3. `BlockingQueue`: producer-consumer without `wait`/`notify`

`BlockingQueueDemo.java`:

```java
void main() throws InterruptedException {
    BlockingQueue<String> orders = new ArrayBlockingQueue<>(2);
    String poisonPill = "SHUTDOWN";

    Thread producer = Thread.ofPlatform().name("producer").start(() -> {
        try {
            for (int i = 1; i <= 5; i++) {
                orders.put("order-" + i);
                IO.println("placed order-" + i + " (queue size " + orders.size() + ")");
            }
            orders.put(poisonPill);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });

    Thread consumer = Thread.ofPlatform().name("consumer").start(() -> {
        try {
            while (true) {
                String order = orders.take();
                if (order.equals(poisonPill)) {
                    IO.println("consumer shutting down");
                    return;
                }
                Thread.sleep(150);
                IO.println("    shipped " + order);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });

    producer.join();
    consumer.join();

    boolean accepted = orders.offer("late-order", 100, TimeUnit.MILLISECONDS);
    IO.println("offer to empty queue accepted? " + accepted);
    IO.println("poll: " + orders.poll() + ", poll again: " + orders.poll());
}
```

One run:

```
placed order-1 (queue size 1)
placed order-2 (queue size 1)
placed order-3 (queue size 2)
    shipped order-1
placed order-4 (queue size 2)
    shipped order-2
placed order-5 (queue size 2)
    shipped order-3
    shipped order-4
    shipped order-5
consumer shutting down
offer to empty queue accepted? true
poll: late-order, poll again: null
```

This is Lesson 08's `BoundedBuffer` with no hand-written locking, and the same backpressure behaviour. `BlockingQueue` gives you a choice of behaviour for when the queue is full or empty:

| | Throws | Returns a special value | Blocks | Waits with a timeout |
|---|---|---|---|---|
| Insert | `add(e)` | `offer(e)` returns `false` | `put(e)` | `offer(e, time, unit)` |
| Remove | `remove()` | `poll()` returns `null` | `take()` | `poll(time, unit)` |

| Implementation | Notes |
|---|---|
| `ArrayBlockingQueue(n)` | Bounded, fixed array, one lock. The default choice when you want backpressure. |
| `LinkedBlockingQueue` | Optionally bounded, and **unbounded by default**. Separate locks for head and tail give higher throughput. |
| `PriorityBlockingQueue` | Unbounded, ordered by priority |
| `DelayQueue` | Elements become available only after their delay expires |
| `SynchronousQueue` | Capacity zero: each `put` waits for a `take`. Used by `newCachedThreadPool`. |

Every `ThreadPoolExecutor` (Lesson 10) has a `BlockingQueue` inside it, and now you know what it does.

### 4. `CopyOnWriteArrayList` and `ConcurrentLinkedQueue`

- **`CopyOnWriteArrayList`**: every write copies the entire array. Reads and iteration take no lock and see a stable snapshot. That's perfect for listener lists and routing tables, which are read thousands of times per write. For a list that changes often, it's terrible: `add` costs O(n) and creates garbage.
- **`ConcurrentLinkedQueue`**: an unbounded, lock-free (CAS-based) queue. `offer` and `poll` never block, and `poll` returns `null` when the queue is empty. Use it when producers must never wait and consumers can poll. Because it's unbounded, a slow consumer means memory grows without limit, and nothing pushes back.

---

## Try it yourself

1. Replace `ConcurrentHashMap` with `HashMap` in `CollectionsTour` and run it 20 times. What goes wrong?
2. Call `map.put("key", null)` on a `ConcurrentHashMap`. What happens? Why might concurrent maps reject `null`? (Hint: what would `get` returning `null` mean?)
3. Rewrite Lesson 08's `BoundedBuffer` demo using `LinkedBlockingQueue` with no capacity. What happens to memory with a fast producer and a slow consumer?
4. In `ComputeIfAbsent`, try calling `cache.put("other", "x")` inside the mapping function. What happens?

---

## Common mistakes

- **Check-then-act across two calls on a concurrent map.** Each call is safe on its own, the pair isn't. Use `computeIfAbsent`, `merge` or `putIfAbsent`.
- **Slow or recursive work inside `compute*` functions.** It holds a lock on that key's bin.
- **Using `CopyOnWriteArrayList` for write-heavy lists.** Every write copies the whole array.
- **Unbounded queues between fast producers and slow consumers** (`LinkedBlockingQueue()`, `ConcurrentLinkedQueue`). Memory grows until the service dies.
- **Trusting `size()` for decisions.** Under concurrency it's an estimate that may be stale by the time you use it.
- **Storing `null` in a concurrent collection.** They reject it on purpose, because `get()` returning `null` must mean "absent".

---

## Check your understanding

**1. Why is `ConcurrentHashMap` faster than `Collections.synchronizedMap` under contention?**

<details>
<summary>Reveal answer</summary>

The synchronized wrapper uses one lock for every operation, so all threads queue behind it. `ConcurrentHashMap` reads without locking and locks only the single bin being written, so threads working on different keys don't block each other.

</details>

**2. How do you count word frequencies safely from many threads with a `ConcurrentHashMap`?**

<details>
<summary>Reveal answer</summary>

`counts.merge(word, 1, Integer::sum)`. It inserts 1 if the key is absent and otherwise adds 1, as one atomic operation. For very hot keys, `computeIfAbsent(word, key -> new LongAdder()).increment()` scales better.

</details>

**3. What's the difference between `put` and `offer` on a full `ArrayBlockingQueue`?**

<details>
<summary>Reveal answer</summary>

`put` blocks until space is available. `offer` returns `false` immediately. `offer(e, timeout, unit)` waits up to the timeout. `add` throws `IllegalStateException`.

</details>

**4. When is `CopyOnWriteArrayList` the right choice?**

<details>
<summary>Reveal answer</summary>

When reads and iteration vastly outnumber writes, such as event-listener lists, subscriber lists or small configuration lists. Iteration is lock-free and never throws `ConcurrentModificationException`. Every write copies the whole array, so it's a poor fit for data that changes often.

</details>

**5. Iterating a `ConcurrentHashMap` while another thread adds entries: what happens?**

<details>
<summary>Reveal answer</summary>

No exception. The iterator is *weakly consistent*: it returns elements as they were at some point during iteration, and it may or may not include entries added after it was created.

</details>

---

## Recap

- Concurrent collections let unrelated operations run in parallel and never throw `ConcurrentModificationException`.
- `ConcurrentHashMap`: use `merge`, `compute`, `computeIfAbsent` and `putIfAbsent` instead of check-then-act.
- `BlockingQueue` is producer-consumer with backpressure. Choose bounded queues.
- `CopyOnWriteArrayList` for read-mostly lists, `ConcurrentLinkedQueue` for non-blocking hand-offs, `ConcurrentSkipListMap` for sorted data.

**Next: [Lesson 16, Sharing safely without locks](16-immutability-threadlocal-scopedvalue.md)**
