# Lesson 26: Final Exam

Thirty questions covering the whole course. Try to answer each one before opening the answer. Most answers name the lesson to go back to if you got it wrong.

The questions come in four kinds:

- **Predict**: what does this code do?
- **Spot the bug**: what's wrong, and how do you fix it?
- **Pick the tool**: which part of `java.util.concurrent` fits this problem?
- **Explain**: why does something work the way it does?

---

## Section A: Threads (Lessons 01–04)

**1. Predict.** What does this print?

```java
Thread thread = new Thread(() -> IO.println(Thread.currentThread().getName()), "worker");
thread.run();
thread.start();
```

<details>
<summary>Reveal answer</summary>

`main`, then `worker`. `run()` is a plain method call on the current thread. Only `start()` creates a new thread. (Lesson 02)

</details>

**2. Explain.** A service spends 95% of each request waiting on the network. Is "number of threads = number of cores" a good rule for it?

<details>
<summary>Reveal answer</summary>

No. That rule is for CPU-bound work. I/O-bound work spends most of its time waiting, so far more concurrent tasks than cores are needed to keep the CPU busy. Use a larger pool, or better, virtual threads. (Lessons 01 and 21)

</details>

**3. Predict.** `main` starts a non-daemon thread running `while (true) {}` and then returns. Does the JVM exit?

<details>
<summary>Reveal answer</summary>

No. The JVM waits for all non-daemon threads to finish. (Lesson 03)

</details>

**4. Spot the bug.**

```java
while (!Thread.currentThread().isInterrupted()) {
    try {
        processNextBatch();
        Thread.sleep(1_000);
    } catch (InterruptedException e) {
        log.warn("interrupted", e);
    }
}
```

<details>
<summary>Reveal answer</summary>

`sleep` clears the interrupt flag when it throws `InterruptedException`. The catch block only logs, so the loop condition never sees the interrupt, and the thread can never be stopped. Fix it by calling `Thread.currentThread().interrupt();` in the catch, and then `break` or `return`. (Lesson 04)

</details>

**5. Explain.** What state does a thread show while waiting to enter a `synchronized` block that another thread holds? And while inside `Thread.sleep(500)`?

<details>
<summary>Reveal answer</summary>

`BLOCKED`, and `TIMED_WAITING`. (Lesson 03)

</details>

---

## Section B: Shared state (Lessons 05–09)

**6. Predict.** Four threads each run `counter++` 100,000 times on a shared `int counter`. What values can the final result be?

<details>
<summary>Reveal answer</summary>

Anything up to 400,000, usually well below it, and different on each run. `counter++` is a read, an add and a write, and interleaved updates are lost. (Lesson 05)

</details>

**7. Spot the bug.** `inventory` is a `ConcurrentHashMap<String, Integer>`.

```java
if (inventory.get(sku) > 0) {
    inventory.put(sku, inventory.get(sku) - 1);
}
```

<details>
<summary>Reveal answer</summary>

Check-then-act. Two threads can both see 1 and both decrement, which oversells. Each call is thread-safe on its own, but the sequence isn't. Use one atomic operation: `inventory.computeIfPresent(sku, (key, count) -> count > 0 ? count - 1 : count)`, and check the result, or protect the whole sequence with a lock. (Lessons 05 and 15)

</details>

**8. Explain.** `synchronized` gives you two guarantees. What are they?

<details>
<summary>Reveal answer</summary>

Mutual exclusion (only one thread at a time runs code guarded by the same lock) and visibility (writes made before a lock is released are visible to the next thread that acquires the same lock). (Lesson 06)

</details>

**9. Spot the bug.**

```java
private Integer balance = 0;

void deposit(int amount) {
    synchronized (balance) {
        balance += amount;
    }
}
```

<details>
<summary>Reveal answer</summary>

`balance += amount` replaces the `Integer` object, so threads end up locking different objects, which means no mutual exclusion at all. Small `Integer` values are also cached and shared across the JVM. Lock on a `private final Object lock`, or use `AtomicInteger`/`LongAdder`. (Lesson 06)

</details>

**10. Predict.** `boolean running = true;` (not volatile). A worker loops `while (running) { count++; }`, and `main` sets `running = false` a second later. Is the worker guaranteed to stop?

<details>
<summary>Reveal answer</summary>

No. There's no happens-before edge, so the JIT may hoist the read out of the loop and the worker can spin forever. Mark the field `volatile`. (Lesson 07)

</details>

**11. Explain.** Why is a `volatile int` counter still wrong?

<details>
<summary>Reveal answer</summary>

`volatile` guarantees visibility and ordering, not atomicity. `count++` is still three steps, and two threads can read the same value. (Lesson 07)

</details>

**12. Spot the bug.**

```java
synchronized T take() throws InterruptedException {
    if (items.isEmpty()) {
        wait();
    }
    return items.remove();
}
```

<details>
<summary>Reveal answer</summary>

`wait()` must be inside a `while` loop. After waking, the queue may be empty again: another consumer took the item, the wake-up was spurious, or the notification was for another reason. `items.remove()` would then throw. Use `while (items.isEmpty()) wait();`. (Lesson 08)

</details>

**13. Explain.** Thread 1 locks A then B, and Thread 2 locks B then A. Name the problem and two ways to prevent it.

<details>
<summary>Reveal answer</summary>

Deadlock. Prevent it with a consistent global lock order (for example, always lower ID first), or with `tryLock(timeout)` and back-off with jitter, or by never holding two locks at once. (Lessons 09 and 13)

</details>

---

## Section C: `java.util.concurrent` (Lessons 10–16)

**14. Explain.** A `ThreadPoolExecutor` has `corePoolSize = 4`, `maximumPoolSize = 50` and an unbounded `LinkedBlockingQueue`. How many threads will it create under heavy load?

<details>
<summary>Reveal answer</summary>

Four. Threads above the core size are created only when the queue is full, and an unbounded queue never fills. Tasks queue until memory runs out. (Lesson 10)

</details>

**15. Predict.** `pool.submit(() -> { throw new IllegalStateException("boom"); });`, and the returned `Future` is ignored. What appears in the logs?

<details>
<summary>Reveal answer</summary>

Nothing. `submit` stores the exception in the `Future`, and it only surfaces if someone calls `get()`. With `execute`, it would reach the uncaught-exception handler instead. (Lessons 10 and 11)

</details>

**16. Explain.** `future.get(2, SECONDS)` throws `TimeoutException`. Is the task still running? How do you stop it?

<details>
<summary>Reveal answer</summary>

Probably yes. The timeout only stops the caller from waiting. `future.cancel(true)` interrupts the worker thread, and the task stops only if it responds to interruption. (Lesson 11)

</details>

**17. Pick the tool.** You want to process 100 downloads in the order they *finish*, not the order they were submitted.

<details>
<summary>Reveal answer</summary>

`ExecutorCompletionService` and its `take()`, or `CompletableFuture`s with a `thenAccept` callback on each. (Lessons 11 and 18)

</details>

**18. Pick the tool.** A request-count metric is incremented by hundreds of threads and read once every 10 seconds.

<details>
<summary>Reveal answer</summary>

`LongAdder`. It spreads contended updates across cells, and `sum()` combines them when read. (Lesson 12)

</details>

**19. Explain.** Why must the function passed to `AtomicReference.updateAndGet` be free of side effects?

<details>
<summary>Reveal answer</summary>

It runs inside a CAS retry loop and may run several times when there's contention. Any side effect could happen more than once. (Lesson 12)

</details>

**20. Spot the bug.**

```java
lock.lock();
doWork();
lock.unlock();
```

<details>
<summary>Reveal answer</summary>

If `doWork()` throws, `unlock()` never runs and the lock is held forever. Use `lock.lock(); try { doWork(); } finally { lock.unlock(); }`. (Lesson 13)

</details>

**21. Pick the tool.** The application must not accept traffic until three independent startup checks have all passed.

<details>
<summary>Reveal answer</summary>

`CountDownLatch(3)`. Each check calls `countDown()` in a `finally`, and the startup thread calls `await(timeout)`. (Lesson 14)

</details>

**22. Pick the tool.** No more than 5 concurrent calls may reach a rate-limited partner API, and callers should give up after 100 ms.

<details>
<summary>Reveal answer</summary>

`Semaphore(5)` with `tryAcquire(100, MILLISECONDS)`, and `release()` in a `finally`. That's the bulkhead pattern. (Lessons 14 and 23)

</details>

**23. Explain.** Why does `ConcurrentHashMap` reject `null` keys and values?

<details>
<summary>Reveal answer</summary>

Under concurrency, `get(k)` returning `null` must mean exactly one thing: absent. If `null` values were allowed, you couldn't tell "absent" from "mapped to null" without a separate `containsKey` check, and that check would be a check-then-act race. (Lesson 15)

</details>

**24. Spot the bug.** In a web application running on a thread pool:

```java
static final ThreadLocal<User> CURRENT_USER = new ThreadLocal<>();

void handle(Request request) {
    CURRENT_USER.set(authenticate(request));
    process(request);
}
```

<details>
<summary>Reveal answer</summary>

The value is never removed. Pool threads are reused, so the next request on that thread, which may be unauthenticated, sees the previous user. Wrap the body in `try { ... } finally { CURRENT_USER.remove(); }`, or use `ScopedValue.where(CURRENT_USER, user).run(...)`, which can't leak. (Lesson 16)

</details>

---

## Section D: Async, parallel and modern Java (Lessons 17–25)

**25. Predict.** A task scheduled with `scheduleAtFixedRate` throws a `RuntimeException` on its fourth run. What happens on run five?

<details>
<summary>Reveal answer</summary>

There's no run five. The task is silently cancelled, and the exception sits in its `ScheduledFuture`. Wrap periodic task bodies in `try/catch`. (Lesson 17)

</details>

**26. Explain.** `loadUser(id)` returns `CompletableFuture<User>`, and `loadOrders(user)` returns `CompletableFuture<List<Order>>`. Why use `thenCompose` instead of `thenApply` to chain them?

<details>
<summary>Reveal answer</summary>

`thenApply` would give `CompletableFuture<CompletableFuture<List<Order>>>`. `thenCompose` flattens it to `CompletableFuture<List<Order>>`. It's the same `map` vs `flatMap` difference as with streams. (Lesson 18)

</details>

**27. Spot the bug.** In a REST controller:

```java
List<Price> prices = skus.parallelStream()
        .map(sku -> pricingClient.fetch(sku))
        .toList();
```

<details>
<summary>Reveal answer</summary>

Blocking HTTP calls inside a parallel stream run on the JVM-wide `ForkJoinPool.commonPool()`, which has about (cores − 1) threads. Concurrency is capped at that number, and every other parallel stream and default `CompletableFuture` in the JVM gets starved. Use virtual threads (`newVirtualThreadPerTaskExecutor`, or a structured scope) for I/O fan-out. (Lessons 20 and 21)

</details>

**28. Explain.** In a `RecursiveTask`, why write `left.fork(); right.compute(); left.join();` and not `left.fork(); left.join(); right.compute();`?

<details>
<summary>Reveal answer</summary>

The second order waits for the left half before starting the right half, so the two run one after the other. The first order computes the right half on the current thread *while* the left half runs somewhere else. (Lesson 19)

</details>

**29. Explain.** A team switches its service from a 200-thread pool to virtual threads. Throughput goes up, and then the database starts refusing connections. What happened, and what's the fix?

<details>
<summary>Reveal answer</summary>

The 200-thread pool was quietly limiting concurrency to 200. With virtual threads, many more requests reach the database at once. Limit the resource itself: size the connection pool properly (its waiting callers are cheap virtual threads), or add a `Semaphore`. Never pool the virtual threads. (Lesson 21)

</details>

**30. Explain.** With `StructuredTaskScope.open()`, subtask B fails while subtask A is still running. What happens, and why is that better than two plain `Future`s?

<details>
<summary>Reveal answer</summary>

The scope cancels A by interrupting it, and `join()` throws `FailedException` with B's exception as the cause. With plain `Future`s, A would keep running after the caller gave up, wasting a thread and resources. A structured scope guarantees that no subtask outlives the block. (Lesson 22)

</details>

---

## Scoring

| Score | What it means |
|---|---|
| 27–30 | You can review concurrent code with confidence. Try the capstone extensions (Lesson 25). |
| 20–26 | Solid. Go back to the lessons named in the answers you missed. |
| 12–19 | The basics are there. Re-run the Part 2 and Part 3 samples and redo their exercises. |
| Below 12 | Restart from Lesson 05. Races and visibility are the foundation for everything after them. |

---

## Where to go next

- **Jay Wang, *Java Concurrency and Parallelism*.** Chapters 4–5 take these tools into cloud patterns, and later chapters cover big data and microservices.
- **Goetz et al., *Java Concurrency in Practice*.** The classic book on the memory model and designing thread-safe classes. It's older than virtual threads, but its principles still apply.
- **The `java.util.concurrent` package Javadoc.** Its "Memory Consistency Properties" section lists every happens-before guarantee.
- **JEP 444 (virtual threads), JEP 491 (no pinning in `synchronized`), JEP 505 (structured concurrency) and JEP 506 (scoped values).**

[Back to the course index](../README.md)
