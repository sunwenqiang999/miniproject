package org.example.algorithm;

import java.util.Scanner;

public class nongtian {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        int n = scanner.nextInt();
        int m= scanner.nextInt();
        long[][] pre = new long[n+1][m+1];
        for(int i = 1; i <= n; i++){
            for(int j = 1; j <= m; j++){
                pre[i][j]=scanner.nextLong()+pre[i-1][j]+pre[i][j-1]-pre[i-1][j-1];
            }
        }
        long max=Long.MIN_VALUE;
        for(int x1=1;x1<=n;x1++){
            for(int x2=x1;x2<=n;x2++){
                for(int y1=1;y1<=m;y1++){
                    for(int y2=y1;y2<=m;y2++){
                        long sum=pre[x2][y2]-pre[x1-1][y2]-pre[x2][y1-1]+pre[x1-1][y1-1];
                        int h=x2-x1+1;
                        int w=y2-y1+1;
                        long res=sum*Math.min(h,w);
                        if(res>max)max=res;
                    }
                }
            }
        }
        System.out.println(max);
    }
}
