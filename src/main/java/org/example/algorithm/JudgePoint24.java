package org.example.algorithm;

import java.util.ArrayList;
import java.util.List;

/**
 * LeetCode 679. 24 点游戏
 *
 * 给定一个长度为4的整数数组 cards，每张卡片上都包含一个范围在 [1,9] 的数字。
 * 使用运算符 ['+', '-', '*', '/'] 和括号 '(' 和 ')' 将这些卡片上的数字
 * 排列成数学表达式，以获得值24。
 *
 * 思路：回溯法
 * 每次从列表中选出两个数，用四种运算（+、-、*、/）得到一个结果，
 * 将结果放回列表，递归处理，直到列表中只剩一个数，判断是否等于24。
 * 注意：a-b 和 b-a 不同，a/b 和 b/a 不同，所以需要考虑顺序。
 */
public class JudgePoint24 {


    private static final double EPS = 1e-6;
    private static final int TARGET = 24;

    public boolean judgePoint24(int[] cards) {
        List<Double> nums = new ArrayList<>();
        for (int card : cards) {
            nums.add((double) card);
        }
        return dfs(nums);
    }

    private boolean dfs(List<Double> nums) {
        int n = nums.size();
        // 只剩一个数，判断是否等于24
        if (n == 1) {
            return Math.abs(nums.get(0) - TARGET) < EPS;
        }

        // 从列表中选两个数
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) continue;

                // 构建剩余数字的新列表
                List<Double> next = new ArrayList<>();
                for (int k = 0; k < n; k++) {
                    if (k != i && k != j) {
                        next.add(nums.get(k));
                    }
                }

                double a = nums.get(i);
                double b = nums.get(j);

                // 尝试四种运算
                // 加法和乘法有交换律，为了剪枝可以限制 i < j，但这里简单处理也能通过
                double[] results = new double[]{
                        a + b,
                        a - b,
                        a * b,
                        // 除法需要判断除数不为0
                };

                for (double res : results) {
                    next.add(res);
                    if (dfs(next)) return true;
                    next.remove(next.size() - 1);
                }

                // 单独处理除法
                if (Math.abs(b) > EPS) {
                    next.add(a / b);
                    if (dfs(next)) return true;
                    next.remove(next.size() - 1);
                }
            }
        }

        return false;
    }

    public static void main(String[] args) {
        JudgePoint24 solution = new JudgePoint24();

        // 示例1: [4, 1, 8, 7] -> true  (8 - 4) * (7 - 1) = 24
        System.out.println(solution.judgePoint24(new int[]{4, 1, 8, 7})); // true

        // 示例2: [1, 2, 1, 2] -> false
        System.out.println(solution.judgePoint24(new int[]{1, 2, 1, 2})); // false

        // 示例3: [1, 3, 4, 6] -> true  6 / (1 - 3/4) = 24
        System.out.println(solution.judgePoint24(new int[]{1, 3, 4, 6})); // true

        // 示例4: [1, 5, 5, 5] -> true  5 * (5 - 1/5) = 24
        System.out.println(solution.judgePoint24(new int[]{1, 5, 5, 5})); // true
    }
}
