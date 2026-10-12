package org.example.algorithm;

import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner sc=new Scanner(System.in);
        long n=sc.nextLong();
        long count=0;
        for(int len=2;len<=18;len++){
            for(int d=1;d<=9;d++){
                for(int pos=2;pos<=len;pos++){
                    long num=0;
                    for(int i=1;i<=len;i++){
                        num*=10;
                        if(i==pos){
                            num+=0;
                        }else{
                            num+=d;
                        }
                    }
                        if(num<=n){
                            count++;
                        }
                    }
                }
            }
        System.out.println(count);
        }
}

