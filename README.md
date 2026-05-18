# Concurrent Skip List

Cultus project. Thread-safe skip list implementation in Java. Everything is in one file (`Main.java`) so it's easy to compile and submit.

## How to run

```bash
javac Main.java
java Main
```

## What's inside

There are 4 tasks for this project, and I did them in this order:

1. Built a non-concurrent skip list first (`BasicSkipList`) with insert, delete, and search. This was just to make sure the pointer logic works before adding threads.
2. Built the concurrent version (`ConcurrentSkipList`) using per-node locking.
3. Wrote a stress test that runs 8 threads, each doing 30000 random operations (mix of insert, delete, search).
4. Benchmarked it against a `SynchronizedLinkedList` baseline at 1, 2, 4, and 8 threads.

## Synchronization strategy

I went with **fine-grained locking** (per-node `ReentrantLock`) instead of one big lock for the whole structure. The reason is simple: if I just use one lock, the multithreading is pointless because only one thread can access it at a time. With per-node locks, threads modifying different parts of the list can actually run in parallel.

Here's how insert works:

1. Search first (without locking) to get the predecessor nodes at each level.
2. Lock all the predecessor nodes, sorted by value first so the order is consistent (this matters to avoid deadlock).
3. Validate again after getting the locks - check if the links are still the same as during the search. If another thread already changed something, retry from the beginning.
4. If it's still valid, update the pointers.
5. Unlock everything.

Delete is similar, but it also locks the node being removed. Before unlinking, the node is marked `deleted = true` (volatile) first, so any search running in parallel can immediately tell that this node is no longer valid.

Search (`contains`) is **lock-free**. It just walks down from the top level to level 0, then checks if the node exists and `deleted == false`. Since it doesn't lock anything, search stays fast even when there are lots of inserts and deletes happening at the same time.

## Test results

Output from my run:

```text
Single-thread tests passed
Stress test passed, final size = 2443

Benchmark: 50% search, 25% insert, 25% delete
Structure                    Threads  Millis       Ops/sec      Size
ConcurrentSkipList           1        24.87        1608642      4947
SynchronizedLinkedList       1        210.77       189781       4947
ConcurrentSkipList           2        25.33        3158896      5014
SynchronizedLinkedList       2        601.06       133098       5056
ConcurrentSkipList           4        15.85        10091581     5018
SynchronizedLinkedList       4        1360.91      117568       5021
ConcurrentSkipList           8        20.16        15871441     5002
SynchronizedLinkedList       8        3049.86      104923       5030
```

## Bottlenecks I noticed

**The synchronized linked list doesn't scale at all.** Look at the numbers - from 1 thread (189k ops/sec) to 8 threads (104k ops/sec), it actually gets slower instead of faster. This is because every method is synchronized on the same object, so only 1 thread can run at a time. The rest just wait. More threads means more contention, which ends up being even slower than single-thread.

**The skip list scales much better.** From 1 thread (1.6M ops/sec) to 8 threads (15.8M ops/sec), almost 10x improvement. But I noticed that going from 4 to 8 threads, the gain isn't proportional anymore (10M to 15.8M, not 20M). My guess is that the retry rate increases as more threads pile up - there's a higher chance some other thread already modified the list, so validation fails and the operation retries.

**Delete was the trickiest part.** I had a bug here at first - a skip list node can appear at more than one level, so if you only unlink it from level 0, the upper levels still hold a reference to the "deleted" node. The validate function is what helped me catch this bug.

## Complexity analysis

Skip list:
- Search: expected `O(log n)`. The upper levels act like shortcuts, so on average it only takes about log n steps.
- Insert: expected `O(log n)`. Search first (log n), then update the forward pointers based on the random height of the new node.
- Delete: expected `O(log n)`. Search first, then unlink the node from every level where it appears.

Synchronized linked list:
- All operations are `O(n)`. Only one level, so worst case it has to scan from head until it finds the value (or hits the end).

So in theory the skip list is already faster because of log n vs n. But the bigger factor in the benchmark is the concurrency - the synchronized linked list blocks itself on its own lock, while the skip list can process multiple operations in parallel.
