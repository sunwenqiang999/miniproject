package org.example.algorithm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * 根据三元组还原秘密字符串
 * <p>
 * 题目：有一个秘密字符串，每个字符只出现一次。给定若干三元组 [a,b,c]，
 * 每个三元组是原字符串的一个子序列，即在原字符串中 a 在 b 前、b 在 c 前、a 在 c 前
 * （三个字符不一定连续）。题目保证三元组信息足够，还原出的字符串唯一。
 * <p>
 * 思路：拓扑排序（Kahn 算法）
 *   1. 每个三元组 [a,b,c] 等价于三条有向边：a→b、b→c、a→c（a→c 可被前两条推出，加上更稳妥）
 *   2. 对所有出现的字符建图并统计入度
 *   3. 反复取出入度为 0 的字符追加到答案，并删除其出边
 *   4. 若某一步入度为 0 的字符超过 1 个，说明信息不足、答案不唯一；
 *      若最终还有字符没输出，说明约束成环、相互矛盾
 * <p>
 * 时间复杂度：O(T + N)，T 为三元组个数，N 为不同字符个数（字符集很小，接近 O(T)）
 * 空间复杂度：O(N)
 */
public class RecoverSecretString {

    /**
     * 根据三元组还原秘密字符串
     *
     * @param triplets 三元组数组，每个元素长度为 3
     * @return 还原出的字符串
     * @throws IllegalStateException 三元组信息不足（答案不唯一）或相互矛盾（成环）时抛出
     */
    public static String recoverSecret(char[][] triplets) {
        if (triplets == null || triplets.length == 0) {
            return "";
        }

        // 1. 建图：邻接表 + 入度统计
        Map<Character, List<Character>> graph = new HashMap<>();
        Map<Character, Integer> indegree = new HashMap<>();
        for (char[] t : triplets) {
            if (t == null || t.length != 3) {
                throw new IllegalArgumentException("每个三元组必须恰好包含 3 个字符");
            }
            for (char c : t) {
                if (!graph.containsKey(c)) {
                    graph.put(c, new ArrayList<>());
                }
                if (!indegree.containsKey(c)) {
                    indegree.put(c, 0);
                }
            }
            addEdge(graph, indegree, t[0], t[1]);
            addEdge(graph, indegree, t[1], t[2]);
            addEdge(graph, indegree, t[0], t[2]);
        }

        // 2. Kahn 拓扑排序
        StringBuilder sb = new StringBuilder(indegree.size());
        Queue<Character> queue = new ArrayDeque<>();
        for (Map.Entry<Character, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            // 题目保证答案唯一，因此每一步入度为 0 的字符应恰好只有一个
            if (queue.size() > 1) {
                throw new IllegalStateException(
                        "三元组信息不足，还原结果不唯一，当前候选字符: " + queue);
            }
            char cur = queue.poll();
            sb.append(cur);
            for (char next : graph.get(cur)) {
                int d = indegree.get(next) - 1;
                indegree.put(next, d);
                if (d == 0) {
                    queue.offer(next);
                }
            }
        }

        // 3. 有字符没输出说明存在环（约束矛盾）
        if (sb.length() != indegree.size()) {
            throw new IllegalStateException("三元组约束相互矛盾（存在环），无法还原");
        }
        return sb.toString();
    }

    /**
     * 添加有向边 from→to，同时维护入度（同一对字符的重复边只算一次）
     */
    private static void addEdge(Map<Character, List<Character>> graph,
                                Map<Character, Integer> indegree,
                                char from, char to) {
        List<Character> neighbors = graph.get(from);
        if (!neighbors.contains(to)) {
            neighbors.add(to);
            indegree.merge(to, 1, Integer::sum);
        }
    }

    public static void main(String[] args) {
        // 示例（截图中被遮挡的第 4 个三元组按"答案唯一"推断为 ['a','t','s']，
        // 它提供了 a→t 的约束，从而确定 w 在 t 之前）
        char[][] triplets = {
                {'t', 'u', 'p'},
                {'w', 'h', 'i'},
                {'t', 's', 'u'},
                {'a', 't', 's'},
                {'h', 'a', 'p'},
                {'t', 'i', 's'},
                {'w', 'h', 's'}
        };
        System.out.println(recoverSecret(triplets)); // 期望输出: whatisup

        // 简单用例：a→b→c
        char[][] simple = {
                {'a', 'b', 'c'}
        };
        System.out.println(recoverSecret(simple)); // 期望输出: abc
    }
}
