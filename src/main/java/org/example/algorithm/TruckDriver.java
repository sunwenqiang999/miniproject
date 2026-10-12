package org.example.algorithm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Scanner;

/**
 * 货车司机多多：带油量约束的最短路。
 *
 * 状态：(city, fuel)，dist[city][fuel] 表示到达该状态的最短耗时。
 * 转移：
 *   1. 加油：fuel < F 时，(city, fuel) -> (city, fuel+1)，代价 p[city]
 *   2. 走路：fuel >= c 时，(city, fuel) -> (v, fuel-c)，代价 t
 * 使用 Dijkstra 求解从 (1, F) 出发到 (N, *) 的最短耗时。
 *
 * 复杂度：O(N * F * log(N * F) + M * F)，N≤1000, M≤10000, F≤100，足够通过。
 */
public class TruckDriver {

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int N = sc.nextInt();
        int M = sc.nextInt();
        int F = sc.nextInt();

        int[] p = new int[N + 1];
        for (int i = 1; i <= N; i++) {
            p[i] = sc.nextInt();
        }

        // 邻接表：graph[u] = list of {v, c, t}
        List<int[]>[] graph = new List[N + 1];
        for (int i = 0; i <= N; i++) {
            graph[i] = new ArrayList<>();
        }
        for (int i = 0; i < M; i++) {
            int u = sc.nextInt();
            int v = sc.nextInt();
            int c = sc.nextInt();
            int t = sc.nextInt();
            graph[u].add(new int[]{v, c, t});
            graph[v].add(new int[]{u, c, t}); // 双向道路
        }

        System.out.println(shortestTime(N, F, p, graph));
    }

    private static long shortestTime(int N, int F, int[] p, List<int[]>[] graph) {
        final long INF = Long.MAX_VALUE / 4;
        long[][] dist = new long[N + 1][F + 1];
        for (long[] row : dist) {
            Arrays.fill(row, INF);
        }
        dist[1][F] = 0;

        // 小顶堆：{time, city, fuel}
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        pq.offer(new long[]{0L, 1L, (long) F});

        while (!pq.isEmpty()) {
            long[] cur = pq.poll();
            long time = cur[0];
            int city = (int) cur[1];
            int fuel = (int) cur[2];

            if (time > dist[city][fuel]) continue; // 过期状态
            if (city == N) return time;            // 第一次弹出到达 N 的状态即最优

            // 1) 在当前城市加 1 单位油
            if (fuel < F) {
                long nt = time + p[city];
                if (nt < dist[city][fuel + 1]) {
                    dist[city][fuel + 1] = nt;
                    pq.offer(new long[]{nt, city, fuel + 1});
                }
            }

            // 2) 走出去一条边
            for (int[] e : graph[city]) {
                int v = e[0], c = e[1], t = e[2];
                if (fuel >= c) {
                    long nt = time + t;
                    int nf = fuel - c;
                    if (nt < dist[v][nf]) {
                        dist[v][nf] = nt;
                        pq.offer(new long[]{nt, v, nf});
                    }
                }
            }
        }

        // 兜底：所有可达状态里挑最小的（正常情况上面 return 已经处理）
        long ans = INF;
        for (int f = 0; f <= F; f++) {
            ans = Math.min(ans, dist[N][f]);
        }
        return ans >= INF ? -1 : ans;
    }
}
