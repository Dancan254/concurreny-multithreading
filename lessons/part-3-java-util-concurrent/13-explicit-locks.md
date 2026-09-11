# Lesson 13: Explicit Locks

## What you'll learn

- `ReentrantLock`: `synchronized` with more options, and the `lock`/`try`/`finally` shape it requires
- `tryLock` with a timeout, a way to avoid deadlock instead of just preventing it
- `Condition`: separate wait queues for separate conditions
- `ReadWriteLock` for read-heavy data, and `StampedLock`'s optimistic reads

---

## Why this matters

`synchronized` is simple and covers most needs. But it can't:

- Try to get a lock and give up if it's busy.
- Wait for a lock with a timeout.
- Be interrupted while waiting for a lock.
- Let many readers in at the same time while keeping writers exclusive.
- Have more than one wait-set per lock (Lesson 08's producers and consumers had to share one).

The `java.util.concurrent.locks` package adds all of these.

---

## The concept

### `ReentrantLock`

It behaves like `synchronized`: mutual exclusion, visibility and reentrancy. But you lock and unlock it yourself, and **you must always unlock in `finally`**:

```java
lock.lock();
try {
    // critical section
} finally {
    lock.unlock();
}
```

If an exception escapes without that `finally`, the lock is never released and every other thread waits forever. `synchronized` releases automatically. `ReentrantLock` doesn't.

| Method | Behaviour |
|---|---|
| `lock()` | Wait until the lock is free, without responding to interrupts |
| `lockInterruptibly()` | Wait, but throw `InterruptedException` if interrupted |
| `tryLock()` | Take the lock if it's free right now and return `true`, otherwise return `false` immediately |
| `tryLock(timeout, unit)` | Wait up to the timeout |
| `newCondition()` | Create a `Condition`, a wait-set for this lock |
| `new ReentrantLock(true)` | A **fair** lock: the longest-waiting thread goes next. Less starvation, lower throughput. |

### Which lock should I use?

| Need | Use |
|---|---|
| Plain mutual exclusion | `synchronized`. It's simpler, and it releases automatically. |
| Timeout, `tryLock`, interruptible waiting, fairness | `ReentrantLock` |
| Several separate wait conditions | `ReentrantLock` plus `Condition`s |
| Many readers, few writers, meaningful work under the lock | `ReentrantReadWriteLock` |
| Very frequent, very short reads | `StampedLock` with optimistic reads |

Since Java 24, `synchronized` no longer pins virtual threads (Lesson 21), so that old reason to prefer `ReentrantLock` has gone away.

---

## Hands-on

### 1. `ReentrantLock` basics

`ReentrantLockDemo.java`:

```java
void main() throws InterruptedException {
    var account = new BankAccount(100);

    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                for (int j = 0; j < 1_000; j++) {
                    account.deposit(1);
                    account.withdraw(1);
                }
            });
        }
    }
    IO.println("balance = " + account.balance());
}

class BankAccount {
    private final Lock lock = new ReentrantLock();
    private long balance;

    BankAccount(long openingBalance) {
        this.balance = openingBalance;
    }

    void deposit(long amount) {
        lock.lock();
        try {
            balance += amount;
        } finally {
            lock.unlock();
        }
    }

    void withdraw(long amount) {
        lock.lock();
        try {
            if (amount > balance) {
                throw new IllegalArgumentException("insufficient funds");
            }
            balance -= amount;
        } finally {
            lock.unlock();
        }
    }

    long balance() {
        lock.lock();
        try {
            return balance;
        } finally {
            lock.unlock();
        }
    }
}
```

```
balance = 100
```

Note `withdraw`: when it throws, the `finally` still unlocks. Call `lock()` *before* the `try`, not inside it. If `lock()` itself failed inside the `try`, the `finally` would call `unlock()` on a lock you never held, which throws `IllegalMonitorStateException`.

### 2. `tryLock`: the deadlock-free transfer

Lesson 09 prevented deadlock with lock ordering. `tryLock` breaks a different Coffman condition, *hold and wait*: if you can't get the second lock, release the first and try again later.

`TryLockTransfer.java`:

```java
void main() throws InterruptedException {
    var alice = new Account("alice", 1_000);
    var bob = new Account("bob", 1_000);

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        pool.submit(() -> repeat(() -> transfer(alice, bob, 1)));
        pool.submit(() -> repeat(() -> transfer(bob, alice, 1)));
    }
    IO.println("alice=" + alice.balance + " bob=" + bob.balance + " total=" + (alice.balance + bob.balance));
}

void repeat(Runnable action) {
    for (int i = 0; i < 10_000; i++) {
        action.run();
    }
}

void transfer(Account from, Account to, long amount) {
    while (true) {
        try {
            if (from.lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                try {
                    if (to.lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                        try {
                            from.balance -= amount;
                            to.balance += amount;
                            return;
                        } finally {
                            to.lock.unlock();
                        }
                    }
                } finally {
                    from.lock.unlock();
                }
            }
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
    }
}

class Account {
    final String owner;
    final ReentrantLock lock = new ReentrantLock();
    long balance;

    Account(String owner, long balance) {
        this.owner = owner;
        this.balance = balance;
    }
}
```

```
alice=1000 bob=1000 total=2000
```

Twenty thousand transfers in opposite directions, all taking locks in opposite orders, and no deadlock. Two details matter:

- **The random sleep before retrying** is the livelock fix from Lesson 09. Without jitter, both threads could back off and retry in perfect lockstep.
- **Every successful `tryLock` has a matching `unlock` in a `finally`.**

### 3. `Condition`: separate wait queues

Lesson 08's bounded buffer used one wait-set for both "not full" and "not empty", so it needed `notifyAll`. With `Condition`s, producers wait on one queue and consumers on another, and each signal wakes only a thread that can make progress.

`ConditionBuffer.java`:

```java
void main() throws InterruptedException {
    var buffer = new ConditionBuffer<Integer>(3);

    Thread producer = new Thread(() -> {
        try {
            for (int i = 1; i <= 6; i++) {
                buffer.put(i);
                IO.println("put " + i);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });
    Thread consumer = new Thread(() -> {
        try {
            for (int i = 1; i <= 6; i++) {
                Thread.sleep(100);
                IO.println("    took " + buffer.take());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });
    producer.start();
    consumer.start();
    producer.join();
    consumer.join();
}

class ConditionBuffer<T> {
    private final Queue<T> items = new ArrayDeque<>();
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    ConditionBuffer(int capacity) {
        this.capacity = capacity;
    }

    void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (items.size() == capacity) {
                notFull.await();
            }
            items.add(item);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    T take() throws InterruptedException {
        lock.lock();
        try {
            while (items.isEmpty()) {
                notEmpty.await();
            }
            T item = items.remove();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }
}
```

```
put 1
put 2
put 3
put 4
    took 1
put 5
    took 2
put 6
    took 3
    took 4
    took 5
    took 6
```

(`put 4` can appear before `took 1` because the consumer takes the item and *then* prints, so the producer may slip into the freed space in between.)

`await`/`signal`/`signalAll` are the `Condition` versions of `wait`/`notify`/`notifyAll`, and the rules are the same: hold the lock, and await in a `while` loop. This is essentially how `ArrayBlockingQueue` is written in the JDK.

### 4. `ReadWriteLock`: many readers or one writer

Most caches are read far more often than they're written. A normal lock makes readers wait for each other for no reason. A read-write lock lets any number of readers in at once, while a writer gets exclusive access.

`ReadWriteCache.java`:

```java
void main() throws InterruptedException {
    var cache = new PriceCache();
    cache.update("BTC", 60_000);

    try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
        for (int reader = 0; reader < 5; reader++) {
            pool.submit(() -> {
                for (int i = 0; i < 3; i++) {
                    IO.println(Thread.currentThread().getName() + " reads BTC = " + cache.price("BTC"));
                    pause(50);
                }
            });
        }
        pool.submit(() -> {
            pause(60);
            cache.update("BTC", 61_000);
            IO.println(">>> writer updated BTC");
        });
    }
}

void pause(long millis) {
    try {
        Thread.sleep(millis);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}

class PriceCache {
    private final Map<String, Integer> prices = new HashMap<>();
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    Integer price(String symbol) {
        readWriteLock.readLock().lock();
        try {
            return prices.get(symbol);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    void update(String symbol, int price) {
        readWriteLock.writeLock().lock();
        try {
            prices.put(symbol, price);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }
}
```

One run:

```
pool-1-thread-3 reads BTC = 60000
pool-1-thread-4 reads BTC = 60000
pool-1-thread-1 reads BTC = 60000
pool-1-thread-2 reads BTC = 60000
pool-1-thread-5 reads BTC = 60000
pool-1-thread-4 reads BTC = 60000
pool-1-thread-5 reads BTC = 60000
pool-1-thread-1 reads BTC = 60000
pool-1-thread-3 reads BTC = 60000
pool-1-thread-2 reads BTC = 60000
>>> writer updated BTC
pool-1-thread-4 reads BTC = 61000
...
```

Readers never wait for each other. The writer waits for the readers currently inside, then briefly excludes everyone.

Two things to know:

- A read-write lock only pays off when reads are **frequent and not trivially short**, and writes are rare. For a single `HashMap.get`, the extra bookkeeping can cost more than it saves. In this example, a `ConcurrentHashMap` (Lesson 15) would be simpler and faster. Use `ReadWriteLock` when a *compound* read must see a consistent view of several structures.
- You **can't upgrade** a read lock to a write lock. A thread holding the read lock that asks for the write lock deadlocks with itself. Release the read lock first. Downgrading (write to read) is allowed.

### 5. `StampedLock` and optimistic reads

`StampedLock` adds an **optimistic read**: read the fields *without locking at all*, then check whether a writer came in while you were reading. If one did, fall back to a real read lock.

`StampedPoint.java`:

```java
void main() throws InterruptedException {
    var point = new Point();

    Thread mover = new Thread(() -> {
        for (int i = 1; i <= 100_000; i++) {
            point.move(1, 1);
        }
    });
    Thread reader = new Thread(() -> {
        int mismatches = 0;
        for (int i = 0; i < 100_000; i++) {
            double[] xy = point.read();
            if (xy[0] != xy[1]) {
                mismatches++;
            }
        }
        IO.println("reads where x != y: " + mismatches);
    });
    mover.start();
    reader.start();
    mover.join();
    reader.join();
    IO.println("final: " + Arrays.toString(point.read()));
}

class Point {
    private final StampedLock stampedLock = new StampedLock();
    private double x;
    private double y;

    void move(double deltaX, double deltaY) {
        long stamp = stampedLock.writeLock();
        try {
            x += deltaX;
            y += deltaY;
        } finally {
            stampedLock.unlockWrite(stamp);
        }
    }

    double[] read() {
        long stamp = stampedLock.tryOptimisticRead();
        double currentX = x;
        double currentY = y;
        if (!stampedLock.validate(stamp)) {
            stamp = stampedLock.readLock();
            try {
                currentX = x;
                currentY = y;
            } finally {
                stampedLock.unlockRead(stamp);
            }
        }
        return new double[] {currentX, currentY};
    }
}
```

```
reads where x != y: 0
final: [100000.0, 100000.0]
```

`x` and `y` always move together, and the reader never sees a half-moved point, even though most reads take no lock. `validate(stamp)` returns `false` if any write happened since the stamp was issued. The values read in that case may be inconsistent, so they are thrown away and read again under a real lock.

The price: `StampedLock` is **not reentrant**, has no `Condition`s, and is easy to misuse. Use it for small, hot, read-mostly values, and reach for simpler tools first.

---

## Try it yourself

1. Delete the `finally` in `BankAccount.withdraw` and call `withdraw(1_000_000)` once from one thread. What happens to the other threads?
2. Remove the random sleep in `TryLockTransfer` and add a retry counter. How many retries do you see? Now put the sleep back.
3. In `ConditionBuffer`, replace both `signal()` calls with `signalAll()`. Does it still work? Is it necessary?
4. In `ReadWriteCache`, have a reader try to call `update` while it holds the read lock. What happens? (Take a thread dump.)

---

## Common mistakes

- **Forgetting `unlock()` in a `finally`.** One exception, and the lock is held forever.
- **Calling `lock()` inside the `try`.** If locking fails, `finally` unlocks a lock you don't hold.
- **Using `ReentrantLock` when `synchronized` would do.** More code, and more ways to get it wrong.
- **Trying to upgrade a read lock to a write lock.** The thread deadlocks with itself.
- **Reentering a `StampedLock`.** It isn't reentrant, so the thread blocks itself.
- **Assuming a fair lock is always better.** Fairness costs a lot of throughput. Use it only when starvation is an observed problem.

---

## Check your understanding

**1. Name three things `ReentrantLock` can do that `synchronized` can't.**

<details>
<summary>Reveal answer</summary>

Any three of these: `tryLock()` (don't wait), `tryLock(timeout)`, `lockInterruptibly()`, fair ordering, and several `Condition`s per lock.

</details>

**2. Why must `unlock()` be in a `finally` block?**

<details>
<summary>Reveal answer</summary>

`ReentrantLock` isn't released automatically. If the critical section throws and the unlock is skipped, the lock is held forever and every other thread waits indefinitely.

</details>

**3. How does `tryLock` with a timeout help prevent deadlock?**

<details>
<summary>Reveal answer</summary>

It breaks "hold and wait". A thread that can't get the second lock in time releases the first and retries later, so it never holds one lock while waiting forever for another. Add random backoff to avoid livelock.

</details>

**4. When does a `ReadWriteLock` beat a normal lock?**

<details>
<summary>Reveal answer</summary>

When reads are much more common than writes, and each read does enough work under the lock that letting readers run in parallel matters. For tiny reads, or write-heavy workloads, its overhead can make it slower than a plain lock.

</details>

**5. What does `StampedLock.validate(stamp)` returning `false` mean?**

<details>
<summary>Reveal answer</summary>

A write happened since the optimistic stamp was issued, so the values you read may be inconsistent. Discard them and read again under a real read lock.

</details>

---

## Recap

- `ReentrantLock` = `synchronized` plus `tryLock`, timeouts, interruptible waits, fairness and `Condition`s. Always use `lock()`, then `try`, then `finally { unlock(); }`.
- `tryLock` with a timeout and jitter avoids deadlock without a global lock order.
- `Condition` gives each wait condition its own queue.
- `ReadWriteLock` is for read-heavy data with meaningful reads. `StampedLock` adds lock-free optimistic reads.
- Default to `synchronized`, and move up only when you need a feature.

**Next: [Lesson 14, Synchronizers](14-synchronizers.md)**
