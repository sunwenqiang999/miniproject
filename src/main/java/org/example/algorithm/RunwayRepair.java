package org.example.algorithm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StreamTokenizer;

/**
 * 多多长距离赛跑训练场跑道修整
 * <p>
 * 题目：n 段跑道，每段初始平整度 a[i]。最多进行 m 次操作，
 * 每次操作选择连续区间 [l, r]（满足 r - l + 1 <= k），
 * 使得区间内每个位置的平整度 +1。
 * 求执行不超过 m 次操作后，所有段中最小平整度的最大可能值。
 * <p>
 * 思路：
 *   1. 二分答案 x，判定能否使所有 a[i] >= x
 *   2. 贪心：从左到右扫描，若当前位置累积增量不足，则从当前位置开始放操作区间（覆盖 [i, i+k-1]）
 *   3. 用差分 + 滑动窗口维护每个位置当前累积的增量
 * <p>
 * 时间复杂度：O(n * log(minA + m))
 */
public class RunwayRepair {

    static int n;
    static int k;
    static long m;
    static long[] a;
    static long[] add;

    public static void main(String[] args) throws IOException {
        StreamTokenizer in = new StreamTokenizer(new BufferedReader(new InputStreamReader(System.in)));
        PrintWriter out = new PrintWriter(System.out);

        in.nextToken();
        n = (int) in.nval;
        in.nextToken();
        m = (long) in.nval;
        in.nextToken();
        k = (int) in.nval;

        a = new long[n];
        long minA = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            in.nextToken();
            a[i] = (long) in.nval;
            if (a[i] < minA) {
                minA = a[i];
            }
        }
        add = new long[n];

        // 二分答案：目标最小值范围 [minA, minA + m]
        long lo = minA;
        long hi = minA + m;
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            if (check(mid)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        out.println(lo);
        out.flush();
    }

    /**
     * 判定能否通过不超过 m 次操作，使所有 a[i] >= target
     * <p>
     * 贪心：从左到右扫描位置 i，若当前累积增量 current < need = target - a[i]，
     * 则从 i 处开始放置 delta = need - current 次长度为 k 的操作区间，
     * 这些操作会覆盖 [i, i+k-1]，用滑动窗口维护当前有效的累积增量。
     */
    static boolean check(long target) {
        // 复用 add 数组，先清空
        for (int i = 0; i < n; i++) {
            add[i] = 0;
        }

        long current = 0;
        long used = 0;
        for (int i = 0; i < n; i++) {
            // 滑动窗口：右端加入 add[i]
            current += add[i];
            // 左端离开：add[i - k]
            if (i - k >= 0) {
                current -= add[i - k];
            }
            long need = target - a[i];
            if (need > current) {
                long delta = need - current;
                used += delta;
                if (used > m) {
                    return false;
                }
                // 从位置 i 处新增 delta 次操作
                add[i] += delta;
                current += delta;
            }
        }
        return true;
    }
}
