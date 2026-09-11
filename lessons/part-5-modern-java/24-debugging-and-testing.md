# Lesson 24: Debugging and Testing Concurrent Code

## What you'll learn

- How to read platform and virtual thread dumps, and why `Thread.print` doesn't show virtual threads
- How Java Flight Recorder (JFR) shows lock contention and virtual-thread pinning
- How to write stress tests that make race conditions show up, using latches as a starting gun
- What jcstress is, and when you need it

---

## Why this matters

Concurrency bugs are rare, depend on timing, and often disappear when you watch them. Adding a `println` changes the timing, a debugger's breakpoints serialize threads, and the bug stops happening. You need tools that observe a running JVM *without* changing its behaviour, and tests designed to *provoke* bad interleavings instead of hoping for them.

---

## Diagnosing a running JVM

### Symptom → first tool

| Symptom | First thing to do |
|---|---|
| Hangs, never finishes | Thread dump. Look for `BLOCKED`/`WAITING` threads and "Found one Java-level deadlock". |
| Slow under load, CPU low | Thread dump or JFR. Threads are waiting on locks, pools or I/O. |
| Slow under load, CPU high | JFR CPU profile. Look for hot loops, CAS retry storms or GC. |
| Wrong results sometimes | Code review ("what protects this state?"), then a stress test |
| Memory grows | Heap dump. Look for unbounded queues and `ThreadLocal` leaks. |

### Thread dumps, again

In Lesson 00 you used `jcmd <pid> Thread.print`. It lists **platform threads only**. A service with 10,000 virtual threads blocked on something shows you... the carriers, and nothing about your tasks.

For virtual threads, use the newer dump format:

```bash
jcmd <pid> Thread.dump_to_file -format=json dump.json
```

`HangingService.java` leaks two permits of a two-permit "connection pool". Every request after that hangs:

```java
Semaphore connectionPool = new Semaphore(2);

void main() throws InterruptedException {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        executor.submit(() -> leakyRequest(1));
        executor.submit(() -> leakyRequest(2));
        Thread.sleep(100);
        for (int i = 3; i <= 5; i++) {
            int requestId = i;
            executor.submit(() -> goodRequest(requestId));
        }
    }
}

Void leakyRequest(int requestId) throws InterruptedException {
    connectionPool.acquire();
    IO.println("request " + requestId + " took a connection and never released it");
    return null;
}

Void goodRequest(int requestId) throws InterruptedException {
    connectionPool.acquire();
    try {
        IO.println("request " + requestId + " working");
        return null;
    } finally {
        connectionPool.release();
    }
}
```

```
request 2 took a connection and never released it
request 1 took a connection and never released it
```

Then it hangs forever. `jcmd <pid> Thread.print | grep goodRequest` finds nothing. The JSON dump shows each stuck virtual thread, grouped by the executor that owns it:

```json
{
  "tid": "32",
  "virtual": true,
  "name": "",
  "state": "WAITING",
  "parkBlocker": {
    "object": "java.util.concurrent.Semaphore$NonfairSync@42ce9fb7"
  },
  "stack": [
    "java.base/java.lang.VirtualThread.park(VirtualThread.java:738)",
    "java.base/java.lang.System$1.parkVirtualThread(System.java:2284)",
    "java.base/java.util.concurrent.locks.LockSupport.park(LockSupport.java:221)",
    "java.base/java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:790)",
    "java.base/java.util.concurrent.locks.AbstractQueuedSynchronizer.acquireSharedInterruptibly(AbstractQueuedSynchronizer.java:1139)",
    "java.base/java.util.concurrent.Semaphore.acquire(Semaphore.java:318)",
    "HangingService.goodRequest(HangingService.java:22)",
    "HangingService.lambda$main$2(HangingService.java:10)",
    ...
  ]
}
```

The answer is right there. The thread is `WAITING`, its `parkBlocker` is a `Semaphore`, and it is stuck at `goodRequest` line 22, the `acquire()`. Three such entries, all on the same semaphore, point straight at a permit leak. Reading dumps is pattern matching:

| Pattern in the dump | Likely cause |
|---|---|
| Many threads `BLOCKED` on the same monitor | A hot lock, or a slow operation inside `synchronized` |
| "Found one Java-level deadlock" | Lock-ordering deadlock (Lesson 09) |
| Many threads `WAITING` in `Semaphore.acquire` / a connection pool | Exhausted resource or a permit leak |
| Many threads parked in `CountDownLatch.await` | A `countDown()` that never happens (not in a `finally`?) |
| Pool threads all `RUNNABLE` in the same socket read | A slow downstream service, and no timeouts |

Take **two or three dumps a few seconds apart**. Threads that haven't moved between dumps are the stuck ones.

### Java Flight Recorder

JFR is a low-overhead event recorder built into the JVM, safe to leave running in production. Start a program with it:

```bash
java -XX:StartFlightRecording=duration=30s,filename=rec.jfr MyApp.java
```

or attach it to a running JVM:

```bash
jcmd <pid> JFR.start duration=30s filename=rec.jfr
```

Then look at the events:

```bash
jfr print --events jdk.JavaMonitorEnter rec.jfr       # threads that waited for a synchronized lock
jfr print --events jdk.ThreadPark rec.jfr             # time parked in j.u.c locks, latches, queues
jfr print --events jdk.VirtualThreadPinned rec.jfr    # virtual threads that blocked while pinned
jfr summary rec.jfr                                   # which events were recorded
```

Or open `rec.jfr` in JDK Mission Control for graphs. `jdk.VirtualThreadPinned` is the tool for the pinning problem from Lesson 21. The event is enabled by default and records pins longer than 20 ms.

---

## Testing concurrent code

### Test the logic without threads first

Most of a concurrent class is ordinary logic: what happens on deposit, what happens when a buffer is full. Test that single-threaded, like any other code. Then add a small number of focused **concurrency tests** for the thread-safety claims.

### A stress test with a starting gun

A race only shows up when threads really overlap. If you start threads one at a time, the first may finish before the last one starts. A `CountDownLatch(1)` releases them all at once (Lesson 14), and repeating the round many times gives the bad interleaving many chances to happen.

`StressTest.java`:

```java
interface Counter {
    void increment();
    int value();
}

class UnsafeCounter implements Counter {
    private int value;
    public void increment() { value++; }
    public int value() { return value; }
}

class AtomicCounter implements Counter {
    private final AtomicInteger value = new AtomicInteger();
    public void increment() { value.incrementAndGet(); }
    public int value() { return value.get(); }
}

void main() throws InterruptedException {
    IO.println("UnsafeCounter failed " + stress(UnsafeCounter::new) + " of 200 rounds");
    IO.println("AtomicCounter failed " + stress(AtomicCounter::new) + " of 200 rounds");
}

int stress(Supplier<Counter> newCounter) throws InterruptedException {
    int threads = 8;
    int incrementsPerThread = 1_000;
    int failures = 0;

    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
        for (int round = 0; round < 200; round++) {
            Counter counter = newCounter.get();
            var startingGun = new CountDownLatch(1);
            var finished = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startingGun.await();
                        for (int i = 0; i < incrementsPerThread; i++) {
                            counter.increment();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startingGun.countDown();
            if (!finished.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("round " + round + " timed out, possible deadlock");
            }
            if (counter.value() != threads * incrementsPerThread) {
                failures++;
            }
        }
    }
    return failures;
}
```

One run:

```
UnsafeCounter failed 40 of 200 rounds
AtomicCounter failed 0 of 200 rounds
```

Only 1 round in 5 caught the unsafe counter. A single run would have passed 80% of the time. The techniques that make this work:

- **A starting gun** so threads truly overlap.
- **Many rounds**, because each round is another chance to hit the bad interleaving.
- **More threads than cores**, which forces context switches in the middle of operations.
- **`await` with a timeout**, so that a deadlock fails the test instead of hanging your build.
- **Assertions after `join`/`await`**, since the latch's happens-before makes all results visible (Lesson 07).

In a JUnit test, the same shape goes inside a `@Test` method with an `assertThat(failures).isZero()`. Awaitility (`await().atMost(...).until(...)`) is useful for "eventually true" checks, and is much better than `Thread.sleep` in tests.

A passing stress test is **evidence, not proof**. The absence of failures in 200 rounds on your laptop says nothing certain about production hardware.

### jcstress

For code where you need real confidence, such as lock-free algorithms, custom synchronizers or anything relying on `volatile` or `final` semantics, the OpenJDK project has **jcstress** (the Java Concurrency Stress tests harness). You describe a few actors that run at the same time and the outcomes you consider acceptable, and it runs them billions of times with a lot of variation in timing. The JDK developers use it to test the JVM itself. It's overkill for application code, but it's the right tool if you ever write your own `AtomicReference`-based data structure.

### Stay deterministic where you can

- **Inject the executor.** Code that accepts an `Executor` can be given a direct executor (`Runnable::run`) in unit tests, so the logic runs step by step on the test thread.
- **Inject the clock** (`java.time.Clock`) instead of calling `System.currentTimeMillis()`, so timeouts and schedules can be tested without sleeping.
- **Never `Thread.sleep` to wait for a result in a test.** Use `join`, latches, `Future.get(timeout)` or Awaitility.

---

## Try it yourself

1. Run `HangingService`, take both a `Thread.print` dump and a JSON dump, and compare them.
2. Record `Deadlock.java` from Lesson 09 with JFR and find the `jdk.JavaMonitorEnter` events.
3. Change `StressTest` to 2 threads and 10 increments per thread. How often does the unsafe counter fail now? What does this tell you about small tests?
4. Write a stress test for Lesson 05's `CheckThenAct` ticket office and its fixed version from Lesson 06.

---

## Common mistakes

- **Debugging races with `println` or breakpoints.** Both change the timing and hide the bug.
- **Using `Thread.print` on a virtual-thread application.** Use the JSON thread dump.
- **Stress tests without a starting gun.** The threads barely overlap.
- **Tests that `sleep` and hope.** They're slow and flaky. Use latches and timeouts.
- **Treating a green stress test as proof.** It's a probability, not a guarantee.
- **Waiting with no timeout in tests.** A deadlock hangs the build forever.

---

## Check your understanding

**1. Your Spring Boot service uses virtual threads and hangs. `jcmd <pid> Thread.print` shows nothing interesting. What next?**

<details>
<summary>Reveal answer</summary>

`Thread.print` shows platform threads only. Take a JSON thread dump with `jcmd <pid> Thread.dump_to_file -format=json dump.json`, which includes virtual threads grouped by their thread container, with their states and stacks.

</details>

**2. A thread dump shows 200 threads `WAITING` in `HikariPool.getConnection`. What's your hypothesis?**

<details>
<summary>Reveal answer</summary>

The database connection pool is exhausted. Either connections are leaking (not closed or returned), queries are slow, or too many concurrent requests are hitting a small pool, which is common after switching to virtual threads. Check which threads hold connections and what they're doing.

</details>

**3. Why start threads with a `CountDownLatch(1)` in a stress test?**

<details>
<summary>Reveal answer</summary>

It releases all threads at the same instant, which maximises how much they overlap. Started one by one, early threads may finish before later ones begin, and the race never gets a chance to happen.

</details>

**4. Your stress test passed 1,000 rounds. Is the class thread-safe?**

<details>
<summary>Reveal answer</summary>

Not necessarily. It's evidence, not proof, because different hardware, JIT decisions or load can produce interleavings your test never hit. Thread-safety has to be argued from the code, by knowing what protects each piece of shared state, and supported by testing.

</details>

**5. Which JFR event tells you a virtual thread was pinned?**

<details>
<summary>Reveal answer</summary>

`jdk.VirtualThreadPinned`. It's recorded when a virtual thread blocks while pinned to its carrier for longer than the threshold (20 ms by default).

</details>

---

## Recap

- Hangs: take a thread dump, and use the JSON format for virtual threads. Take several and compare them.
- Contention and pinning: record with JFR and look at `JavaMonitorEnter`, `ThreadPark` and `VirtualThreadPinned`.
- Tests: check the logic on one thread first, then stress with a starting gun, many rounds and timeouts.
- jcstress is for lock-free and memory-model-sensitive code.
- Inject executors and clocks to keep tests deterministic, and never sleep in a test.

**Next: [Lesson 25, Capstone](25-capstone.md)**
