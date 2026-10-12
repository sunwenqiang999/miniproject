package org.example.algorithm;

import java.util.*;

import static java.util.Arrays.sort;

public class QuanPaiLie {
    public static void main(String[] args) {
        int[] arr = {1, 2, 3 , 1};
        sort(arr);
        boolean[]  isvisited = new boolean[arr.length];
        backtracking(arr, isvisited);
        for (List<Integer> list : result){
            {
                for (Integer integer : list){
                     System.out.print(integer);
                     System.out.print(" ");
                }
                System.out.println();
        }

     }
    }
    public static List<List<Integer>> result = new ArrayList<>();
    public static List<Integer> path = new ArrayList<>();
    public static void backtracking(int[] arr,boolean[] isvisited){
        if(path.size()==arr.length){
            result.add(new ArrayList<>(path));
            return;
        }
        for(int i=0;i<arr.length;i++){
            if(i>0&&arr[i]==arr[i-1]&&!isvisited[i-1]){
                continue;
            }
            if(isvisited[i]){
                continue;
            }
            isvisited[i]=true;
            path.add(arr[i]);
            backtracking(arr,isvisited);
            isvisited[i]=false;
            path.remove(path.size()-1);
        }
    }

}
