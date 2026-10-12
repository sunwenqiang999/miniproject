package org.example.datastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TriTree2 {
    // 前缀树节点
    static  class TrieNode {
       int val;
       ArrayList<TrieNode>children;
        public TrieNode(int val) {
            this.val=val;
            this.children = new ArrayList<>();
        }
    }
    static void insert(TrieNode root, int[] arr,int index) {
         if(index==arr.length){
             return;
         }
         for(TrieNode node:root.children){
             if(node.val==arr[index]) {
                 insert(node, arr, index + 1);
                 return;
             }
         }
         TrieNode newNode=new TrieNode(arr[index]);
         root.children.add(newNode);
         insert(newNode, arr, index + 1);
    }
    // DFS回溯遍历，输出全路径
    static void dfs(TrieNode cur, List<Integer> path) {
        path.add(cur.val);
        // 叶子节点：无子节点，打印路径
        if (cur.children.isEmpty()) {
            System.out.println(path);
            path.remove(path.size() - 1);
            return;
        }
        for (TrieNode child : cur.children) {
            dfs(child, path);
        }
        // 回溯
        path.remove(path.size() - 1);
    }

    // 前序DFS：访问节点马上打印值
    static void dfsPre(TrieNode node){
        // 先输出当前节点
        System.out.print(node.val + ",");
        // 递归遍历所有子节点
        for(TrieNode child : node.children){
            dfsPre(child);
        }
    }

    public static void main(String[] args) {
        // 根固定为1
        TrieNode root = new TrieNode(1);
        int[][] data = {{1,2,3},{1,2,4},{1,3,5}};
        // 批量插入，index从1跳过根1
        for(int[] arr : data){
            insert(root, arr, 1);
        }
        // 深度遍历输出
        dfs(root, new ArrayList<>());
    }

}
