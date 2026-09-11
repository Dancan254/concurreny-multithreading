# Map Internals: HashMap, LinkedHashMap, TreeMap, ConcurrentHashMap

> Target: Java 25. Source references are to `java.util` / `java.util.concurrent` in the JDK.
> Audience: you know how to *use* a `Map`. This lesson is about what happens underneath.

## The sequence

1. **HashMap**: the base. Hashing, buckets, collisions, resizing, treeification.
2. **LinkedHashMap**: HashMap plus a doubly linked list. Ordering and LRU caches.
3. **TreeMap**: no hashing at all. A red-black tree and ordered navigation.
4. **ConcurrentHashMap**: HashMap redesigned so many threads can use it safely. CAS, per-bin locks, cooperative resizing.
5. Comparison and when to pick which.

Each map answers a different question:

| Map | Question it answers |
|---|---|
| `HashMap` | "Find this key as fast as possible." |
| `LinkedHashMap` | "Find it fast, and remember the order." |
| `TreeMap` | "Keep keys sorted, and let me ask range questions." |
| `ConcurrentHashMap` | "Find it fast while many threads read and write at once." |

---

## Part 1: HashMap

### Why hashing?

A list lookup is O(n): you compare against every element. An array lookup by index is O(1).
Hashing turns a key into an array index, so a lookup costs about the same as an array read.

The catch: many keys can map to the same index. Most of HashMap's design is about dealing
with that.

### The data structure

```java
transient Node<K,V>[] table;   // the buckets ("bins")
transient int size;            // number of key-value mappings
int threshold;                 // resize when size exceeds this
final float loadFactor;        // default 0.75
transient int modCount;        // structural modifications, used by fail-fast iterators

static class Node<K,V> implements Map.Entry<K,V> {
    final int hash;            // cached, never recomputed
    final K key;
    V value;
    Node<K,V> next;            // the next node in the same bucket
}
```

Picture it like this:

```
table (capacity 16)
 ┌────┐
 │ 0  │ → null
 │ 1  │ → [hash=17,"bob"] → [hash=33,"eve"] → null      ← collision chain
 │ 2  │ → null
 │ 3  │ → [hash=3,"ann"] → null
 │ …  │
 │ 15 │ → null
 └────┘
```

Things worth knowing:

- **The table is created lazily.** `new HashMap<>()` allocates no array. The first `put` does.
- **Defaults:** capacity 16, load factor 0.75, so threshold = 16 × 0.75 = 12.
- **Capacity is always a power of two.** Ask for 100 and you get 128. We'll see why below.
- **The hash is cached in the node**, so resizing and comparisons never call `hashCode()` again.

### Step 1: from `hashCode()` to a spread hash

```java
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
```

**Why XOR the high 16 bits into the low 16?** Because the index only uses the *low* bits
(next step). With a 16-slot table, only the bottom 4 bits matter. Keys whose hash codes differ
only in the high bits would all pile into the same bucket. Folding the high half into the low
half lets those bits influence the index, for the cost of one shift and one XOR.

Also note: **a `null` key is allowed** and always hashes to 0, so it lives in bucket 0.

### Step 2: from hash to bucket index

```java
int index = (table.length - 1) & hash;
```

This is why capacity is a power of two. When `n = 16`, `n - 1 = 0b1111`, and `hash & 0b1111`
gives the same result as `hash % 16`, but it's a single AND instruction and it works for
negative hashes too.

```
hash       = 1011 0110 1101 0101   (low 16 bits shown)
n - 1 = 15 = 0000 0000 0000 1111
             ─────────────────── &
index      = 0000 0000 0000 0101   = 5
```

The requested capacity is rounded up with `tableSizeFor`:

```java
static final int tableSizeFor(int cap) {
    int n = -1 >>> Integer.numberOfLeadingZeros(cap - 1);
    return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
}
```

### Step 3: `put` walkthrough (`putVal`, simplified)

```java
final V putVal(int hash, K key, V value, boolean onlyIfAbsent, boolean evict) {
    Node<K,V>[] tab = table;
    if (tab == null || tab.length == 0)
        tab = resize();                                   // lazy init

    int i = (tab.length - 1) & hash;
    Node<K,V> p = tab[i];
    if (p == null) {
        tab[i] = newNode(hash, key, value, null);         // empty bucket: done
    } else {
        Node<K,V> e;                                      // existing node for this key, if any
        if (p.hash == hash && (p.key == key || key.equals(p.key)))
            e = p;                                        // first node matches
        else if (p instanceof TreeNode)
            e = ((TreeNode<K,V>) p).putTreeVal(this, tab, hash, key, value);
        else {
            for (int binCount = 0; ; ++binCount) {        // walk the chain
                if ((e = p.next) == null) {
                    p.next = newNode(hash, key, value, null);   // append at TAIL
                    if (binCount >= TREEIFY_THRESHOLD - 1)
                        treeifyBin(tab, hash);
                    break;
                }
                if (e.hash == hash && (e.key == key || key.equals(e.key)))
                    break;
                p = e;
            }
        }
        if (e != null) {                                  // key existed: replace value
            V old = e.value;
            if (!onlyIfAbsent || old == null) e.value = value;
            afterNodeAccess(e);                           // hook for LinkedHashMap
            return old;
        }
    }
    ++modCount;
    if (++size > threshold)
        resize();
    afterNodeInsertion(evict);                            // hook for LinkedHashMap
    return null;
}
```

Notice the comparison order: `p.hash == hash` first, then `==`, then `equals`. Comparing two
cached ints is almost free and rules out most non-matches before the expensive `equals()` call.

This is also why **the `equals`/`hashCode` contract matters**. Equal objects *must* have equal
hash codes, otherwise the map looks in the wrong bucket and never finds the key.

### Step 4: `get`

`get` runs the same path: compute hash, pick bucket, check the first node, then either search
the tree or walk the chain. Average O(1). The worst case depends on the bucket shape, which
brings us to collisions.

### Collisions and treeification

A bucket starts as a linked list. If a single bucket gets long, lookups in it degrade to O(n).
This can happen through a bad `hashCode()` or on purpose (hash-flooding attacks on web servers
that put request parameters in a map).

Since Java 8, a long bucket turns into a **red-black tree** (`TreeNode`):

| Constant | Value | Meaning |
|---|---|---|
| `TREEIFY_THRESHOLD` | 8 | A bucket with 8+ nodes is a candidate for becoming a tree |
| `MIN_TREEIFY_CAPACITY` | 64 | Only treeify if the table has at least 64 slots; otherwise **resize instead** |
| `UNTREEIFY_THRESHOLD` | 6 | During a resize, a tree bucket with ≤ 6 nodes goes back to a list |

**Why resize before treeifying a small table?** In a small table, a long chain probably means
"the table is too small," not "the hash function is bad." Doubling the table spreads the keys
out. Treeification is a fallback for when the table is already big and collisions persist.

**Why 8?** The JDK source comment explains it: with random hash codes and load factor 0.75,
bucket sizes follow a Poisson distribution. The chance of a bucket reaching 8 entries is about
0.00000006. So a tree bucket almost always means a bad hash or an attack, never normal use.

**Why the gap between 8 and 6?** Hysteresis. If both thresholds were 8, a bucket bouncing
between 7 and 8 entries would convert back and forth on every change.

**How are tree nodes ordered?** Keys don't have to be `Comparable`, so the tree orders by:

1. the hash value,
2. then `compareTo`, if both keys are the same `Comparable` class,
3. then a tie-breaker (`tieBreakOrder`, based on class name and `System.identityHashCode`).

So a hostile set of keys with identical hash codes still gives O(log n) lookups **if the keys
are `Comparable`** (like `String`). If they aren't, the tree can't use ordering to narrow the
search and has to explore both subtrees.

A `TreeNode` takes roughly twice the memory of a `Node`, another reason it's only used when needed.

### Resizing

When `size > threshold`, the table doubles. Every entry needs a new index, but HashMap never
recomputes a hash. It uses a trick that only works because capacity is a power of two.

Doubling adds exactly one bit to the index mask. So each entry either:

- stays at **the same index** (the new bit of its hash is 0), or
- moves to **index + oldCapacity** (the new bit is 1).

```
oldCap = 16 → mask 0b01111
newCap = 32 → mask 0b11111
                     ↑ the only new bit: hash & oldCap

hash =  5 = 0b00101 → old index 5, (5  & 16) == 0 → stays at 5
hash = 21 = 0b10101 → old index 5, (21 & 16) != 0 → moves to 5 + 16 = 21
```

`resize()` splits each bucket into a "lo" list and a "hi" list with a single pass, and both
**keep their original relative order**.

> **History lesson (good for a video):** Java 7 inserted new nodes at the *head* of a chain,
> and resizing reversed chains. When two threads resized an unsynchronized HashMap at the same
> time, they could create a cycle in a chain, and the next `get` would loop forever at 100% CPU.
> Java 8 switched to tail insertion and order-preserving splits, which removes the infinite loop.
> But HashMap is **still not thread-safe**. Concurrent writes can still lose updates or corrupt
> `size`. The fix is `ConcurrentHashMap`, not "HashMap is fine now."

### Pre-sizing pitfall

```java
var users = new HashMap<String, User>(100);
```

This looks like it fits 100 entries. It doesn't. Capacity rounds to 128, threshold is
128 × 0.75 = 96, so entry 97 triggers a resize.

Since Java 19, use the factory method that takes the number of **mappings**, not the capacity:

```java
var users = HashMap.<String, User>newHashMap(100);   // capacity 256, threshold 192, no resize
```

The same method exists on `LinkedHashMap`, `HashSet`, `LinkedHashSet`, and `WeakHashMap`.

### Iteration and fail-fast

- Iteration goes bucket by bucket, so the order looks random and **can change after a resize**.
  Never rely on it.
- Iteration costs O(capacity + size). A map that grew to a million entries and shrank to ten
  still iterates over a million slots. Tables never shrink.
- Every structural change bumps `modCount`. Iterators remember the value they started with,
  and throw `ConcurrentModificationException` on mismatch. This is a **best-effort bug detector,
  not a thread-safety mechanism**. It also fires in a single thread:

```java
for (String key : map.keySet()) {
    if (key.startsWith("tmp")) map.remove(key);      // ConcurrentModificationException
}

map.keySet().removeIf(key -> key.startsWith("tmp")); // correct
```

### Mutable key trap

```java
var key = new ArrayList<>(List.of(1, 2));
var map = new HashMap<List<Integer>, String>();
map.put(key, "found");

key.add(3);                     // hashCode changes, node is still in the old bucket
map.get(key);                   // null
map.get(List.of(1, 2));         // null too: right bucket, but equals() now fails
```

The node's cached hash is stale. The entry is still in the map, but unreachable. Use immutable
keys: `String`, boxed primitives, enums, records with immutable components.

### HashMap cheat sheet

| Operation | Average | Worst case (Java 8+) |
|---|---|---|
| `get` / `put` / `remove` | O(1) | O(log n) with Comparable keys in a tree bin, else O(n) |
| Iteration | O(capacity + size) | |
| `null` key / values | allowed / allowed | |
| Thread-safe | no | |

---

## Part 2: LinkedHashMap

### Why it exists

HashMap's order is unpredictable. Sometimes you need to iterate in the order entries were
inserted (JSON output, config, reports), or in the order they were *used* (a cache).

### How it works: HashMap + a doubly linked list

`LinkedHashMap extends HashMap`. The buckets work exactly as before. On top, every entry is
also threaded into one doubly linked list that runs through **all** entries:

```java
static class Entry<K,V> extends HashMap.Node<K,V> {
    Entry<K,V> before, after;
}

transient LinkedHashMap.Entry<K,V> head;   // eldest
transient LinkedHashMap.Entry<K,V> tail;   // youngest
final boolean accessOrder;                 // false = insertion order, true = access order
```

```
Buckets (hash structure, unchanged)       Linked list (iteration order)

 [1] → ann → eve                          head
 [4] → bob                                 ↓
 [9] → cat                                ann ⇄ bob ⇄ cat ⇄ eve
                                                             ↑
                                                            tail
```

Each entry sits in **two structures at once**: its bucket chain (`next`) for lookup, and the
global list (`before`/`after`) for order.

Fun detail: HashMap's `TreeNode` extends `LinkedHashMap.Entry`, not `HashMap.Node`. That way a
tree bin works inside a LinkedHashMap without a second node type. A plain HashMap pays for two
unused pointers in tree nodes, which is acceptable because tree nodes are rare.

### The hooks

Remember the calls in `putVal`? HashMap has three empty methods that LinkedHashMap overrides:

| Hook | Called when | LinkedHashMap does |
|---|---|---|
| `newNode(...)` | a node is created | creates an `Entry` and links it at the tail |
| `afterNodeAccess(e)` | a key is read or updated | if `accessOrder`, moves `e` to the tail |
| `afterNodeInsertion(evict)` | after a new mapping is added | asks `removeEldestEntry(head)`, removes head if true |
| `afterNodeRemoval(e)` | a node is removed | unlinks it from the list |

This is the **template method pattern** in the JDK: the base class defines the algorithm, the
subclass customizes the steps.

### Insertion order vs access order

```java
var insertion = new LinkedHashMap<String, Integer>();
insertion.put("a", 1); insertion.put("b", 2); insertion.put("c", 3);
insertion.get("a");
insertion.put("b", 20);                 // re-inserting an existing key does NOT move it
IO.println(insertion.keySet());         // [a, b, c]

var access = new LinkedHashMap<String, Integer>(16, 0.75f, true);
access.put("a", 1); access.put("b", 2); access.put("c", 3);
access.get("a");                        // moves "a" to the tail
IO.println(access.keySet());            // [b, c, a]
```

In access-order mode, **`get` is a structural modification**. It moves a node and increments
`modCount`, so calling `get` while iterating throws `ConcurrentModificationException`. And a
read now writes, so even "read-only" use from several threads is unsafe.

### Building an LRU cache in six lines

```java
class LruCache<K, V> extends LinkedHashMap<K, V> {
    private final int maxEntries;

    LruCache(int maxEntries) {
        super(16, 0.75f, true);
        this.maxEntries = maxEntries;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxEntries;
    }
}
```

Access order keeps the least recently used entry at the head, and `removeEldestEntry` evicts it
after each insert. Not thread-safe. For production caching, reach for Caffeine or Spring's
cache abstraction.

### Sequenced collections (Java 21+)

`LinkedHashMap` implements `SequencedMap`, so ordering is part of the API now:

```java
var map = new LinkedHashMap<String, Integer>();
map.put("b", 2);
map.putFirst("a", 1);           // a moves/goes to the front
map.putLast("z", 26);
map.firstEntry();               // a=1
map.pollLastEntry();            // removes and returns z=26
map.reversed();                 // a reversed VIEW, not a copy
```

### LinkedHashMap cheat sheet

| | |
|---|---|
| Lookup | same as HashMap, O(1) average |
| Memory | HashMap + 2 pointers per entry |
| Iteration | O(size), faster than HashMap for sparse tables |
| Order | insertion (default) or access |
| Thread-safe | no, and access order makes `get` a write |

---

## Part 3: TreeMap

### Why it exists

A hash scrambles order by design. If you need "all orders between 9:00 and 10:00" or "the
closest price below 100," hashing can't help. TreeMap keeps keys **sorted at all times**.

### The data structure: a red-black tree

No table, no hashing. `hashCode()` and `equals()` are never called on keys.

```java
private final Comparator<? super K> comparator;   // null = natural ordering (Comparable)
private transient Entry<K,V> root;

static final class Entry<K,V> implements Map.Entry<K,V> {
    K key;
    V value;
    Entry<K,V> left;
    Entry<K,V> right;
    Entry<K,V> parent;       // needed to walk back up for rebalancing and iteration
    boolean color = BLACK;
}
```

```
                (30,B)
               /      \
          (15,R)      (45,B)
          /    \           \
      (10,B)  (20,B)      (50,R)
```

A plain binary search tree can degenerate into a linked list (insert 1, 2, 3, 4, … in order).
A red-black tree prevents that by enforcing these rules:

1. Every node is red or black.
2. The root is black.
3. A red node never has a red child.
4. Every path from a node down to its empty leaves has the same number of black nodes.

Together, these guarantee the longest path is at most twice the shortest, so the height stays
O(log n). A million keys means at most about 40 comparisons per lookup.

### `put`

```java
public V put(K key, V value) {
    // 1. Walk down from the root: compare, go left or right.
    // 2. If compare(...) == 0, replace the value and return. No new node.
    // 3. Otherwise attach a new RED node as a leaf.
    // 4. fixAfterInsertion(node): recolor and rotate until rules 1–4 hold again.
}
```

Inserting red keeps rule 4 intact (black counts don't change). It may break rule 3 (two reds
in a row), which `fixAfterInsertion` repairs with recolorings and at most **two rotations**.
Deletion is similar with `fixAfterDeletion`, at most three rotations.

A rotation is a local pointer swap that changes shape but keeps sorted order:

```
     x                     y
    / \    rotateLeft     / \
   a   y   ─────────►    x   c
      / \               / \
     b   c             a   b
```

### The comparator is the source of truth

TreeMap decides "same key" with `compare(a, b) == 0`, **not** `equals`. When the two disagree,
you get surprising results:

```java
var prices = new TreeMap<BigDecimal, String>();
prices.put(new BigDecimal("1.0"), "first");
prices.put(new BigDecimal("1.00"), "second");
IO.println(prices.size());     // 1  (compareTo says equal)

var hashed = new HashMap<BigDecimal, String>();
hashed.put(new BigDecimal("1.0"), "first");
hashed.put(new BigDecimal("1.00"), "second");
IO.println(hashed.size());     // 2  (equals says different: scale differs)
```

Same for a case-insensitive comparator: `"Bob"` and `"bob"` become one key.

### Null keys

With natural ordering, `put(null, …)` throws `NullPointerException`. Even the first `put` into
an empty map calls `compare(key, key)` to check the key's type and nullness early. A custom
comparator such as `Comparator.nullsFirst(Comparator.naturalOrder())` can allow `null`.
Null values are fine.

### Navigation: what you pay O(log n) for

```java
var schedule = new TreeMap<LocalTime, String>();
schedule.put(LocalTime.of(9, 0), "standup");
schedule.put(LocalTime.of(11, 30), "review");
schedule.put(LocalTime.of(14, 0), "planning");

schedule.floorKey(LocalTime.of(12, 0));      // 11:30  greatest key ≤ 12:00
schedule.ceilingKey(LocalTime.of(12, 0));    // 14:00  least key ≥ 12:00
schedule.higherEntry(LocalTime.of(9, 0));    // 11:30=review
schedule.headMap(LocalTime.of(12, 0));       // {09:00, 11:30}
schedule.subMap(LocalTime.of(10, 0), true, LocalTime.of(15, 0), false);
schedule.descendingMap();
```

`headMap`, `tailMap`, `subMap`, and `descendingMap` are **live views** backed by the same tree,
not copies. Writes go through to the original map, and inserting a key outside the view's range
throws `IllegalArgumentException`.

`TreeMap` also implements `SequencedMap` (via `NavigableMap`), but `putFirst`/`putLast` throw
`UnsupportedOperationException`: the comparator decides the position, not you.

### TreeMap cheat sheet

| | |
|---|---|
| `get` / `put` / `remove` | O(log n), guaranteed |
| `firstKey`, `floorKey`, `ceilingKey`, … | O(log n) |
| Iteration | O(n), in sorted order (in-order traversal via `parent` links) |
| Key identity | `compare(...) == 0`, not `equals` |
| `null` key | not with natural ordering |
| Thread-safe | no. The concurrent sorted map is `ConcurrentSkipListMap` |

---

## Part 4: ConcurrentHashMap

### Why HashMap + a lock isn't good enough

Three ways to share a map between threads:

| Approach | Problem |
|---|---|
| Plain `HashMap` | Lost updates, corrupted size, broken chains. Undefined behaviour. |
| `Hashtable` / `Collections.synchronizedMap` | One lock for the whole map. Every read and write waits in the same line. |
| `ConcurrentHashMap` | Lock-free reads, writes lock only **one bucket**. |

Compound operations are the other problem. Even with a synchronized map, this is broken:

```java
if (!map.containsKey(key)) {      // thread A and B both see "absent"
    map.put(key, createValue());  // both put; one overwrites the other
}
```

Each call is atomic; the pair is not. ConcurrentHashMap gives you atomic compound operations
(`putIfAbsent`, `computeIfAbsent`, `compute`, `merge`) so you don't need the pair.

### A short history

- **Java 5–7:** the map was split into 16 `Segment`s, each a mini hash table extending
  `ReentrantLock`. Up to 16 writers could work in parallel. The `concurrencyLevel` constructor
  parameter set the number of segments.
- **Java 8+ (current design):** segments are gone. The structure looks like HashMap again, a
  single `Node[]` table, but each bucket is its own lock. Parallelism scales with the table
  size, not a fixed 16. `concurrencyLevel` survives only as a sizing hint.

### The data structure

```java
transient volatile Node<K,V>[] table;
private transient volatile Node<K,V>[] nextTable;   // only non-null during a resize
private transient volatile int sizeCtl;             // the control word, see below
private transient volatile long baseCount;
private transient volatile CounterCell[] counterCells;

static class Node<K,V> implements Map.Entry<K,V> {
    final int hash;
    final K key;
    volatile V val;              // volatile: readers always see the latest value
    volatile Node<K,V> next;     // volatile: readers always see a consistent chain
}
```

Compare with HashMap's `Node`: `val` and `next` are now **volatile**. That's what makes
lock-free reads safe: a write to a volatile field *happens-before* any later read of it, so a
reader that sees a node also sees its fully initialized contents.

Array elements can't be declared volatile, so bucket slots are read and written through
`Unsafe` helpers with volatile semantics:

```java
static final <K,V> Node<K,V> tabAt(Node<K,V>[] tab, int i)                          // volatile read
static final <K,V> boolean casTabAt(Node<K,V>[] tab, int i, Node<K,V> c, Node<K,V> v) // CAS
static final <K,V> void setTabAt(Node<K,V>[] tab, int i, Node<K,V> v)                // volatile write
```

### Special nodes, encoded as negative hashes

A normal key's hash is always non-negative (`spread` masks off the sign bit). Negative hashes
mark special bucket heads:

| Hash | Constant | Node type | Meaning |
|---|---|---|---|
| -1 | `MOVED` | `ForwardingNode` | "This bucket was already moved to the new table during a resize." |
| -2 | `TREEBIN` | `TreeBin` | "This bucket is a red-black tree." |
| -3 | `RESERVED` | `ReservationNode` | "Placeholder while `computeIfAbsent`/`compute` fills an empty bucket." |

```java
static final int spread(int h) {
    return (h ^ (h >>> 16)) & HASH_BITS;   // HASH_BITS = 0x7fffffff, clears the sign bit
}
```

### `get`: no locks at all

```java
public V get(Object key) {
    int h = spread(key.hashCode());
    Node<K,V>[] tab = table;
    Node<K,V> e = tabAt(tab, (tab.length - 1) & h);   // volatile read of the bucket head
    if (e == null) return null;
    if (e.hash == h && (e.key == key || key.equals(e.key)))
        return e.val;
    if (e.hash < 0)                                   // special node: forwarding or tree
        return (p = e.find(h, key)) != null ? p.val : null;
    while ((e = e.next) != null) {                    // plain chain walk
        if (e.hash == h && (e.key == key || key.equals(e.key)))
            return e.val;
    }
    return null;
}
```

Notice `e.find(...)`: a `ForwardingNode` redirects the lookup to `nextTable`, so readers keep
working during a resize without waiting for it.

### `put` (`putVal`, simplified)

```java
final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null) throw new NullPointerException();
    int hash = spread(key.hashCode());
    for (Node<K,V>[] tab = table;;) {                         // retry loop
        Node<K,V> f; int n, i, fh;
        if (tab == null || (n = tab.length) == 0)
            tab = initTable();                                // (1) lazy init via CAS on sizeCtl

        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            if (casTabAt(tab, i, null, new Node<>(hash, key, value)))
                break;                                        // (2) empty bucket: CAS, no lock
        }
        else if ((fh = f.hash) == MOVED)
            tab = helpTransfer(tab, f);                       // (3) resize in progress: help, then retry

        else {
            synchronized (f) {                                // (4) lock ONLY this bucket's head
                if (tabAt(tab, i) == f) {                     //     re-check: head may have changed
                    if (fh >= 0) { /* walk chain, replace or append at tail */ }
                    else if (f instanceof TreeBin) { /* tree insert */ }
                }
            }
            if (binCount >= TREEIFY_THRESHOLD) treeifyBin(tab, i);
            break;
        }
    }
    addCount(1L, binCount);                                   // (5) update size, maybe resize
    return null;
}
```

The four cases, in plain words:

1. **No table yet.** One thread wins a CAS on `sizeCtl` (setting it to -1) and allocates the
   table. Losers `Thread.yield()` and spin until it's ready.
2. **Empty bucket.** No lock. One CAS installs the node. If it fails, another thread got there
   first, so loop and try again. This is **optimistic concurrency**.
3. **Bucket shows `MOVED`.** A resize is running. Instead of waiting, this thread **joins the
   resize**, then retries against the new table.
4. **Occupied bucket.** `synchronized` on the first node. Two threads writing to *different*
   buckets never contend. The re-check `tabAt(tab, i) == f` handles the race where the head
   changed between reading it and acquiring the lock.

**Why `synchronized` and not `ReentrantLock`?** No extra lock object per bucket: the node
itself is the monitor. And uncontended `synchronized` is cheap. Bucket-level contention is rare
because the table is sized so most buckets hold 0 or 1 entries.

> **Virtual threads note:** before JDK 24, blocking inside `synchronized` pinned a virtual thread
> to its carrier thread. JEP 491 (JDK 24) removed that limitation, so on Java 25 CHM's bucket
> locks are fine for virtual-thread workloads. The critical sections are tiny anyway.

### Why no null keys or values?

HashMap allows nulls; CHM throws `NullPointerException`. The reason is ambiguity:

```java
V v = map.get(key);
if (v == null) {
    // Absent, or present with value null?
    // In HashMap, ask containsKey(key). In CHM, another thread may have
    // changed the map between the two calls, so the answer is meaningless.
}
```

Banning null makes `null` from `get` mean exactly one thing: absent. This is also what lets
`putIfAbsent`, `computeIfAbsent`, and `merge` use `null` as a signal.

### `sizeCtl`: one int, several meanings

| Value | Meaning |
|---|---|
| `0` | Table not yet created, use the default capacity (16) |
| positive, table null | Initial capacity to use when creating the table |
| positive, table exists | Next resize threshold, i.e. `n - (n >>> 2)` = 0.75n |
| `-1` | A thread is initializing the table |
| other negative | A resize is running; the high bits hold a resize stamp, the low bits count resizing threads |

The load factor is effectively fixed at 0.75. The constructor's `loadFactor` argument only
affects the initial size.

### Cooperative resizing (`transfer`)

This is the most elegant part of CHM. A resize doesn't stop the world, and it doesn't fall on
one unlucky thread.

```
Old table (n = 16)                              New table (n = 32)
 ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
 │ 0 │ 1 │ … │   │   │   │   │ 7 │ 8 │ … │   │   │   │   │   │15 │
 └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
   ◄── thread B claims [0..7]      ◄── thread A claims [8..15]
       (strides claimed from the END via CAS on transferIndex)
```

1. The thread that pushes size over the threshold allocates `nextTable` (2× size).
2. Work is split into **strides** of bins (at least 16 bins each). A thread claims a stride by
   CAS-decrementing `transferIndex`, moving from the end of the table toward index 0.
3. For each bin in its stride, the thread locks the bin's head, splits it into lo/hi lists
   (same trick as HashMap: `hash & n`), writes them into `nextTable` at `i` and `i + n`, and
   puts a `ForwardingNode` in the old slot.
4. Any other writer that hits a `ForwardingNode` calls `helpTransfer` and claims a stride too.
   Readers follow the forwarding node into `nextTable` and never block.
5. The last thread to finish swaps `table = nextTable` and sets the new threshold in `sizeCtl`.

More writers means a faster resize: the threads causing the pressure also do the work.

### Counting size without a hot spot

A single `AtomicLong` size counter would be a contention point: every insert from every thread
hits the same cache line. CHM uses the same striping idea as `LongAdder`:

- First try a CAS on `baseCount`.
- If that CAS fails (contention), pick a `CounterCell` based on the thread's random probe and
  add to that cell instead. The cell array grows under contention.
- `size()` = `baseCount` + sum of all cells.

Consequences:

- `size()` is a **snapshot estimate** under concurrent modification, not an exact live value.
- `size()` returns `int`. Prefer `mappingCount()`, which returns a `long`.
- Never write logic like `if (map.size() < limit) map.put(...)` expecting atomicity.

### Treeified bins: `TreeBin`

Treeification uses the same thresholds as HashMap (8 / 6 / 64). But the bucket head is a
`TreeBin` wrapper, not the root `TreeNode`, because red-black rotations change the root and the
bucket needs a stable object to `synchronized` on.

`TreeBin` also has its own small read/write lock state (`lockState` with `WRITER`, `WAITER`,
`READER` bits). If a writer is restructuring the tree, a reader doesn't wait: it walks the tree
nodes' linear `next` chain instead (every `TreeNode` is still linked as a list). Slower, but
never blocked. The lock-free read guarantee holds even for tree bins.

### Atomic compound operations

```java
var wordCounts = new ConcurrentHashMap<String, Long>();

wordCounts.merge(word, 1L, Long::sum);                     // atomic increment, no lock of your own

var cache = new ConcurrentHashMap<UserId, Profile>();
Profile profile = cache.computeIfAbsent(id, this::loadProfile);  // loadProfile runs at most once per key
```

How `computeIfAbsent` stays atomic on an empty bucket: it CASes a `ReservationNode` into the
slot, locks that placeholder, runs your function, then replaces the placeholder with the real
node. Another thread computing the same key blocks on the placeholder instead of computing twice.

Rules for the function you pass:

- **Keep it short.** It runs while holding the bucket lock. Other writers to that bucket, and
  threads that need to resize, wait for it. Don't do slow IO inside it unless you accept that.
- **Never modify the same map inside it.** Updating another key from inside `computeIfAbsent`
  can deadlock or throw `IllegalStateException: Recursive update`.

```java
// Broken: recursive update inside compute
cache.computeIfAbsent(1, k -> cache.computeIfAbsent(2, j -> "b"));
```

**`computeIfAbsent` when the key is present:** Java 8 locked the bucket even on a hit, which
made CHM a surprisingly slow cache. Since Java 9 it checks the first node without locking, so a
hit on the bucket head is lock-free.

### Weakly consistent iterators

CHM iterators **never throw `ConcurrentModificationException`**. They reflect the state of the
map at some point at or after their creation, may or may not show changes made during
iteration, and never return the same entry twice. Aggregate methods (`size`, `isEmpty`,
`containsValue`) are estimates for the same reason.

### Parallel bulk operations

CHM has built-in bulk operations that run on the common ForkJoinPool when the map has more
entries than `parallelismThreshold`:

```java
long totalWords = wordCounts.reduceValuesToLong(1_000, Long::longValue, 0L, Long::sum);
String firstLongWord = wordCounts.search(1_000, (word, count) -> word.length() > 12 ? word : null);
wordCounts.forEach(1_000, (word, count) -> IO.println(word + "=" + count));
```

Pass `Long.MAX_VALUE` to force sequential, `1` for maximum parallelism.

Need a concurrent `Set`? `ConcurrentHashMap.newKeySet()`.

### ConcurrentHashMap cheat sheet

| | |
|---|---|
| `get` | lock-free, O(1) average |
| `put` into empty bucket | CAS, no lock |
| `put` into occupied bucket | `synchronized` on that bucket's head only |
| Resize | cooperative, multi-threaded, readers never blocked |
| `size()` | striped counters, estimate under concurrency; use `mappingCount()` |
| `null` key / value | forbidden |
| Iterators | weakly consistent, never CME |
| Sorted concurrent alternative | `ConcurrentSkipListMap` |

---

## Part 5: Putting it together

### Side-by-side

| | `HashMap` | `LinkedHashMap` | `TreeMap` | `ConcurrentHashMap` |
|---|---|---|---|---|
| Structure | array of buckets (list → tree) | HashMap + doubly linked list | red-black tree | array of buckets, volatile, per-bin locks |
| Key identity | `hashCode` + `equals` | `hashCode` + `equals` | `compare` / `compareTo` | `hashCode` + `equals` |
| get / put | O(1) avg | O(1) avg | O(log n) | O(1) avg |
| Iteration order | undefined | insertion or access | sorted | undefined |
| `null` key | yes (bucket 0) | yes | no (natural ordering) | no |
| `null` value | yes | yes | yes | no |
| Thread-safe | no | no | no | yes |
| Iterator on concurrent change | fail-fast (CME) | fail-fast (CME) | fail-fast (CME) | weakly consistent |
| Extra memory per entry | baseline | +2 pointers | 3 pointers + color, no table | similar to HashMap + counters |

### Decision guide

1. **Shared between threads and mutated?** `ConcurrentHashMap`. Sorted too? `ConcurrentSkipListMap`.
2. **Need sorted keys or range queries** (`floor`, `ceiling`, `subMap`)? `TreeMap`.
3. **Need predictable iteration order, or an LRU cache?** `LinkedHashMap`.
4. **Otherwise:** `HashMap`, pre-sized with `HashMap.newHashMap(n)` when you know `n`.
5. **Never changes after creation?** `Map.of(...)` / `Map.copyOf(...)`: immutable, no nulls,
   and safe to share between threads.

### Misconceptions to address on camera

| Myth | Reality |
|---|---|
| "HashMap is O(1), full stop." | Average O(1). Worst case O(log n) in a tree bin, or O(n) with non-Comparable colliding keys. |
| "Java 8 made HashMap thread-safe because the infinite loop is gone." | It removed one symptom. Concurrent writes still lose data. |
| "`new HashMap<>(100)` holds 100 entries without resizing." | It resizes at 97. Use `HashMap.newHashMap(100)`. |
| "ConcurrentHashMap locks the whole map on write." | It locks one bucket. Reads never lock. |
| "ConcurrentHashMap makes my check-then-act code safe." | Only single calls are atomic. Use `putIfAbsent` / `compute` / `merge`. |
| "`ConcurrentHashMap.size()` is exact." | It's a snapshot sum of striped counters. |
| "TreeMap uses `equals` to find keys." | It only uses the comparator. `BigDecimal("1.0")` and `"1.00"` collide. |
| "`get` never changes a map." | Not for an access-ordered `LinkedHashMap`. |

### Hands-on exercises

1. **Force a treeify.** Write a `record BadKey(int id)` whose `hashCode()` always returns 42.
   Insert 10,000 into a `HashMap` and time `get`. Then make it `implements Comparable<BadKey>`
   and time again. Explain the difference.
2. **Watch the resize.** Insert into `new HashMap<>(100)` and `HashMap.newHashMap(100)` and use
   a debugger to watch `table.length` change.
3. **Break a HashMap.** Have 8 threads each `put` 100,000 distinct keys into a shared `HashMap`.
   Print `size()`. Repeat with `ConcurrentHashMap`.
4. **Lost update.** 8 threads each run `map.put(k, map.get(k) + 1)` 100,000 times on a
   `ConcurrentHashMap`. Print the result, then rewrite with `merge`.
5. **LRU.** Build the `LruCache` above with capacity 3, access keys in a pattern, and predict
   which one gets evicted before you run it.
6. **Comparator vs equals.** Put `"Bob"` and `"bob"` into a
   `TreeMap<>(String.CASE_INSENSITIVE_ORDER)` and into a `HashMap`. Compare sizes.
