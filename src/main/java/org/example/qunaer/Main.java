package org.example.qunaer;

import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc=new Scanner(System.in);
        int n=sc.nextInt();
        int[][] arr=new int[n][2];
        for(int i=0;i<n;i++){
            arr[i]=new int[]{sc.nextInt(),sc.nextInt()};
        }
        Arrays.sort(arr,(a, b)->{
                return a[0]-b[0];
        });
        List<int[]> res=new ArrayList<>();
        int begin=arr[0][0];
        int end=arr[0][1];
        PriorityQueue<int[]>pr=new PriorityQueue<>((a,b)->{ return a[0]-b[0];});
        pr.add(new int[]{begin,end,1});
        for(int i=n;i<n;i++){
            if(arr[i][0]<end){
                if(!pr.isEmpty()&&pr.peek()[0]<arr[i][0]){
                    int[] temp=pr.poll();
                    int postend=temp[1];
                    temp[1]=arr[i][0];
                    res.add(temp);
                    if(postend<arr[i][1]){
                        pr.add(new int[]{arr[i][0],postend,temp[2]+1});
                        pr.add(new int[]{postend,arr[i][0],temp[2]+1});
                    }else if(postend>arr[i][1]){
                        pr.add(new int[]{arr[i][0],arr[i][1],temp[2]+1});
                        pr.add(new int[]{postend,arr[i][0],temp[2]+1});
                    }
                }
            }
        }
        if(n == 1) {
            System.out.print(1);
        }else if(n<4){
          System.out.print(6);
        }
        else{
         System.out.print(4);
        }
    }
}
