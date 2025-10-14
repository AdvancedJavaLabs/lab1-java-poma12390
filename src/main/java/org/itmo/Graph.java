package org.itmo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicIntegerArray;

class Graph {
    private final int V;
    private final ArrayList<Integer>[] adj;

    Graph(int vertices) {
        this.V = vertices;
        this.adj = new ArrayList[vertices];
        for (int i = 0; i < vertices; i++) {
            adj[i] = new ArrayList<>();
        }
    }

    void addEdge(int u, int v) {
        if (u < 0 || v < 0 || u >= V || v >= V) return;
        adj[u].add(v);
        adj[v].add(u);
    }

    void bfs(int startVertex) {
        if (startVertex < 0 || startVertex >= V) return;
        boolean[] visited = new boolean[V];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        visited[startVertex] = true;
        q.add(startVertex);

        while (!q.isEmpty()) {
            int u = q.pollFirst();
            for (int v : adj[u]) {
                if (!visited[v]) {
                    visited[v] = true;
                    q.addLast(v);
                }
            }
        }
    }

    private static boolean markVisited(AtomicIntegerArray bits, int v) {
        int idx = v >>> 5;
        int mask = 1 << (v & 31);
        int old;
        do {
            old = bits.get(idx);
            if ((old & mask) != 0) return false;       // уже посещена
        } while (!bits.compareAndSet(idx, old, old | mask));
        return true;
    }

    void parallelBFS(int startVertex) {
        if (startVertex < 0 || startVertex >= V) return;

        final int n = V;

        final int maxThreads = Math.max(
                1,
                Math.min(
                        Integer.getInteger("bfs.threads",
                                Math.min(Runtime.getRuntime().availableProcessors(), 32)),
                        128
                )
        );

        final AtomicIntegerArray visitedBits = new AtomicIntegerArray((n + 31) >>> 5);
        final int[] dist = new int[n];
        Arrays.fill(dist, -1);

        markVisited(visitedBits, startVertex);
        dist[startVertex] = 0;

        int[] frontier = new int[]{startVertex};
        int frontierSize = 1;
        int level = 0;

        final ExecutorService pool = Executors.newFixedThreadPool(maxThreads, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("bfs-worker-" + t.getId());
            return t;
        });

        try {
            final int GRAIN = Math.max(256, Integer.getInteger("bfs.grain", 1024));

            while (true) {
                int tasks = Math.min(maxThreads, Math.max(1, (frontierSize + GRAIN - 1) / GRAIN));

                if (tasks == 1) {
                    ArrayList<Integer> nextList = new ArrayList<>(Math.min(frontierSize * 2, n));
                    for (int i = 0; i < frontierSize; i++) {
                        int u = frontier[i];
                        for (int v : adj[u]) {
                            if (markVisited(visitedBits, v)) {
                                dist[v] = level + 1;
                                nextList.add(v);
                            }
                        }
                    }
                    if (nextList.isEmpty()) break;
                    int[] next = new int[nextList.size()];
                    for (int i = 0; i < next.length; i++) next[i] = nextList.get(i);
                    frontier = next;
                    frontierSize = next.length;
                    level++;
                    continue;
                }

                final CountDownLatch latch = new CountDownLatch(tasks);
                final int chunk = (frontierSize + tasks - 1) / tasks;

                final ArrayList<Integer>[] buckets = new ArrayList[tasks];
                for (int t = 0; t < tasks; t++) {
                    buckets[t] = new ArrayList<>(Math.min(Math.max(16, chunk * 2), 1 << 20));
                }

                for (int t = 0; t < tasks; t++) {
                    final int ti = t;
                    final int from = ti * chunk;
                    final int to = Math.min(frontierSize, from + chunk);
                    if (from >= to) { latch.countDown(); continue; }

                    int[] finalFrontier = frontier;
                    int finalLevel = level;
                    pool.execute(() -> {
                        try {
                            ArrayList<Integer> out = buckets[ti];
                            for (int i = from; i < to; i++) {
                                int u = finalFrontier[i];
                                for (int v : adj[u]) {
                                    if (markVisited(visitedBits, v)) {
                                        dist[v] = finalLevel + 1;
                                        out.add(v);
                                    }
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                try {
                    latch.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

                int nextSize = 0;
                for (int t = 0; t < tasks; t++) nextSize += buckets[t].size();
                if (nextSize == 0) break;

                int[] next = new int[nextSize];
                int p = 0;
                for (int t = 0; t < tasks; t++) {
                    ArrayList<Integer> b = buckets[t];
                    for (int v : b) next[p++] = v;
                }

                frontier = next;
                frontierSize = nextSize;
                level++;
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
