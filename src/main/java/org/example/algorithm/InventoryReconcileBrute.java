package org.example.algorithm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.StringTokenizer;

/**
 * 暴力/直观解法：建图 + BFS 展开每个连通分量
 *
 * 思路：
 *  - 把每条约束当作一条无向边。
 *  - 对每个连通分量选任一节点作为起点 start，令 c[start] = x（未知的自由变量）。
 *  - BFS 展开：每个节点得到 c[v] = sign_v * x + offset_v。
 *  - 若某条边的另一端已被访问过：
 *      * 两次给出的表达式若不一致 → 检查是否能反推出 x 的整数值；不一致或非整数 → 无解。
 *  - 若整个分量 BFS 完仍没有推出 x，则该分量需要盘点 1 个；否则 0 个。
 *
 * 复杂度: O(n + m) —— 建图与 BFS 都是线性
 */
public class InventoryReconcileBrute {

    // 邻接表条目: [neighbor, type(0=D/1=S), k, dir(0=self is a, 1=self is b)]
    static List<long[]>[] adj;
    static int[] sgn;
    static long[] off;
    static boolean[] visited;

    public static void main(String[] args) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        int T = Integer.parseInt(br.readLine().trim());
        StringBuilder out = new StringBuilder();

        while (T-- > 0) {
            StringTokenizer st = new StringTokenizer(br.readLine());
            int n = Integer.parseInt(st.nextToken());
            int m = Integer.parseInt(st.nextToken());

            adj = new List[n + 1];
            for (int i = 1; i <= n; i++) adj[i] = new ArrayList<>();
            sgn = new int[n + 1];
            off = new long[n + 1];
            visited = new boolean[n + 1];

            for (int i = 0; i < m; i++) {
                st = new StringTokenizer(br.readLine());
                char type = st.nextToken().charAt(0);
                int a = Integer.parseInt(st.nextToken());
                int b = Integer.parseInt(st.nextToken());
                long k = Long.parseLong(st.nextToken());
                int t = (type == 'D') ? 0 : 1;
                adj[a].add(new long[]{b, t, k, 0}); // 从 a 看，a 是原式的 a
                adj[b].add(new long[]{a, t, k, 1}); // 从 b 看，b 是原式的 b
            }

            boolean valid = true;
            int unfixedComponents = 0;

            for (int start = 1; start <= n && valid; start++) {
                if (visited[start]) continue;

                // BFS
                Deque<Integer> queue = new ArrayDeque<>();
                queue.offer(start);
                visited[start] = true;
                sgn[start] = 1;
                off[start] = 0L;

                boolean componentFixed = false;
                long fixedX = 0L;

                bfs:
                while (!queue.isEmpty()) {
                    int u = queue.poll();
                    for (long[] e : adj[u]) {
                        int v = (int) e[0];
                        int t = (int) e[1];
                        long k = e[2];
                        int dir = (int) e[3];

                        // 从 u 计算 v 的表达式 c[v] = newSign * x + newOffset
                        int newSign;
                        long newOffset;
                        if (t == 0) { // D 型：c[a] - c[b] = k
                            if (dir == 0) {              // u = a, v = b  ⇒  c[v] = c[u] - k
                                newSign = sgn[u];
                                newOffset = off[u] - k;
                            } else {                      // u = b, v = a  ⇒  c[v] = c[u] + k
                                newSign = sgn[u];
                                newOffset = off[u] + k;
                            }
                        } else {       // S 型：c[a] + c[b] = k  ⇒  c[v] = k - c[u]
                            newSign = -sgn[u];
                            newOffset = k - off[u];
                        }

                        if (!visited[v]) {
                            visited[v] = true;
                            sgn[v] = newSign;
                            off[v] = newOffset;
                            queue.offer(v);
                        } else {
                            // 已访问，检查一致性
                            if (sgn[v] == newSign) {
                                if (off[v] != newOffset) { // (s-s)*x = diff ≠ 0，矛盾
                                    valid = false;
                                    break bfs;
                                }
                                // 完全一致，不做处理
                            } else {
                                // sgn[v] - newSign = ±2, 可以反解出 x
                                long diff = newOffset - off[v];
                                int coefDiff = sgn[v] - newSign;   // ±2
                                if (diff % coefDiff != 0) {         // 整数性
                                    valid = false;
                                    break bfs;
                                }
                                long xVal = diff / coefDiff;
                                if (componentFixed) {
                                    if (fixedX != xVal) {
                                        valid = false;
                                        break bfs;
                                    }
                                } else {
                                    componentFixed = true;
                                    fixedX = xVal;
                                }
                            }
                        }
                    }
                }

                if (valid && !componentFixed) unfixedComponents++;
            }

            if (!valid) {
                out.append("NO\n-1\n");
            } else {
                out.append("YES\n").append(unfixedComponents).append('\n');
            }
        }
        System.out.print(out);
    }
}
