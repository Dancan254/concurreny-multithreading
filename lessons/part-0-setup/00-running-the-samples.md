# Lesson 00: Running the Samples

## What you'll learn

- How to run a single-file Java 25 program without a build tool
- What `void main()` and `IO.println` are, since every sample uses them
- How to take a thread dump of a running program, the most useful concurrency debugging tool there is

---

## Why this matters

Concurrency is learned by running code, watching it misbehave, and running it again. Output changes from run to run, and that is the point. So the loop from editing code to seeing output has to be as short as possible.

Java 25 makes that loop about as short as a script's. You don't need a project, a `pom.xml` or an IDE. You need one file and one command.

---

## Install Java 25

Check what you have:

```bash
java -version
```

You want to see `25` or higher:

```
openjdk version "25" 2025-09-16 LTS
```

If you have an older version, install any Java 25 distribution (Temurin, Zulu, Oracle, Corretto). [SDKMAN!](https://sdkman.io) is the easiest way on macOS and Linux:

```bash
sdk install java 25-tem
```

---

## Your first program

Create a file called `Hello.java`:

```java
void main() {
    IO.println("Hello from " + Thread.currentThread().getName());
    IO.println("CPU cores available: " + Runtime.getRuntime().availableProcessors());
}
```

Run it:

```bash
java Hello.java
```

```
Hello from main
CPU cores available: 12
```

Your core count will differ. Remember the number, because it shows up again in Lesson 01.

A few things in that file may look unfamiliar if you learned Java before version 25:

| What you see | What it means |
|---|---|
| No `class` declaration | A **compact source file**. Java wraps the file in an unnamed class for you. |
| `void main()` | An **instance main method**. It doesn't need `public`, `static` or `String[] args`. |
| `IO.println(...)` | `java.lang.IO`, a small console helper. It is in `java.lang`, so it needs no import. |
| No imports at all | Compact source files automatically import everything in the `java.base` module, so `java.util.*`, `java.util.concurrent.*`, `java.time.*` and friends are all available. |

These features became final in Java 25 (JEP 512). Everything you learn in this course works the same in a regular class with `public static void main(String[] args)`. The compact form just lets you focus on the concurrency.

### Methods and classes in a compact file

A compact file can contain other methods and even nested classes:

```java
void main() {
    greet("threads");
    var counter = new Counter();
    counter.increment();
    IO.println(counter.value());
}

void greet(String topic) {
    IO.println("Let's learn " + topic);
}

class Counter {
    private int value;

    void increment() {
        value++;
    }

    int value() {
        return value;
    }
}
```

Most samples in this course look like this: `main` at the top, helpers below it.

---

## The one debugging tool to learn now: the thread dump

A **thread dump** is a snapshot of every thread in a running JVM, showing its name, its state and the exact line of code it is on. When a concurrent program hangs, the dump tells you why.

Create `Sleepy.java`:

```java
void main() throws InterruptedException {
    Thread worker = new Thread(() -> {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }, "sleepy-worker");
    worker.start();
    worker.join();
}
```

Run it in one terminal. It will sit there for a minute:

```bash
java Sleepy.java
```

In a second terminal, find its process ID and ask the JVM for a thread dump:

```bash
jcmd                       # lists running JVMs with their PIDs
jcmd <pid> Thread.print    # prints every thread's stack
```

Look for your thread in the output:

```
"main" #3 [570476] prio=5 os_prio=0 cpu=1236.78ms elapsed=3.72s tid=0x0000782a1402ac80 nid=570476 in Object.wait()
   java.lang.Thread.State: WAITING (on object monitor)
	at java.lang.Object.wait0(java.base@25/Native Method)
	- waiting on <0x00000005b599d278> (a java.lang.Thread)
	at java.lang.Object.wait(java.base@25/Object.java:389)
	at java.lang.Thread.join(java.base@25/Thread.java:1887)
	- locked <0x00000005b599d278> (a java.lang.Thread)
	at java.lang.Thread.join(java.base@25/Thread.java:1963)
	at Sleepy.main(Sleepy.java:10)

"sleepy-worker" #25 [570525] prio=5 os_prio=0 cpu=0.84ms elapsed=2.46s tid=0x0000782a144dfdc0 nid=570525 waiting on condition
   java.lang.Thread.State: TIMED_WAITING (sleeping)
	at java.lang.Thread.sleepNanos0(java.base@25/Native Method)
	at java.lang.Thread.sleepNanos(java.base@25/Thread.java:509)
	at java.lang.Thread.sleep(java.base@25/Thread.java:540)
	at Sleepy.lambda$main$0(Sleepy.java:4)
	at Sleepy$$Lambda/0x000000005e120210.run(Unknown Source)
	at java.lang.Thread.runWith(java.base@25/Thread.java:1487)
	at java.lang.Thread.run(java.base@25/Thread.java:1474)
```

The dump lists many more threads than these two (JVM housekeeping threads such as `Reference Handler` and `Common-Cleaner`). You can ignore those for now.

For `sleepy-worker`, the dump shows the name you gave the thread, its state (`TIMED_WAITING`) and the line it is stuck on (`Sleepy.java:4`). The `main` thread is `WAITING` inside `join()`, on line 10. You'll learn what every one of those states means in Lesson 03. For now, remember that **`jcmd <pid> Thread.print` is how you see what threads are doing**.

---

## Try it yourself

1. Change `Hello.java` so that it also prints `Thread.currentThread().threadId()`.
2. In `Sleepy.java`, rename the thread to something else, run it and find it in the thread dump.
3. Remove the name argument (`"sleepy-worker"`) entirely. What name does the thread get in the dump?

---

## Common mistakes

- **Running with Java 21 or earlier.** You get `error: class, interface, enum, or record expected` or a complaint about `IO`. Check `java -version`.
- **Naming the file differently from what you run.** `java hello.java` and `java Hello.java` are different files on case-sensitive file systems.
- **Using `javac` then `java` out of habit.** It works, but you don't need it. `java File.java` compiles in memory and runs.

---

## Check your understanding

**1. Why does `IO.println` work without an import?**

<details>
<summary>Reveal answer</summary>

`IO` lives in `java.lang`, which every Java file imports implicitly, just like `String` and `Thread`.

</details>

**2. You use `ExecutorService` in a compact source file without importing `java.util.concurrent`. Does it compile?**

<details>
<summary>Reveal answer</summary>

Yes. Compact source files automatically import every public top-level type in the `java.base` module, and `java.util.concurrent` is part of `java.base`. A regular class file would still need the import.

</details>

**3. Your program hangs and never exits. What is the first thing you do?**

<details>
<summary>Reveal answer</summary>

Take a thread dump with `jcmd <pid> Thread.print` and look at what each thread is doing: its state and the line it is stuck on. Guessing from the source code comes after that, never before.

</details>

---

## Recap

- Every sample is one file: `java Name.java`.
- `void main()`, `IO.println` and automatic `java.base` imports are standard Java 25.
- `jcmd <pid> Thread.print` shows every thread's name, state and current line.

**Next: [Lesson 01, Concurrency vs parallelism](../part-1-threads-from-scratch/01-concurrency-vs-parallelism.md)**
