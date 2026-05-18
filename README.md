# Concurrent Skip List

Cultus project. Thread-safe skip list implementation in Java. Only one file (`Main.java`)

## run the code

```bash
javac Main.java
java Main
```

## Inside file

4 tasks for this project that I did in order:

1. Built a non-concurrent skip list first (`BasicSkipList`) with insert, delete, and search. just to make sure the pointer logic works before adding threads.
2. Built (`ConcurrentSkipList`) using per-node locking.
3. Wrote a stress test that runs 8 threads, each doing 30000 random operations (mix of insert, delete, search).
4. Benchmarked it against a `SynchronizedLinkedList` baseline at 1, 2, 4, and 8 threads.

## Synchronization strategy

I went with **fine-grained locking** (per-node `ReentrantLock`) instead of locking the whole structure. The reason is because, if I just use one lock, the multithreading is pointless because only one thread can access it at a time. With per-node locks, threads modifying different parts of the list can actually run in parallel.

Here is how insert works:

1. Search first to get the predecessor nodes at each level.
2. Lock all the predecessor nodes, sorted by value first (to avoid deadlock).
3. Validate again after getting the locks, to check if the links are still the same as during the search. If another thread already changed something, retry again.
4. If it's still valid, update the pointers.
5. Just unlock everything.

Delete is similar, but it also locks the node being removed. Before unlinking, the node is marked `deleted = true`, so any search running in parallel can tell that this node is no longer valid.

Search (`contains`) is **lock-free**. From the top level to level 0, then checks if the node exists and `deleted == false`. It doesn't lock anything, so search stays fast even when there are lots of inserts and deletes happening at the same time.

## Test results

Output from my run:

```text
Single-thread tests passed
Stress test passed, final size = 2473

Benchmark: 50% search, 25% insert, 25% delete
Structure                    Threads  Millis       Ops/sec      Size      
ConcurrentSkipList           1        31.25        1280119      4947      
SynchronizedLinkedList       1        348.11       114908       4947      
ConcurrentSkipList           2        37.57        2129285      5044      
SynchronizedLinkedList       2        819.86       97577        5039      
ConcurrentSkipList           4        52.35        3056422      5058      
SynchronizedLinkedList       4        1899.27      84243        4975      
ConcurrentSkipList           8        50.16        6379700      5000      
SynchronizedLinkedList       8        4455.05      71829        5018    
```

## Bottlenecks I noticed

**The synchronized linked list doesn't scale at all.** Look at the numbers - from 1 thread (114k ops/sec) to 8 threads (71k ops/sec), it actually gets slower instead of faster. This is because every method is synchronized on the same object, so only 1 thread can run at a time. The rest just wait. More threads means more contention, which ends up being even slower than single-thread.

**The skip list scales much better.** From 1 thread (1.28M ops/sec) to 8 threads (6.37M ops/sec), about 5x improvement. But I noticed that the gain isn't perfectly linear - going from 4 threads to 8 threads only doubled the throughput (3M to 6.3M), not tripled. My guess is that the retry rate increases as more threads pile up - there's a higher chance some other thread already modified the list, so validation fails and the operation retries.

**Delete was the trickiest part.** I had a bug here at first - a skip list node can appear at more than one level, so if you only unlink it from level 0, the upper levels still hold a reference to the "deleted" node. The validate function is what helped me catch this bug.

## Complexity analysis

Skip list:
- Search: expected `O(log n)`. The upper levels act like shortcuts, so on average it only takes about log n steps.
- Insert: expected `O(log n)`. Search first (log n), then update the forward pointers based on the random height of the new node.
- Delete: expected `O(log n)`. Search first, then unlink the node from every level where it appears.

Synchronized linked list:
- All operations are `O(n)`. Only one level, so worst case it has to scan from head until it finds the value (or hits the end).

So in theory the skip list is already faster because of log n vs n. But the bigger factor in the benchmark is the concurrency. The synchronized linked list blocks itself on its own lock, while the skip list can process multiple operations in parallel.
