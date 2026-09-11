<div align="center">

# Java Concurrency & Multithreading

### From your first `Thread` to virtual threads and structured concurrency

**27 lessons · 87 runnable programs · a quiz in every lesson · one final exam**

[![Java](https://img.shields.io/badge/Java-25_LTS-f0196a?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![Build](https://img.shields.io/badge/build_tool-none_needed-12121f?style=for-the-badge&logo=apachemaven&logoColor=white)](#run-any-sample-in-one-command)
[![Lessons](https://img.shields.io/badge/lessons-27-f0196a?style=for-the-badge&logo=bookstack&logoColor=white)](lessons/README.md)
[![Samples](https://img.shields.io/badge/samples-87_verified-12121f?style=for-the-badge&logo=checkmarx&logoColor=white)](#every-sample-is-verified)

[**Start the course →**](lessons/README.md) &nbsp;·&nbsp; [Final exam](lessons/part-6-final-exam/26-final-exam.md) &nbsp;·&nbsp; [Capstone](lessons/part-5-modern-java/25-capstone.md)

</div>

---

## Why this course

Most concurrency tutorials start at `ExecutorService` and never explain why `counter++` is broken.

This course builds from the ground up. You **create raw threads**, **break things on purpose** (lost updates, stale reads, deadlocks), and only then learn the tool Java provides for each problem. By the time you reach virtual threads and structured concurrency, you know exactly what problem they solve.

```text
Expected: 40000
Actual:   29515     ← Lesson 05. By Lesson 06 you know why, and how to fix it.
```

<table>
<tr>
<td width="33%" valign="top">

### Mental models first
Every concept starts with **why it matters**, then a diagram, then the mechanism. You learn to ask the one question that matters: *what protects this state?*

</td>
<td width="33%" valign="top">

### Run, don't read
Every sample is **one self-contained file**. No Maven, no project setup, no IDE: `java RaceCondition.java`, and you see it for yourself.

</td>
<td width="33%" valign="top">

### Check yourself
Every lesson ends with exercises, the **common mistakes that bite in production**, and a quiz with hidden answers. A 30-question exam closes the course.

</td>
</tr>
</table>

---

## The path

```mermaid
flowchart LR
    P0["Part 0<br/>Setup"] --> P1["Part 1<br/>Threads"]
    P1 --> P2["Part 2<br/>Shared state"]
    P2 --> P3["Part 3<br/>j.u.concurrent"]
    P3 --> P4["Part 4<br/>Async & parallel"]
    P4 --> P5["Part 5<br/>Modern Java"]
    P5 --> P6["Part 6<br/>Final exam"]

    style P0 fill:#12121f,stroke:#f0196a,color:#fff
    style P1 fill:#12121f,stroke:#f0196a,color:#fff
    style P2 fill:#12121f,stroke:#f0196a,color:#fff
    style P3 fill:#12121f,stroke:#f0196a,color:#fff
    style P4 fill:#12121f,stroke:#f0196a,color:#fff
    style P5 fill:#12121f,stroke:#f0196a,color:#fff
    style P6 fill:#f0196a,stroke:#f0196a,color:#fff
```

<details open>
<summary><b>Part 0 · Setup</b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 00 | [Running the samples](lessons/part-0-setup/00-running-the-samples.md) | Single-file Java 25 programs, `void main()`, and reading a thread dump with `jcmd` |

</details>

<details open>
<summary><b>Part 1 · Threads from scratch</b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 01 | [Concurrency vs parallelism](lessons/part-1-threads-from-scratch/01-concurrency-vs-parallelism.md) | Processes vs threads, the shared heap, and I/O-bound vs CPU-bound work |
| 02 | [Your first thread](lessons/part-1-threads-from-scratch/02-your-first-thread.md) | `Runnable`, `start()` vs `run()`, and why output interleaves |
| 03 | [The thread lifecycle](lessons/part-1-threads-from-scratch/03-thread-lifecycle.md) | The six states, `sleep`, `join`, and daemon threads |
| 04 | [Interruption](lessons/part-1-threads-from-scratch/04-interruption.md) | Stopping threads politely, and never swallowing `InterruptedException` |

</details>

<details open>
<summary><b>Part 2 · Shared state</b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 05 | [Race conditions](lessons/part-2-shared-state/05-race-conditions.md) | Lost updates, check-then-act, and why tests miss races |
| 06 | [`synchronized`](lessons/part-2-shared-state/06-synchronized.md) | Intrinsic locks, reentrancy, and what never to lock on |
| 07 | [Visibility & `volatile`](lessons/part-2-shared-state/07-visibility-and-volatile.md) | The Java Memory Model, happens-before, and safe publication |
| 08 | [`wait` & `notify`](lessons/part-2-shared-state/08-wait-and-notify.md) | Guarded blocks, and a bounded buffer built by hand |
| 09 | [Deadlock, livelock, starvation](lessons/part-2-shared-state/09-liveness.md) | The Coffman conditions, lock ordering, and detection |

</details>

<details open>
<summary><b>Part 3 · <code>java.util.concurrent</code></b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 10 | [Executors & thread pools](lessons/part-3-java-util-concurrent/10-executors.md) | `ThreadPoolExecutor`, bounded queues, and rejection policies |
| 11 | [`Callable` & `Future`](lessons/part-3-java-util-concurrent/11-callable-and-future.md) | Results, failures, timeouts, and `CompletionService` |
| 12 | [Atomics & CAS](lessons/part-3-java-util-concurrent/12-atomics.md) | Compare-and-set, `LongAdder`, and `AtomicReference` |
| 13 | [Explicit locks](lessons/part-3-java-util-concurrent/13-explicit-locks.md) | `ReentrantLock`, `tryLock`, `Condition`, and `StampedLock` |
| 14 | [Synchronizers](lessons/part-3-java-util-concurrent/14-synchronizers.md) | Latches, barriers, semaphores, and phasers |
| 15 | [Concurrent collections](lessons/part-3-java-util-concurrent/15-concurrent-collections.md) | `ConcurrentHashMap`, `BlockingQueue`, and copy-on-write |
| 16 | [Sharing without locks](lessons/part-3-java-util-concurrent/16-immutability-threadlocal-scopedvalue.md) | Immutability, `ThreadLocal`, and `ScopedValue` |

</details>

<details open>
<summary><b>Part 4 · Async & parallel</b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 17 | [Scheduled executors](lessons/part-4-async-and-parallel/17-scheduled-executors.md) | Fixed rate vs fixed delay, and the silent death of periodic tasks |
| 18 | [`CompletableFuture`](lessons/part-4-async-and-parallel/18-completablefuture.md) | Compose, combine, `allOf`, errors, and timeouts |
| 19 | [Fork/Join](lessons/part-4-async-and-parallel/19-fork-join.md) | Divide and conquer, work stealing, and granularity |
| 20 | [Parallel streams](lessons/part-4-async-and-parallel/20-parallel-streams.md) | When they help, when they hurt, and how they break |

</details>

<details open>
<summary><b>Part 5 · Modern Java</b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 21 | [Virtual threads](lessons/part-5-modern-java/21-virtual-threads.md) | Mounting, carriers, pinning, and when *not* to use them |
| 22 | [Structured concurrency](lessons/part-5-modern-java/22-structured-concurrency.md) | `StructuredTaskScope`, fail-fast, and joiners *(preview)* |
| 23 | [Concurrency patterns](lessons/part-5-modern-java/23-concurrency-patterns.md) | Producer-consumer, bulkhead, scatter-gather, and circuit breaker |
| 24 | [Debugging & testing](lessons/part-5-modern-java/24-debugging-and-testing.md) | JSON thread dumps, JFR, and stress tests |
| 25 | [Capstone](lessons/part-5-modern-java/25-capstone.md) | A concurrent word counter that uses it all |

</details>

<details open>
<summary><b>Part 6 · Final exam</b></summary>

| # | Lesson | You'll learn |
|:-:|---|---|
| 26 | [Final exam](lessons/part-6-final-exam/26-final-exam.md) | 30 questions: predict, spot the bug, pick the tool, explain |

</details>

---

## Run any sample in one command

All you need is **JDK 25**.

```bash
java -version            # openjdk version "25" ...
java RaceCondition.java  # that's it
```

Samples use Java 25's compact source files: no `class` declaration, no `public static`, and the `java.base` imports come for free.

```java
int counter = 0;

void main() throws InterruptedException {
    Thread first = new Thread(() -> { for (int i = 0; i < 1_000_000; i++) counter++; });
    Thread second = new Thread(() -> { for (int i = 0; i < 1_000_000; i++) counter++; });
    first.start(); second.start();
    first.join();  second.join();
    IO.println("Expected 2000000, got " + counter);
}
```

> [!NOTE]
> Lesson 22 uses `StructuredTaskScope`, which is still a **preview** API in Java 25. Run those samples with `java --enable-preview File.java`.

---

## How every lesson is built

```text
┌───────────────────────┐
│  What you'll learn    │  3–4 concrete outcomes
│  Why this matters     │  the real problem, before any mechanism
│  The concept          │  explanation and diagrams
│  Hands-on             │  complete programs with real output
│  Try it yourself      │  exercises, no answers given
│  Common mistakes      │  the ones that bite in production
│  Check understanding  │  quiz with click-to-reveal answers
│  Recap                │  the lesson in a few bullets
└───────────────────────┘
```

A taste of the quizzes:

> **Is this safe if `cache` is a `ConcurrentHashMap`?**
> ```java
> if (!cache.containsKey(key)) {
>     cache.put(key, expensiveLoad(key));
> }
> ```
> <details>
> <summary>Reveal answer</summary>
>
> No. It's check-then-act. Each call is thread-safe on its own, but two threads can both see "absent" and both load. Use `cache.computeIfAbsent(key, this::expensiveLoad)`.
>
> </details>

---

## Every sample is verified

Every program in the course was run on JDK 25, and every output block in the lessons comes from a real run, not from memory. Where output changes from run to run (thread interleaving, timings), the lesson says so.

| Java 25 feature | Status | Where |
|---|:-:|---|
| Compact source files & `IO.println` | Final | Every lesson |
| Virtual threads | Final | Lessons 21–25 |
| No pinning inside `synchronized` (JEP 491) | Final | Lesson 21 |
| `ScopedValue` (JEP 506) | Final | Lessons 16, 22 |
| `StructuredTaskScope` (JEP 505) | Preview | Lesson 22 |

---

## Repository layout

```text
concurreny-multithreading/
├── lessons/                         ← the course (start here)
│   ├── README.md                    ← course index
│   ├── part-0-setup/
│   ├── part-1-threads-from-scratch/
│   ├── part-2-shared-state/
│   ├── part-3-java-util-concurrent/
│   ├── part-4-async-and-parallel/
│   ├── part-5-modern-java/
│   └── part-6-final-exam/
├── docs/
│   └── map-internals.md             ← deep dive: HashMap → ConcurrentHashMap internals
└── src/main/java/org/javaguy/       ← demos written while learning
    ├── callable/                    ← Callable, Future, CompletionService
    ├── concurrentcollections/       ← ConcurrentHashMap demo + CC.MD guide
    ├── executors/                   ← first ExecutorService
    ├── multithread/                 ← coloured threads running side by side
    └── synchronizedkey/             ← synchronized methods vs blocks
```

---

## Further reading

- **Jay Wang**, *Java Concurrency and Parallelism* (Packt). Chapters 1–5 cover the same ground as this course, and later chapters take it into cloud patterns.
- **Brian Goetz et al.**, *Java Concurrency in Practice*. The classic on the memory model and designing thread-safe classes.
- The [`java.util.concurrent` package docs](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/package-summary.html), especially the *Memory Consistency Properties* section.
- JEPs: [444 · Virtual threads](https://openjdk.org/jeps/444) · [491 · No pinning in `synchronized`](https://openjdk.org/jeps/491) · [505 · Structured concurrency](https://openjdk.org/jeps/505) · [506 · Scoped values](https://openjdk.org/jeps/506)

---

<div align="center">

**Made by [@your_javaguy](https://www.youtube.com/@your_javaguy)**

If this helped you finally understand `volatile`, give the repo a star. 
[**Start with Lesson 00 →**](lessons/part-0-setup/00-running-the-samples.md)

</div>
