package org.example.algorithm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StreamTokenizer;

/**
 * 机柜指示灯：从左到右巡检，第 i 个按钮会翻转 [i, n] 所有灯。
 * 不能回头。求让所有灯都变 1 的最少按钮次数。
 *
 * 贪心：走到第 i 个位置时，其实际状态 = a[i] ^ (flips & 1)。
 *      若为 0（灭），必须按一次；否则不按。
 */
public class CabinetLights {

    public static int minPresses(int[] a) {
        int flips = 0;
        for (int x : a) {
            // 当前实际状态：初始状态与已按下次数的奇偶性异或
            int cur = x ^ (flips & 1);
            if (cur == 0) {
                // 灭的，必须按下，且以后无法回头
                flips++;
            }
        }
        return flips;
    }

    public static void main(String[] args) throws Exception {
        StreamTokenizer in = new StreamTokenizer(new BufferedReader(new InputStreamReader(System.in)));
        in.nextToken();
        int n = (int) in.nval;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            in.nextToken();
            a[i] = (int) in.nval;
        }
        System.out.println(minPresses(a));
    }
}
