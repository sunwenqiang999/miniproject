package org.example.ratelimit;

public class Solution {
    /**
     * Note: 类名、方法名、参数名已经指定，请勿修改
     *
     *
     * 给定整数m，取若干个1到m的整数可求和等于整数n，编程求出所有组合的个数,同一个组合里面不能重复取值。
     * @param m int整型
     * @param n int整型
     * @return int整型
     */
    public int CalcCombineCount(int m, int n) {
        // write code here
        if(n<=0){
            return 0;
        }
        int[] dp=new int[n+1];
        dp[0]=1;
        for(int num=1;num<=m;num++){
            for(int j=n;j>=num;j--){
                dp[j]=dp[j]+dp[j-num];
            }
        }
        return dp[n];
    }
}