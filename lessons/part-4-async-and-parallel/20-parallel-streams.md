# Lesson 20: Parallel Streams

## What you'll learn

- How `.parallel()` turns a stream into a Fork/Join computation
- When a parallel stream is faster, and the common cases where it's slower
- The three ways to break a parallel stream: side effects, relying on order, and blocking work
- How to run a parallel stream in a pool other than the common pool

---

## Why this matters

`.parallel()` is the easiest parallelism in Java, one method call, and that's exactly why it's misused. People add it hoping for a speed-up and get a slowdown, wrong results, or a server where one slow parallel stream stalls every other parallel stream in the JVM.

Everything from Lesson 19 applies directly: a parallel stream **is** a Fork/Join job running in the common pool. A `Spliterator` does the splitting and the stream's operations are the work.

---

## The concept

```java
long primes = IntStream.range(2, 5_000_000)
        .parallel()
        .filter(this::isPrime)
        .count();
```

Under the hood, the source is split into chunks by its **`Spliterator`**. Each chunk is filtered and counted in a Fork/Join task, and the partial counts are combined.

### When parallel pays off

A useful rule of thumb from the JDK stream designers is **N × Q**: the number of elements times the cost of processing one. When that product is large, parallel may help. When it's small, the overhead dominates.

| Factor | Helps parallel | Hurts parallel |
|---|---|---|
| Cost per element (Q) | Expensive (prime testing, parsing, hashing) | Trivial (`x + 1`) |
| Number of elements (N) | Large (hundreds of thousands or more) | Small |
| Source | Splits cheaply: arrays, `ArrayList`, `IntStream.range` | Splits badly: `LinkedList`, `Stream.iterate`, I/O-backed sources |
| Operations | Stateless: `map`, `filter` | Order-dependent: `limit`, `findFirst`, `sorted` on ordered streams |
| Data | Primitive streams (`IntStream`) | Boxing (`Stream<Integer>`) adds memory traffic |
| Work type | CPU-bound | Blocking I/O |

---

## Hands-on

### 1. Fast, useless and slower

`ParallelStreams.java`:

```java
void main() {
    IO.println("cores: " + Runtime.getRuntime().availableProcessors());

    time("sequential primes", () -> IntStream.range(2, 5_000_000).filter(this::isPrime).count());
    time("parallel primes  ", () -> IntStream.range(2, 5_000_000).parallel().filter(this::isPrime).count());

    time("sequential tiny sum", () -> IntStream.range(0, 1_000).sum());
    time("parallel tiny sum  ", () -> IntStream.range(0, 1_000).parallel().sum());

    List<Integer> boxed = IntStream.range(0, 5_000_000).boxed().collect(Collectors.toCollection(LinkedList::new));
    time("sequential LinkedList", () -> boxed.stream().mapToLong(Integer::longValue).sum());
    time("parallel LinkedList  ", () -> boxed.parallelStream().mapToLong(Integer::longValue).sum());
}

void time(String label, Supplier<Number> work) {
    work.get();
    long start = System.nanoTime();
    Number result = work.get();
    IO.println(String.format("%s: %6.1f ms  (result %s)", label, (System.nanoTime() - start) / 1e6, result));
}

boolean isPrime(int n) {
    for (int divisor = 2; (long) divisor * divisor <= n; divisor++) {
        if (n % divisor == 0) {
            return false;
        }
    }
    return true;
}
```

On a 12-core machine:

```
cores: 12
sequential primes: 3769.3 ms  (result 348513)
parallel primes  :  638.9 ms  (result 348513)
sequential tiny sum:    0.3 ms  (result 499500)
parallel tiny sum  :    0.6 ms  (result 499500)
sequential LinkedList:   65.4 ms  (result 12499997500000)
parallel LinkedList  :  104.2 ms  (result 12499997500000)
```

(`time` runs each job once as a warm-up and then times the second run. That's still a rough measurement, so use JMH for real benchmarks.)

- **Primes:** expensive work per element on a source that splits well. About 6 times faster.
- **Tiny sum:** 1,000 cheap additions. Parallel is *twice as slow*, because splitting and coordination cost more than the work.
- **`LinkedList`:** can't be split without walking it node by node, and every element is a boxed `Integer`. Parallel is *slower*.

Measure before you add `.parallel()`, and keep it only if the numbers say so.

### 2. Ways to break a parallel stream

`ParallelPitfalls.java`:

```java
void main() throws Exception {
    List<Integer> unsafe = new ArrayList<>();
    try {
        IntStream.range(0, 100_000).parallel().forEach(unsafe::add);
        IO.println("side-effect list size: " + unsafe.size() + " (expected 100000)");
    } catch (ArrayIndexOutOfBoundsException e) {
        IO.println("side-effect list crashed: " + e);
    }

    List<Integer> safe = IntStream.range(0, 100_000).parallel().boxed().toList();
    IO.println("collected list size:   " + safe.size());

    IO.print("forEach order:        ");
    IntStream.range(0, 10).parallel().forEach(n -> IO.print(n + " "));
    IO.println("");
    IO.print("forEachOrdered order: ");
    IntStream.range(0, 10).parallel().forEachOrdered(n -> IO.print(n + " "));
    IO.println("");

    try (var customPool = new ForkJoinPool(2)) {
        long count = customPool.submit(() ->
                IntStream.range(0, 10).parallel()
                        .mapToObj(n -> Thread.currentThread().getName())
                        .distinct()
                        .count()).get();
        IO.println("threads used inside custom pool(2): " + count);
    }
}
```

One run:

```
side-effect list crashed: java.lang.ArrayIndexOutOfBoundsException
collected list size:   100000
forEach order:        6 5 2 8 1 9 4 3 7 0 
forEachOrdered order: 0 1 2 3 4 5 6 7 8 9 
threads used inside custom pool(2): 2
```

**Side effects.** `forEach(unsafe::add)` is Lesson 05's `ArrayList` race again. Sometimes it crashes, and sometimes it silently loses elements. Streams are meant to *produce* results with `toList()`, `collect`, `reduce` or `sum`, not to modify outside state. A collector gives each thread its own container and merges them safely.

**Order.** `forEach` on a parallel stream runs in whatever order the threads happen to reach the elements. `forEachOrdered` keeps the order, but it limits how much can run in parallel. The same applies to `findFirst` compared with `findAny`, and to `limit` on ordered streams. If you don't need order, say so with `unordered()` or `findAny()`.

**Choosing the pool.** A parallel stream runs in the pool of the thread that starts it. Started from inside a `ForkJoinPool`, it uses that pool. This behaviour isn't documented as a guarantee in the stream API, but it's widely relied on. It isolates heavy parallel work so it can't starve the common pool.

### Blocking in parallel streams

```java
urls.parallelStream()
    .map(this::httpGet)
    .toList();
```

This is one of the most common misuses. `httpGet` blocks. The common pool has cores − 1 threads, so on a 4-core container you get three calls at a time. Meanwhile, every other parallel stream and default `CompletableFuture` in the application is stuck behind them. For I/O fan-out, use virtual threads (next lesson).

### Reductions must be associative

`reduce(identity, op)` in parallel combines partial results in an unpredictable grouping, so `op` must be **associative**, and `identity` must truly be neutral:

```java
IntStream.range(1, 5).parallel().reduce(0, Integer::sum);           // 10, always
IntStream.range(1, 5).parallel().reduce(0, (a, b) -> a - b);         // printed 0 on a 12-core machine; sequentially it's -10
IntStream.range(1, 5).parallel().reduce(5, Integer::sum);           // printed 30, not 15: the identity 5 was added once per chunk
```

Subtraction isn't associative, so the answer depends on how the stream happened to be split, and that changes with core count and input size.

---

## Try it yourself

1. Run the `reduce` examples above sequentially and in parallel, with `range(1, 5)` and `range(1, 1_000)`. Which results change?
2. Replace the `LinkedList` in `ParallelStreams` with an `ArrayList`. What changes, and why?
3. Parse 1,000,000 strings to integers with `Integer.parseInt`, sequentially and in parallel. Is it worth it?
4. Use `customPool` to run the primes example with parallelism 1, 2, 4 and 8, and plot the times.

---

## Common mistakes

- **Adding `.parallel()` without measuring.** It's often slower.
- **Side effects in `forEach`/`map`**, such as adding to a shared list or updating a counter. Collect instead.
- **Relying on encounter order** with `forEach`. Use `forEachOrdered`, or accept that there is no order.
- **Blocking I/O in parallel streams.** It starves the common pool for the whole JVM.
- **Non-associative reductions or non-neutral identities.** The result changes with the split.
- **Parallel streams inside request handlers on a busy server.** All requests share the same few common-pool threads.

---

## Check your understanding

**1. Which pool runs a parallel stream by default, and how many threads does it have?**

<details>
<summary>Reveal answer</summary>

`ForkJoinPool.commonPool()`, with about (cores − 1) threads. The calling thread also joins in. It is shared across the entire JVM.

</details>

**2. Why can a parallel stream over a `LinkedList` be slower than a sequential one?**

<details>
<summary>Reveal answer</summary>

A `LinkedList` can't be split cheaply. The `Spliterator` has to walk the nodes, and the boxed elements are scattered around memory. The splitting and coordination cost more than the parallelism saves.

</details>

**3. What's wrong with `list.parallelStream().forEach(result::add)` where `result` is an `ArrayList`?**

<details>
<summary>Reveal answer</summary>

Several threads call `ArrayList.add` at the same time. That's a race, and it can lose elements or throw. Use `.toList()` or `.collect(...)` instead.

</details>

**4. Is `parallelStream().map(this::callRestApi)` a good idea?**

<details>
<summary>Reveal answer</summary>

No. It blocks common-pool threads on I/O, which limits you to about cores − 1 concurrent calls and starves every other user of the common pool. Use virtual threads, or an executor sized for I/O.

</details>

**5. Name three conditions that make a parallel stream likely to help.**

<details>
<summary>Reveal answer</summary>

Any three of these: a large number of elements; expensive, CPU-bound work per element; a source that splits cheaply (arrays, `ArrayList`, ranges); stateless operations with no side effects; no ordering requirement; primitive streams instead of boxed ones.

</details>

---

## Recap

- A parallel stream is a Fork/Join job in the common pool.
- It helps with lots of elements, costly CPU work per element and sources that split well. Otherwise it's often slower.
- No side effects, no reliance on order, no blocking I/O, and only associative reductions.
- Measure every time.

## End of Part 4

You can now schedule work, compose asynchronous pipelines, and split CPU-bound work across cores. Part 5 covers the biggest change to Java concurrency in two decades: virtual threads and structured concurrency.

**Next: [Lesson 21, Virtual threads](../part-5-modern-java/21-virtual-threads.md)**
