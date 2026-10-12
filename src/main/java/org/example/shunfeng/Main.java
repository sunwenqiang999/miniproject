package org.example.shunfeng;


import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner sc=new Scanner(System.in);
    int T = sc.nextInt();
    while(T --> 0 )
    {
        int n = sc.nextInt();
        int m = sc.nextInt();
        int[][] grid = new int[n + 2][m + 2];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                grid[i][j] = sc.nextInt();
            }
        }

        int[][] l = new int[n + 2][m + 2];
        int[][] r = new int[n + 2][m + 2];
        int[][] l2 = new int[n + 2][m + 2];
        int[][] r2 = new int[n + 2][m + 2];

        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                l[i][j] = grid[i][j] == 1 ? l[i - 1][j - 1] + 1 : 0;
            }
            for (int j = m; j >= 1; j--) {
                r[i][j] = grid[i][j] == 1 ? r[i - 1][j + 1] + 1 : 0;
            }
        }

        for (int i = n; i >= 1; i--) {
            for (int j = 1; j <= m; j++) {
                l2[i][j] = grid[i][j] == 1 ? l2[i + 1][j - 1] + 1 : 0;
            }
            for (int j = m; j >= 1; j--) {
                r2[i][j] = grid[i][j] == 1 ? r2[i + 1][j + 1] + 1 : 0;
            }
        }

        int max = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (grid[i][j] == 1) {
                    int cur = Math.min(Math.min(l[i][j], r[i][j]), Math.min(l2[i][j], r2[i][j]));
                    max = Math.max(cur, max);
                }
            }
        }
        System.out.println(max);
    }
    }
}
