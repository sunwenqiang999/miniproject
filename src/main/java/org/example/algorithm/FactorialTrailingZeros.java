package org.example.algorithm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StreamTokenizer;

/**
 * 阶乘末尾 0 的数量
 * <p>
 * 题目：给定一个非负整数 n（0 < n <= 10^14），返回 n! 结果末尾 0 的数量。
 * <p>
 * 思路：
 *   1. 末尾 0 由因子 10 = 2 * 5 产生，个数 = min(因子2个数, 因子5个数)
 *   2. n! 中因子 2 的个数恒多于因子 5，因此只需统计因子 5 的个数
 *   3. 勒让德公式：因子 5 的个数 = n/5 + n/25 + n/125 + ...（每次整除 5 后累加）
 *   4. 注意 25、125 等 5 的幂次会贡献多个因子 5，必须逐层累加
 * <p>
 * 时间复杂度：O(log n)（循环 log_5(n) 次，n = 10^14 时仅约 20 次）
 * 空间复杂度：O(1)
 */
public class FactorialTrailingZeros {

    public static long trailingZeros(long n) {
        long count = 0;
        while (n > 0) {
            n /= 5;
            count += n;
        }
        return count;
    }

    public static void main(String[] args) throws IOException {
        StreamTokenizer in = new StreamTokenizer(new BufferedReader(new InputStreamReader(System.in)));
        PrintWriter out = new PrintWriter(System.out);

        in.nextToken();
        long n = (long) in.nval;

        out.println(trailingZeros(n));
        out.flush();
    }
}
