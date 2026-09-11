# Lesson 08: `wait`, `notify` and Guarded Blocks

## What you'll learn

- How a thread can wait efficiently for a condition to become true
- The rules of `wait()`, `notify()` and `notifyAll()`, and why each one exists
- Why `wait()` must always be called in a `while` loop
- How to build a bounded producer-consumer buffer by hand

---

## Why this matters

So far threads have either raced or queued for a lock. Often, though, one thread needs to *wait for another to do something*: a consumer waits until there's an order to process, and a producer waits until there's space in the buffer.

You could loop and check (`while (queue.isEmpty()) {}`), but that burns a whole CPU core doing nothing. `wait` and `notify` are Java's built-in way for a thread to sleep until another thread says "the thing you're waiting for may have happened."

You'll rarely write `wait`/`notify` yourself. `BlockingQueue` (Lesson 15) and the synchronizers in Lesson 14 do it for you. Building it once by hand is what makes those tools make sense, and it's a classic interview question.

---

## The concept

`wait`, `notify` and `notifyAll` are methods on `Object`, and they work together with the object's intrinsic lock from Lesson 06.

| Method | What it does |
|---|---|
| `lock.wait()` | **Releases** `lock` and puts the thread into `WAITING` until notified (or interrupted). It re-acquires `lock` before returning. |
| `lock.notify()` | Wakes **one** thread waiting on `lock`. You don't choose which. |
| `lock.notifyAll()` | Wakes **every** thread waiting on `lock`. They then compete for the lock one at a time. |

Rules:

1. **You must hold the lock** to call any of them, which means being inside `synchronized (lock)`. Otherwise you get `IllegalMonitorStateException`.
2. **`wait()` releases the lock** while waiting. This is the key difference from `sleep()`, which keeps it. Without the release, no other thread could ever get in to change the condition.
3. **A woken thread must re-acquire the lock** before `wait()` returns, so it may briefly sit in `BLOCKED` first.

### The guarded block

The pattern always has the same shape:

```java
synchronized (lock) {
    while (!conditionIsTrue()) {
        lock.wait();
    }
    // the condition is true AND we hold the lock: act on it
}
```

And on the other side:

```java
synchronized (lock) {
    changeTheState();
    lock.notifyAll();
}
```

### Why `while` and not `if`?

Because when `wait()` returns, the condition may **not** be true:

- **Another thread got there first.** Two consumers wait, one item arrives, and `notifyAll` wakes both. The first takes the item, and the second wakes up to an empty queue.
- **Spurious wakeups.** The JVM is allowed to return from `wait()` with no notification at all. This is rare, but it is allowed by the specification.
- **The notification was about a different condition.** With `notifyAll`, a producer waiting for space may be woken by another producer that added an item.

The `while` loop re-checks the condition every time the thread wakes. An `if` would carry on with a false assumption.

### `notify` or `notifyAll`?

Prefer `notifyAll`. With `notify`, the JVM wakes one arbitrary thread. If producers and consumers wait on the same lock, `notify` may wake a producer when a consumer was the one who could make progress. The producer finds nothing to do and goes back to waiting, and nobody else is ever woken, so the program stalls. `notifyAll` wastes a few wake-ups but can't stall this way.

---

## Hands-on

### A bounded buffer

The buffer holds at most `capacity` items. `put` waits while it is full, and `take` waits while it is empty.

`BoundedBuffer.java`:

```java
void main() throws InterruptedException {
    var buffer = new BoundedBuffer<String>(2);

    Thread producer = new Thread(() -> {
        try {
            for (int i = 1; i <= 5; i++) {
                buffer.put("order-" + i);
                IO.println("produced order-" + i);
            }
            buffer.put("DONE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }, "producer");

    Thread consumer = new Thread(() -> {
        try {
            while (true) {
                String order = buffer.take();
                if (order.equals("DONE")) {
                    return;
                }
                Thread.sleep(100);
                IO.println("        consumed " + order);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }, "consumer");

    producer.start();
    consumer.start();
    producer.join();
    consumer.join();
}

class BoundedBuffer<T> {
    private final Queue<T> items = new ArrayDeque<>();
    private final int capacity;

    BoundedBuffer(int capacity) {
        this.capacity = capacity;
    }

    synchronized void put(T item) throws InterruptedException {
        while (items.size() == capacity) {
            wait();
        }
        items.add(item);
        notifyAll();
    }

    synchronized T take() throws InterruptedException {
        while (items.isEmpty()) {
            wait();
        }
        T item = items.remove();
        notifyAll();
        return item;
    }
}
```

One run:

```
produced order-1
produced order-2
produced order-3
        consumed order-1
produced order-4
        consumed order-2
produced order-5
        consumed order-3
        consumed order-4
        consumed order-5
```

The producer races ahead until the buffer is full, then has to wait. From then on, it can only add an order each time the slow consumer takes one. That is **backpressure**: a fast producer is automatically slowed to the speed of its consumer, and memory use stays bounded no matter how far apart their speeds are. (The consumer takes an order *before* sleeping, which is why `order-3` can be produced before `order-1` is printed as consumed.)

Walk through the code with the rules above:

- `put` and `take` are `synchronized` methods, so the lock is `this`, and `wait()` / `notifyAll()` are called on `this`.
- Both waits are in `while` loops.
- Each state change is followed by `notifyAll()`, because it may have made the *other* side's condition true.
- `wait()` throws `InterruptedException`, and both methods propagate it. That follows the rule from Lesson 04.
- The `"DONE"` item is a **poison pill**: a special value that tells the consumer to stop. It is a common way to shut down a producer-consumer pipeline.

---

## Try it yourself

1. Change both `while` loops to `if`, and run with **two** consumers and one producer. Can you make it fail? (Try it with a larger number of orders.)
2. Replace `notifyAll()` with `notify()` and run two producers and two consumers with a capacity of 1. Does it ever hang? Take a thread dump when it does.
3. Call `buffer.wait()` from `main` without a `synchronized` block. What exception do you get?
4. Rewrite the program using `ArrayBlockingQueue<String>(2)` instead of `BoundedBuffer`. How much code disappears? (This is Lesson 15's preview.)

---

## Common mistakes

- **Using `if` around `wait()`.** Always `while`. The condition must be re-checked after every wake-up.
- **Calling `wait`/`notify` without holding the lock.** `IllegalMonitorStateException`.
- **Waiting on one object and notifying a different one.** They must be the same monitor.
- **Using `notify()` when several kinds of waiter share one lock.** The wrong thread can be woken and the signal is lost.
- **Changing state without notifying.** Waiters sleep forever.
- **Writing this by hand in production.** Use `BlockingQueue`, `CountDownLatch`, `Semaphore` and friends. They are tested, faster, and hard to get subtly wrong.

---

## Check your understanding

**1. What is the most important difference between `wait()` and `sleep()`?**

<details>
<summary>Reveal answer</summary>

`wait()` releases the monitor lock while waiting, so other threads can enter and change the condition. `sleep()` keeps every lock it holds. Also, `wait()` must be called while holding the lock, and it is woken by `notify`, while `sleep()` wakes after its time runs out.

</details>

**2. Why must `wait()` be inside a `while` loop?**

<details>
<summary>Reveal answer</summary>

After waking, the condition may not hold. Another thread may have consumed the item first, the wake-up may have been spurious, or the notification may have been for a different condition. The loop re-checks before acting.

</details>

**3. What happens if you call `notifyAll()` outside a `synchronized` block on that object?**

<details>
<summary>Reveal answer</summary>

It throws `IllegalMonitorStateException`. The calling thread must own the object's monitor to call `wait`, `notify` or `notifyAll`.

</details>

**4. In the bounded buffer, why does `take()` call `notifyAll()` after removing an item?**

<details>
<summary>Reveal answer</summary>

Removing an item frees space, so a producer waiting in `put()` because the buffer was full can now proceed. It must be woken up, or it would wait forever.

</details>

**5. What is a poison pill?**

<details>
<summary>Reveal answer</summary>

A special value put into a queue to tell the consumer to shut down, like `"DONE"` in the example. It travels through the queue in order, so the consumer finishes everything that came before it and then stops.

</details>

---

## Recap

- `wait()` releases the lock and sleeps. `notifyAll()` wakes the waiters. Both require holding the lock.
- The guarded block: `synchronized`, then `while (!condition) wait();`, then act.
- Always `while`, and prefer `notifyAll`.
- A bounded buffer gives you **backpressure**. In real code, use `BlockingQueue`.

**Next: [Lesson 09, Deadlock, livelock and starvation](09-liveness.md)**
