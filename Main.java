import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class Main {
    static final int MAX_LEVEL = 12;

    public static void main(String[] args) throws Exception {
        singleThreadCheck();
        stressTest();
        benchmark();
    }

    static void singleThreadCheck() {
        IntSet basic = new BasicSkipList();
        check(basic.insert(10), "basic insert 10");
        check(basic.insert(5), "basic insert 5");
        check(basic.insert(20), "basic insert 20");
        check(basic.contains(10), "basic contains 10");
        check(!basic.contains(99), "basic missing 99");
        check(basic.delete(10), "basic delete 10");
        check(!basic.contains(10), "basic deleted 10");

        ConcurrentSkipList concurrent = new ConcurrentSkipList();
        check(concurrent.insert(10), "concurrent insert 10");
        check(concurrent.insert(5), "concurrent insert 5");
        check(concurrent.insert(20), "concurrent insert 20");
        check(concurrent.contains(10), "concurrent contains 10");
        check(!concurrent.contains(99), "concurrent missing 99");
        check(concurrent.delete(10), "concurrent delete 10");
        check(!concurrent.contains(10), "concurrent deleted 10");
        check(concurrent.validate(), "concurrent validate");

        System.out.println("Single-thread tests passed");
    }

    static void stressTest() throws Exception {
        ConcurrentSkipList list = new ConcurrentSkipList();
        int threads = 8;
        int opsPerThread = 30000;
        int keyRange = 5000;

        for (int i = 0; i < keyRange; i += 3) {
            list.insert(i);
        }

        runParallel(threads, opsPerThread, workerId -> {
            Random rng = new Random(7000L + workerId);
            for (int i = 0; i < opsPerThread; i++) {
                int key = rng.nextInt(keyRange);
                int op = rng.nextInt(100);
                if (op < 50) {
                    list.contains(key);
                } else if (op < 75) {
                    list.insert(key);
                } else {
                    list.delete(key);
                }
            }
        });

        check(list.validate(), "stress validate");
        System.out.println("Stress test passed, final size = " + list.size());
    }

    static void benchmark() throws Exception {
        int[] threadCounts = {1, 2, 4, 8};
        int opsPerThread = 40000;
        int keyRange = 10000;

        System.out.println();
        System.out.println("Benchmark: 50% search, 25% insert, 25% delete");
        System.out.printf("%-28s %-8s %-12s %-12s %-10s%n", "Structure", "Threads", "Millis", "Ops/sec", "Size");

        for (int threads : threadCounts) {
            benchmarkOne("ConcurrentSkipList", ConcurrentSkipList::new, threads, opsPerThread, keyRange);
            benchmarkOne("SynchronizedLinkedList", SynchronizedLinkedList::new, threads, opsPerThread, keyRange);
        }
    }

    static void benchmarkOne(String label, Supplier<IntSet> supplier, int threads, int opsPerThread, int keyRange) throws Exception {
        IntSet set = supplier.get();
        for (int i = 0; i < keyRange; i += 2) {
            set.insert(i);
        }

        long start = System.nanoTime();
        runParallel(threads, opsPerThread, workerId -> {
            Random rng = new Random(1000L + workerId);
            for (int i = 0; i < opsPerThread; i++) {
                int key = rng.nextInt(keyRange);
                int op = rng.nextInt(100);
                if (op < 50) {
                    set.contains(key);
                } else if (op < 75) {
                    set.insert(key);
                } else {
                    set.delete(key);
                }
            }
        });
        long elapsed = System.nanoTime() - start;

        double millis = elapsed / 1_000_000.0;
        double opsPerSecond = (threads * opsPerThread) / (elapsed / 1_000_000_000.0);
        System.out.printf("%-28s %-8d %-12.2f %-12.0f %-10d%n", label, threads, millis, opsPerSecond, set.size());
    }

    static void runParallel(int threads, int opsPerThread, Worker worker) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            int workerId = t;
            pool.submit(() -> {
                try {
                    startGate.await();
                    worker.run(workerId);
                } catch (Throwable e) {
                    failures.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await();
        pool.shutdown();

        if (failures.get() > 0) {
            throw new IllegalStateException("Parallel run had failures: " + failures.get());
        }
    }

    static void check(boolean condition, String name) {
        if (!condition) {
            throw new IllegalStateException("Check failed: " + name);
        }
    }

    interface Worker {
        void run(int workerId) throws Exception;
    }

    interface IntSet {
        boolean insert(int value);
        boolean delete(int value);
        boolean contains(int value);
        int size();
    }

    static class BasicSkipList implements IntSet {
        private final Node head = new Node(Integer.MIN_VALUE, MAX_LEVEL);
        private final Node tail = new Node(Integer.MAX_VALUE, MAX_LEVEL);
        private final Random rng = new Random(1);
        private int currentLevel = 0;
        private int size = 0;

        BasicSkipList() {
            Arrays.fill(head.next, tail);
        }

        public boolean insert(int value) {
            Node[] update = new Node[MAX_LEVEL + 1];
            Node cur = head;

            for (int level = currentLevel; level >= 0; level--) {
                while (cur.next[level].value < value) {
                    cur = cur.next[level];
                }
                update[level] = cur;
            }

            Node found = cur.next[0];
            if (found.value == value) {
                return false;
            }

            int newLevel = randomLevel();
            if (newLevel > currentLevel) {
                for (int i = currentLevel + 1; i <= newLevel; i++) {
                    update[i] = head;
                }
                currentLevel = newLevel;
            }

            Node node = new Node(value, newLevel);
            for (int i = 0; i <= newLevel; i++) {
                node.next[i] = update[i].next[i];
                update[i].next[i] = node;
            }
            size++;
            return true;
        }

        public boolean delete(int value) {
            Node[] update = new Node[MAX_LEVEL + 1];
            Node cur = head;

            for (int level = currentLevel; level >= 0; level--) {
                while (cur.next[level].value < value) {
                    cur = cur.next[level];
                }
                update[level] = cur;
            }

            Node found = cur.next[0];
            if (found.value != value) {
                return false;
            }

            for (int i = 0; i <= currentLevel; i++) {
                if (update[i].next[i] != found) {
                    break;
                }
                update[i].next[i] = found.next[i];
            }

            while (currentLevel > 0 && head.next[currentLevel] == tail) {
                currentLevel--;
            }
            size--;
            return true;
        }

        public boolean contains(int value) {
            Node cur = head;
            for (int level = currentLevel; level >= 0; level--) {
                while (cur.next[level].value < value) {
                    cur = cur.next[level];
                }
            }
            return cur.next[0].value == value;
        }

        public int size() {
            return size;
        }

        private int randomLevel() {
            int level = 0;
            while (level < MAX_LEVEL && rng.nextBoolean()) {
                level++;
            }
            return level;
        }

        static class Node {
            final int value;
            final int topLevel;
            final Node[] next;

            Node(int value, int topLevel) {
                this.value = value;
                this.topLevel = topLevel;
                this.next = new Node[topLevel + 1];
            }
        }
    }

    static class ConcurrentSkipList implements IntSet {
        private final Node head = new Node(Integer.MIN_VALUE, MAX_LEVEL);
        private final Node tail = new Node(Integer.MAX_VALUE, MAX_LEVEL);
        private final AtomicInteger size = new AtomicInteger(0);

        ConcurrentSkipList() {
            Arrays.fill(head.next, tail);
        }

        public boolean insert(int value) {
            int topLevel = randomLevel();
            Node[] preds = new Node[MAX_LEVEL + 1];
            Node[] succs = new Node[MAX_LEVEL + 1];

            while (true) {
                int foundLevel = find(value, preds, succs);
                if (foundLevel != -1) {
                    return false;
                }

                ArrayList<Node> locked = lockNodes(preds, topLevel, null);
                try {
                    if (!validForInsert(preds, succs, topLevel)) {
                        continue;
                    }

                    Node node = new Node(value, topLevel);
                    for (int level = 0; level <= topLevel; level++) {
                        node.next[level] = succs[level];
                    }
                    for (int level = 0; level <= topLevel; level++) {
                        preds[level].next[level] = node;
                    }
                    size.incrementAndGet();
                    return true;
                } finally {
                    unlockNodes(locked);
                }
            }
        }

        public boolean delete(int value) {
            Node[] preds = new Node[MAX_LEVEL + 1];
            Node[] succs = new Node[MAX_LEVEL + 1];

            while (true) {
                int foundLevel = find(value, preds, succs);
                if (foundLevel == -1) {
                    return false;
                }

                Node target = succs[0];
                int topLevel = target.topLevel;
                ArrayList<Node> locked = lockNodes(preds, topLevel, target);
                try {
                    if (target.deleted || !validForDelete(preds, target, topLevel)) {
                        continue;
                    }

                    target.deleted = true;
                    for (int level = topLevel; level >= 0; level--) {
                        preds[level].next[level] = target.next[level];
                    }
                    size.decrementAndGet();
                    return true;
                } finally {
                    unlockNodes(locked);
                }
            }
        }

        public boolean contains(int value) {
            Node cur = head;
            for (int level = MAX_LEVEL; level >= 0; level--) {
                Node next = cur.next[level];
                while (next.value < value) {
                    cur = next;
                    next = cur.next[level];
                }
            }
            Node found = cur.next[0];
            return found.value == value && !found.deleted;
        }

        public int size() {
            return size.get();
        }

        boolean validate() {
            int count = 0;
            int prev = Integer.MIN_VALUE;
            Node cur = head.next[0];

            while (cur != tail) {
                if (cur.deleted || cur.value <= prev) {
                    return false;
                }
                prev = cur.value;
                count++;
                cur = cur.next[0];
            }

            if (count != size.get()) {
                return false;
            }

            for (int level = 1; level <= MAX_LEVEL; level++) {
                prev = Integer.MIN_VALUE;
                cur = head.next[level];
                while (cur != tail) {
                    if (cur.deleted || cur.value <= prev || cur.topLevel < level) {
                        return false;
                    }
                    prev = cur.value;
                    cur = cur.next[level];
                }
            }

            return true;
        }

        private int find(int value, Node[] preds, Node[] succs) {
            int foundLevel = -1;
            Node pred = head;

            for (int level = MAX_LEVEL; level >= 0; level--) {
                Node curr = pred.next[level];
                while (curr.value < value) {
                    pred = curr;
                    curr = pred.next[level];
                }
                if (foundLevel == -1 && curr.value == value && !curr.deleted) {
                    foundLevel = level;
                }
                preds[level] = pred;
                succs[level] = curr;
            }

            return foundLevel;
        }

        private boolean validForInsert(Node[] preds, Node[] succs, int topLevel) {
            for (int level = 0; level <= topLevel; level++) {
                if (preds[level].deleted || succs[level].deleted || preds[level].next[level] != succs[level]) {
                    return false;
                }
            }
            return true;
        }

        private boolean validForDelete(Node[] preds, Node target, int topLevel) {
            for (int level = 0; level <= topLevel; level++) {
                if (preds[level].deleted || preds[level].next[level] != target) {
                    return false;
                }
            }
            return true;
        }

        private ArrayList<Node> lockNodes(Node[] preds, int topLevel, Node extra) {
            ArrayList<Node> nodes = new ArrayList<>();
            for (int level = 0; level <= topLevel; level++) {
                addUnique(nodes, preds[level]);
            }
            if (extra != null) {
                addUnique(nodes, extra);
            }
            nodes.sort(Comparator.comparingInt(n -> n.value));
            for (Node n : nodes) {
                n.lock.lock();
            }
            return nodes;
        }

        private void unlockNodes(ArrayList<Node> nodes) {
            for (int i = nodes.size() - 1; i >= 0; i--) {
                nodes.get(i).lock.unlock();
            }
        }

        private void addUnique(ArrayList<Node> nodes, Node node) {
            for (Node existing : nodes) {
                if (existing == node) {
                    return;
                }
            }
            nodes.add(node);
        }

        private int randomLevel() {
            int level = 0;
            while (level < MAX_LEVEL && ThreadLocalRandom.current().nextBoolean()) {
                level++;
            }
            return level;
        }

        static class Node {
            final int value;
            final int topLevel;
            final Node[] next;
            final ReentrantLock lock = new ReentrantLock();
            volatile boolean deleted = false;

            Node(int value, int topLevel) {
                this.value = value;
                this.topLevel = topLevel;
                this.next = new Node[topLevel + 1];
            }
        }
    }

    static class SynchronizedLinkedList implements IntSet {
        private final Node head = new Node(Integer.MIN_VALUE);
        private final Node tail = new Node(Integer.MAX_VALUE);
        private int size = 0;

        SynchronizedLinkedList() {
            head.next = tail;
        }

        public synchronized boolean insert(int value) {
            Node prev = head;
            Node cur = head.next;
            while (cur.value < value) {
                prev = cur;
                cur = cur.next;
            }
            if (cur.value == value) {
                return false;
            }
            Node node = new Node(value);
            node.next = cur;
            prev.next = node;
            size++;
            return true;
        }

        public synchronized boolean delete(int value) {
            Node prev = head;
            Node cur = head.next;
            while (cur.value < value) {
                prev = cur;
                cur = cur.next;
            }
            if (cur.value != value) {
                return false;
            }
            prev.next = cur.next;
            size--;
            return true;
        }

        public synchronized boolean contains(int value) {
            Node cur = head.next;
            while (cur.value < value) {
                cur = cur.next;
            }
            return cur.value == value;
        }

        public synchronized int size() {
            return size;
        }

        static class Node {
            final int value;
            Node next;

            Node(int value) {
                this.value = value;
            }
        }
    }
}