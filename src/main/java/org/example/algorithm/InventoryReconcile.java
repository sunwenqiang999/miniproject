package org.example.algorithm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * 最优解：带符号带权并查集
 *
 * 每个节点相对于根的表达式:  c[i] = sgn[i] * c[root] + off[i]
 *   - sgn[i] ∈ {+1, -1}
 *   - 若 fixedR[root]=true, 则 valR[root] 保存了 c[root] 的具体值
 *
 * 处理一条约束 (α * c[ra] + β * c[rb] = K):
 *   - D a b d: c[a]-c[b]=d  =>  α = sgn[a], β = -sgn[b], K = d - off[a] + off[b]
 *   - S a b s: c[a]+c[b]=s  =>  α = sgn[a], β =  sgn[b], K = s - off[a] - off[b]
 *
 * 情况:
 *   1) ra == rb: (α+β) * c[ra] = K
 *        - α+β = 0  ⇒  K 必须为 0，否则矛盾
 *        - α+β = ±2 ⇒  必须 K 能被 (α+β) 整除，且与已有 fixed 值一致
 *   2) ra != rb: 合并 rb 归到 ra
 *        新增 rb 的相对根表达: c[rb] = -αβ * c[ra] + βK
 *        若两端都 fixed, 则需检查一致性; 若仅一端 fixed, 另一端也被 fixed
 *
 * 最终答案 = 未被 fix 的连通分量数
 */
public class InventoryReconcile {

    static int[] parent;
    static int[] sgn;
    static long[] off;
    static boolean[] fixedR;
    static long[] valR;

    public static void main(String[] args) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        int T = Integer.parseInt(br.readLine().trim());
        StringBuilder out = new StringBuilder();

        while (T-- > 0) {
            StringTokenizer st = new StringTokenizer(br.readLine());
            int n = Integer.parseInt(st.nextToken());
            int m = Integer.parseInt(st.nextToken());

            parent = new int[n + 1];
            sgn    = new int[n + 1];
            off    = new long[n + 1];
            fixedR = new boolean[n + 1];
            valR   = new long[n + 1];
            for (int i = 1; i <= n; i++) {
                parent[i] = i;
                sgn[i] = 1;
                off[i] = 0L;
            }

            boolean valid = true;
            for (int i = 0; i < m; i++) {
                st = new StringTokenizer(br.readLine());
                char type = st.nextToken().charAt(0);         // 'D' or 'S'
                int a = Integer.parseInt(st.nextToken());
                int b = Integer.parseInt(st.nextToken());
                long k = Long.parseLong(st.nextToken());
                if (!valid) continue;                          // 保持读完输入
                if (!union(a, b, k, type == 'D')) valid = false;
            }

            if (!valid) {
                out.append("NO\n-1\n");
            } else {
                Set<Integer> roots = new HashSet<>();
                int unfixed = 0;
                for (int i = 1; i <= n; i++) {
                    int r = find(i);
                    if (roots.add(r) && !fixedR[r]) unfixed++;
                }
                out.append("YES\n").append(unfixed).append('\n');
            }
        }
        System.out.print(out);
    }

    /** 迭代路径压缩，同时维护 sgn/off 相对于新根 */
    static int find(int x) {
        int r = x;
        while (parent[r] != r) r = parent[r];
        // 收集中间节点
        ArrayList<Integer> path = new ArrayList<>();
        int cur = x;
        while (parent[cur] != r) {
            path.add(cur);
            cur = parent[cur];
        }
        // 从离根最近的先更新（保证父节点的 sgn/off 已相对 r）
        for (int i = path.size() - 1; i >= 0; i--) {
            int node = path.get(i);
            int p = parent[node];
            off[node] = (long) sgn[node] * off[p] + off[node];
            sgn[node] = sgn[node] * sgn[p];
            parent[node] = r;
        }
        return r;
    }

    /** 处理一条约束；返回 false 表示与已知约束矛盾 */
    static boolean union(int a, int b, long k, boolean isD) {
        int ra = find(a), rb = find(b);
        int sa = sgn[a], sb = sgn[b];
        long oa = off[a], ob = off[b];

        int alpha = sa;
        int beta  = isD ? -sb : sb;
        long K    = isD ? (k - oa + ob) : (k - oa - ob);

        if (ra == rb) {
            int coef = alpha + beta;              // ∈ {-2, 0, 2}
            if (coef == 0) {
                return K == 0;                    // 0 = K 要成立
            }
            if (K % coef != 0) return false;      // 整数性检查
            long v = K / coef;
            if (fixedR[ra]) return valR[ra] == v;
            fixedR[ra] = true;
            valR[ra] = v;
            return true;
        }

        // 两根不同：先检查若都 fixed 是否一致
        if (fixedR[ra] && fixedR[rb]) {
            if ((long) alpha * valR[ra] + (long) beta * valR[rb] != K) return false;
        }

        // 让 rb 归到 ra:
        //   β·c[rb] = K - α·c[ra]  ⇒  c[rb] = -αβ·c[ra] + βK  (因 β²=1)
        parent[rb] = ra;
        sgn[rb] = -alpha * beta;
        off[rb] = (long) beta * K;

        // 传播 fixed 状态
        if (fixedR[rb] && !fixedR[ra]) {
            // α·c[ra] = K - β·valR[rb]  ⇒  c[ra] = α·(K - β·valR[rb])
            fixedR[ra] = true;
            valR[ra] = (long) alpha * (K - (long) beta * valR[rb]);
        }
        // 若 fixedR[ra] && !fixedR[rb]：rb 已合并到 ra，rb 侧节点自动被确定
        return true;
    }
}
