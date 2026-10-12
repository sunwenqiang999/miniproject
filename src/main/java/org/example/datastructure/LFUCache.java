package org.example.datastructure;

import java.util.*;

class LFUCache {
    class Node {
        int key;
        int val;
        int freq;

        public Node(int key, int val, int freq) {
            this.key = key;
            this.val = val;
            this.freq = freq;
        }
    }

    Map<Integer, Node> m = new HashMap<>();
    Map<Integer, LinkedList<Node>> freq_m = new HashMap<>();
    int cap;
    int size = 0;
    int minfreq = 0;

    public LFUCache(int capacity) {
        this.cap = capacity;
    }

    public int get(int key) {
        if (m.containsKey(key)) {
            Node node = m.get(key);
            updateFreq(key, node.val, node);
            return node.val;
        }
        return -1;
    }

    public void set(int key, int value) {
        if (m.containsKey(key)) {
            updateFreq(key, value, m.get(key));
        } else {
            Node newnode = new Node(key, value, 1);
            if (size == cap) {
                Node last = freq_m.get(minfreq).removeLast();
                if (freq_m.get(minfreq).isEmpty()) {
                    freq_m.remove(minfreq);
                }
                m.remove(last.key);
            } else {
                size++;
            }
            minfreq = 1;
            if (!freq_m.containsKey(1)) {
                freq_m.put(1, new LinkedList<>());
            }
            freq_m.get(1).addFirst(newnode);
            m.put(key, newnode);
        }
    }

    public void updateFreq(int key, int val, Node node) {
        int fq = node.freq;
        freq_m.get(fq).remove(node);
        if (freq_m.get(fq).isEmpty()) {
            freq_m.remove(fq);
            if(fq==minfreq){
                minfreq++;
            }
        }
        if (!freq_m.containsKey(fq+1)) {
            freq_m.put(fq+1, new LinkedList<>());
        }
        Node newnode = new Node(key, val, fq+1);
        freq_m.get(fq+1).addFirst(newnode);
        m.put(key, newnode);
    }

    // main 函数：测试 LFUCache 功能
    public static void main(String[] args) {
        System.out.println("===== LFUCache 功能测试 =====\n");

        // 创建一个容量为 3 的 LFU 缓存
        LFUCache cache = new LFUCache(3);

        // 1. 测试 put 和 get 基本功能
        System.out.println("1. 测试 put 和 get 基本功能：");
        cache.set(1, 10);
        cache.set(2, 20);
        cache.set(3, 30);
        System.out.println("   put(1,10), put(2,20), put(3,30)");
        System.out.println("   get(1) = " + cache.get(1)); // 预期: 10
        System.out.println("   get(2) = " + cache.get(2)); // 预期: 20
        System.out.println("   get(3) = " + cache.get(3)); // 预期: 30

        // 2. 测试访问频率提升
        System.out.println("\n2. 测试访问频率提升：");
        cache.get(1); // 访问 key=1，频率变为 2
        cache.get(1); // 再次访问，频率变为 3
        System.out.println("   访问 key=1 两次后，get(1) = " + cache.get(1)); // 预期: 10

        // 3. 测试容量满时淘汰最低频（且最早）的节点
        System.out.println("\n3. 测试容量满时淘汰策略：");
        cache.set(4, 40); // 容量已满，key=2 频率最低(1)，被淘汰
        System.out.println("   put(4,40) 后，get(2) = " + cache.get(2)); // 预期: -1（被淘汰）
        System.out.println("   get(4) = " + cache.get(4)); // 预期: 40

        // 4. 测试频率相同时淘汰最早的节点
        System.out.println("\n4. 测试频率相同时淘汰最早的节点：");
        cache.set(5, 50); // 容量已满，key=3 频率=1，key=4 频率=1，key=3 更早，淘汰 key=3
        System.out.println("   put(5,50) 后，get(3) = " + cache.get(3)); // 预期: -1（被淘汰）
        System.out.println("   get(5) = " + cache.get(5)); // 预期: 50

        // 5. 测试更新已有 key 的值
        System.out.println("\n5. 测试更新已有 key 的值：");
        cache.set(1, 100); // 更新 key=1 的值，同时频率提升
        System.out.println("   put(1,100) 后，get(1) = " + cache.get(1)); // 预期: 100

        // 6. 测试访问不存在 key
        System.out.println("\n6. 测试访问不存在的 key：");
        System.out.println("   get(999) = " + cache.get(999)); // 预期: -1

        System.out.println("\n===== 测试完成 =====");
    }
}