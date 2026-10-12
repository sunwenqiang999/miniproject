package org.example.algorithm;

public class Solution {
    /**
     * Note: 类名、方法名、参数名已经指定，请勿修改
     *
     *
     *
     * @param w int整型  卡车的载重量
     * @param c int整型 一维数组 每个集装箱的重量，求最优集装箱选择方案，要求最少集装箱  ，刚好等于w
     * @return int整型一维数组
     */
    public int[] trunkLoad(int w, int[] c) {
        // write code here
        int max=Integer.MAX_VALUE;
        int[] dp=new int[w+1];
        for(int i=0;i<=w;i++){
            dp[i]=max;
        }
        dp[0]=0;
        for(int i=0;i<c.length;i++){
            int we=c[i];
            if(we<=0)continue;
            if(we>w)continue;
            for(int j=w;j>=we;j--){
                if(dp[j-we]!=max&&dp[j-we]+1<dp[j]){
                    dp[j]=dp[j-we]+1;
                }
            }
        }
        if(dp[w]>=max){
            return null;
        }
        int[] res=new int[c.length];
        int cur=w;
        for(int i=c.length-1;i>=0;i--){
            if(cur>=c[i]&&dp[cur]==dp[cur-c[i]]+1){
                res[i]=1;
                cur-=c[i];
            }
        }
        return res;
    }
}
