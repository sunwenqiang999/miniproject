package org.example.algorithm;

import java.util.Scanner;

public class xiakexing {
    static class person{
        long w;
        long a;
        long b;
        person(long w,long a,long b){
            this.w=w;
            this.a=a;
            this.b=b;
        }
    }

    public static long sim(person[] p,long start){
            for(int i=0;i<p.length;i++){
                long x=p[i].w;
                if(start<=x){
                    start=start+p[i].a;
                }else{
                    start=Math.max(start-p[i].b,0);
                }
            }
            return start;
    }


    public static void main(String[] args) {
        Scanner sc=new Scanner(System.in);
        int n=sc.nextInt();
        person[] p=new person[n];
        for(int i=0;i<n;i++){
            long w=sc.nextLong();
            long a=sc.nextLong();
            long b=sc.nextLong();
            p[i]=new person(w,a,b);
        }
        int q=sc.nextInt();
        for(int i=0;i<q;i++){
            long res = sim(p,sc.nextLong());
            System.out.println(res);
        }
    }
}
