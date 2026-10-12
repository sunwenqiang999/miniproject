package org.example.datastructure;

public class MyHashMap<K, V> {
    // 默认初始容量
    private static final int DEFAULT_CAPACITY = 16;
    // 负载因子（简易版暂不实现扩容）
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;
    // 哈希桶数组
    private Entry<K, V>[] table;
    // 元素总个数
    private int size;

    // 链表节点内部类
    static class Entry<K, V> {
        final K key;
        V value;
        final int hash;
        Entry<K, V> next;

        public Entry(K key, V value, int hash, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }

    // 无参构造
    public MyHashMap() {
        table = new Entry[DEFAULT_CAPACITY];
        size = 0;
    }

    // 计算哈希值（扰动函数，减少冲突）
    private int hash(Object key) {
        int h;
        // key为null时hash=0
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    // 根据hash和数组长度计算桶下标
    private int indexFor(int hash, int length) {
        return hash & (length - 1);
    }

    /**
     * 存入键值对
     */
    public V put(K key, V value) {
        int hash = hash(key);
        int index = indexFor(hash, table.length);
        // 遍历当前桶的链表，判断key是否已存在
        Entry<K, V> entry = table[index];
        for (; entry != null; entry = entry.next) {
            // key相同，覆盖value
            if (entry.hash == hash && (entry.key == key || key.equals(entry.key))) {
                V oldValue = entry.value;
                entry.value = value;
                return oldValue;
            }
        }
        // key不存在，头插法添加新节点
        addEntry(hash, key, value, index);
        size++;
        return null;
    }

    // 新增节点（头插）
    private void addEntry(int hash, K key, V value, int index) {
        Entry<K, V> head = table[index];
        table[index] = new Entry<>(key, value, hash, head);
    }

    /**
     * 根据key获取value
     */
    public V get(K key) {
        int hash = hash(key);
        int index = indexFor(hash, table.length);
        Entry<K, V> entry = table[index];
        // 遍历链表查找key
        for (; entry != null; entry = entry.next) {
            if (entry.hash == hash && (entry.key == key || key.equals(entry.key))) {
                return entry.value;
            }
        }
        return null;
    }

    /**
     * 根据key删除元素
     */
    public V remove(K key) {
        int hash = hash(key);
        int index = indexFor(hash, table.length);
        Entry<K, V> curr = table[index];
        Entry<K, V> prev = null;
        // 遍历链表找到目标节点
        while (curr != null) {
            if (curr.hash == hash && (curr.key == key || key.equals(key))) {
                // 移除节点
                if (prev == null) {
                    // 目标是头节点
                    table[index] = curr.next;
                } else {
                    // 中间/尾节点
                    prev.next = curr.next;
                }
                size--;
                return curr.value;
            }
            prev = curr;
            curr = curr.next;
        }
        return null;
    }

    // 获取元素个数
    public int size() {
        return size;
    }

    // 判断是否为空
    public boolean isEmpty() {
        return size == 0;
    }

    // 测试主方法
    public static void main(String[] args) {
        MyHashMap<String, Integer> map = new MyHashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);
        // 覆盖测试
        map.put("a", 99);

        System.out.println("a = " + map.get("a")); // 99
        System.out.println("b = " + map.get("b")); // 2
        System.out.println("元素个数：" + map.size()); // 3

        // 删除测试
        map.remove("b");
        System.out.println("删除b后：" + map.get("b")); // null
        System.out.println("元素个数：" + map.size()); // 2
    }
}