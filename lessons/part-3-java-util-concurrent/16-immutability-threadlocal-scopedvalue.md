# Lesson 16: Sharing Safely Without Locks

## What you'll learn

- The three ways to make shared data safe without locking it: **don't mutate it**, **don't share it**, or **pass it down a call chain**
- How to write immutable classes with records, and the traps with collections inside them
- `ThreadLocal`: one value per thread, and why it leaks in thread pools
- `ScopedValue` (final in Java 25): the modern, leak-proof replacement for most `ThreadLocal` uses

---

## Why this matters

Every tool so far (`synchronized`, `volatile`, atomics, locks) manages *shared mutable* state. The best concurrency bug is the one you can't write, because there's nothing shared and mutable to race on.

Remember Lesson 05: a race needs data that is **shared** *and* **mutable**. Remove either one and the problem disappears.

| Strategy | Removes | Tool |
|---|---|---|
| Immutability | Mutable | Records, `final` fields, `List.copyOf` |
| Thread confinement | Shared | Local variables, `ThreadLocal` |
| Scoped binding | Shared *and* mutable | `ScopedValue` |

---

## The concept

### Immutable objects

An immutable object's state can't change after construction. So:

- Any number of threads can read it at once without locks.
- It can't be seen in a half-updated state.
- Thanks to the `final`-field guarantee from Lesson 07, it is safe to publish once construction has finished.

A class is immutable when all fields are `final`, the class can't be subclassed to add mutable state, `this` doesn't escape the constructor, and **every mutable object it holds is copied in and never handed out**. Records give you the first two for free. The last one is still up to you.

To "change" an immutable object, create a new one. `String`, `BigDecimal`, `LocalDate` and `List.of(...)` all work this way.

### `ThreadLocal`

A `ThreadLocal<T>` holds a **separate value for each thread**. `get()` returns the calling thread's own copy. It is used for:

- Per-thread instances of objects that aren't thread-safe (the classic example is `SimpleDateFormat`).
- Carrying context implicitly through a call stack, such as the current user, request ID or transaction. Spring's `SecurityContextHolder` and `@Transactional` use it this way.

Its problems:

- **Leaks in thread pools.** Pool threads live for the life of the application. A value you forget to `remove()` stays attached to that thread and **shows up in the next, unrelated task**.
- **Mutable and unbounded.** Any code can `set()` it at any time, so it's hard to know who changed what.
- **Expensive with virtual threads.** A million virtual threads means a million copies.

### `ScopedValue`

A `ScopedValue<T>` is bound to a value **for the duration of a method call**. Everything called from inside that scope can read it. When the scope ends, the binding is gone, automatically and always.

```java
ScopedValue.where(REQUEST_ID, "req-1").run(() -> handle());
```

| | `ThreadLocal` | `ScopedValue` |
|---|---|---|
| Lifetime | Until you call `remove()`, or the thread dies | Exactly the `run(...)` / `call(...)` block |
| Mutable | Yes, `set()` anywhere | No. Rebind in a nested scope if you need a different value. |
| Leak risk in pools | High | None |
| Cost with virtual threads | A copy per thread | Cheap |
| Inherited by structured subtasks (Lesson 22) | No (unless `InheritableThreadLocal`, which copies) | Yes, automatically |

`ScopedValue` became a final feature in Java 25 (JEP 506).

---

## Hands-on

### 1. Immutable value objects with records

`ImmutableMoney.java`:

```java
record Money(long cents, String currency) {
    Money {
        if (cents < 0) {
            throw new IllegalArgumentException("negative amount");
        }
    }

    Money plus(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch");
        }
        return new Money(cents + other.cents, currency);
    }
}

record Order(String id, List<String> items) {
    Order {
        items = List.copyOf(items);
    }
}

void main() {
    var price = new Money(1_000, "USD");
    var withTax = price.plus(new Money(160, "USD"));
    IO.println(price + " -> " + withTax);

    var mutableItems = new ArrayList<>(List.of("book"));
    var order = new Order("o-1", mutableItems);
    mutableItems.add("pen");
    IO.println(order);

    try {
        order.items().add("hacked");
    } catch (UnsupportedOperationException e) {
        IO.println("order items cannot be modified");
    }
}
```

```
Money[cents=1000, currency=USD] -> Money[cents=1160, currency=USD]
Order[id=o-1, items=[book]]
order items cannot be modified
```

- `plus` returns a **new** `Money`. `price` never changes, so any thread can hold it safely.
- **The record trap:** a record's fields are `final`, but `final` only fixes the *reference*. Without the `List.copyOf` line, `Order` would keep the caller's `ArrayList`, and the caller's later `add("pen")` would change your "immutable" order from outside. `List.copyOf` makes an unmodifiable copy (and skips the copy if the list is already unmodifiable).

With immutable objects plus an `AtomicReference` or `volatile` field (Lessons 07 and 12), you can build whole lock-free designs: build a new snapshot and swap the reference.

### 2. `ThreadLocal`: one instance per thread

`SimpleDateFormat` keeps internal state while it formats, so sharing one between threads produces garbage dates. A `ThreadLocal` gives each pool thread its own.

`ThreadLocalDemo.java`:

```java
import java.text.SimpleDateFormat;

ThreadLocal<SimpleDateFormat> formatter =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

void main() {
    try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
        for (int i = 0; i < 6; i++) {
            pool.submit(() -> {
                SimpleDateFormat own = formatter.get();
                IO.println(Thread.currentThread().getName()
                        + " uses formatter #" + System.identityHashCode(own)
                        + " -> " + own.format(new Date(0)));
            });
        }
    }
}
```

One run:

```
pool-1-thread-1 uses formatter #268358197 -> 1970-01-01
pool-1-thread-3 uses formatter #1045053322 -> 1970-01-01
pool-1-thread-3 uses formatter #1045053322 -> 1970-01-01
pool-1-thread-3 uses formatter #1045053322 -> 1970-01-01
pool-1-thread-1 uses formatter #268358197 -> 1970-01-01
pool-1-thread-2 uses formatter #1588189487 -> 1970-01-01
```

Three threads, three formatters, each reused by its own thread. (Today you'd just use `java.time.format.DateTimeFormatter`, which is immutable and thread-safe. That's the immutability strategy again.)

### 3. The `ThreadLocal` leak

`ThreadLocalLeak.java`:

```java
ThreadLocal<String> currentUser = new ThreadLocal<>();

void main() {
    try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
        pool.submit(() -> handleRequest("alice"));
        pool.submit(() -> handleRequest(null));

        pool.submit(() -> handleRequestSafely("bob"));
        pool.submit(() -> handleRequestSafely(null));
    }
}

void handleRequest(String user) {
    if (user != null) {
        currentUser.set(user);
    }
    IO.println("leaky handler sees user: " + currentUser.get());
}

void handleRequestSafely(String user) {
    try {
        if (user != null) {
            currentUser.set(user);
        }
        IO.println("safe handler sees user:  " + currentUser.get());
    } finally {
        currentUser.remove();
    }
}
```

```
leaky handler sees user: alice
leaky handler sees user: alice
safe handler sees user:  bob
safe handler sees user:  null
```

The second request was anonymous, but it ran on the same pool thread and **saw `alice`**. In a web server, that is one user's identity leaking into another user's request. The safe handler clears the value in `finally`, so the anonymous request after `bob` correctly sees `null`. Wrap every use of a `ThreadLocal` in `try` / `finally { remove(); }`.

### 4. `ScopedValue`: context that can't leak

`ScopedValueDemo.java`:

```java
static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

void main() throws InterruptedException {
    Thread first = Thread.ofVirtual().start(() -> ScopedValue.where(REQUEST_ID, "req-1").run(this::handle));
    Thread second = Thread.ofVirtual().start(() -> ScopedValue.where(REQUEST_ID, "req-2").run(this::handle));
    first.join();
    second.join();

    IO.println("outside any scope, bound? " + REQUEST_ID.isBound());
}

void handle() {
    log("handling request");
    saveToDatabase();
}

void saveToDatabase() {
    log("saving");
}

void log(String message) {
    IO.println("[" + REQUEST_ID.get() + "] " + message);
}
```

One run:

```
[req-1] handling request
[req-1] saving
[req-2] handling request
[req-2] saving
outside any scope, bound? false
```

- `log` reads the request ID with no parameter passed down through `handle` and `saveToDatabase`. That is the convenience `ThreadLocal` offered.
- Each thread sees its own binding.
- Outside the `run(...)` block the value is **unbound**. There's nothing to `remove()` and nothing to leak. `REQUEST_ID.get()` there would throw `NoSuchElementException`.
- There's no `set()`. To use a different value for part of the call, bind it again in a nested scope: `ScopedValue.where(REQUEST_ID, "req-1-retry").run(...)`.
- `Thread.ofVirtual()` is used here just to show that `ScopedValue` works with any kind of thread. Virtual threads are Lesson 21.
- Use `.call(...)` instead of `.run(...)` when the scoped code returns a value.

---

## Try it yourself

1. Remove `List.copyOf` from `Order` and show that the order changes from outside.
2. Add a `Map<String, Integer>` to a record and make it truly immutable.
3. In `ThreadLocalLeak`, swap the order of the two pairs of submissions and explain the new output.
4. Nest a second `ScopedValue.where(REQUEST_ID, "inner").run(...)` inside `handle()`. What does `log` print inside it, and after it returns?

---

## Common mistakes

- **"It's a record, so it's immutable."** Not if it holds a mutable `List`, `Map` or array. Copy them into unmodifiable collections.
- **Letting `this` escape the constructor** (for example, registering a listener from inside it). Other threads can see a half-built object.
- **Using `ThreadLocal` in pools without `remove()` in a `finally`.** Values leak into the next task.
- **Assuming a `ThreadLocal` value follows your task to another thread.** It doesn't. A task submitted to an executor runs with *that* thread's values.
- **Reaching for `ThreadLocal` in new code.** Prefer passing parameters, or `ScopedValue`.

---

## Check your understanding

**1. Why can an immutable object be shared between threads without synchronization?**

<details>
<summary>Reveal answer</summary>

Races need shared *mutable* state. An immutable object never changes after construction, so there's no write to race with, and its `final` fields are guaranteed visible to any thread that sees the reference.

</details>

**2. `record Team(String name, List<String> members)`: is it immutable?**

<details>
<summary>Reveal answer</summary>

Not necessarily. The fields are final, but `members` may be a mutable `ArrayList` that the caller still holds. Add a compact constructor with `members = List.copyOf(members);` to make it truly immutable.

</details>

**3. In `ThreadLocalLeak`, the anonymous leaky request printed `alice`. Why, and what one change fixes it?**

<details>
<summary>Reveal answer</summary>

Both requests ran on the same pool thread, and the first one never removed its value, so the second one found `alice` still attached to the thread. Calling `currentUser.remove()` in a `finally` block at the end of every request fixes it.

</details>

**4. What is the main advantage of `ScopedValue` over `ThreadLocal`?**

<details>
<summary>Reveal answer</summary>

Its binding has a clearly defined lifetime (the `run`/`call` block) and is removed automatically, so it can't leak between pooled tasks. It's also immutable within its scope and cheap to use with huge numbers of virtual threads.

</details>

**5. A `ThreadLocal` is set in a request thread, and then the request submits a task to an executor. Does the task see the value?**

<details>
<summary>Reveal answer</summary>

No. The task runs on a pool thread with that thread's own `ThreadLocal` values, which may be empty or, worse, left over from an earlier task. Pass the value explicitly, or use structured concurrency with `ScopedValue` (Lesson 22), where subtasks inherit bindings.

</details>

---

## Recap

- No shared mutable state means no races: make data **immutable**, keep it **confined** to one thread, or **scope** it to a call.
- Records plus `List.copyOf` make easy immutable types. Change them by creating new instances.
- `ThreadLocal` gives a value per thread. In pools, always `remove()` in `finally`.
- `ScopedValue` (Java 25) is immutable, automatically scoped context. Prefer it over `ThreadLocal` for request context.

## End of Part 3

You now have the whole `java.util.concurrent` toolkit: executors, futures, atomics, locks, synchronizers, collections and safe-sharing strategies. Part 4 turns to composing asynchronous work and splitting big computations across cores.

**Next: [Lesson 17, Scheduled executors](../part-4-async-and-parallel/17-scheduled-executors.md)**
