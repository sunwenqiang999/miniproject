package org.example.datastructure;

public class LinkedListQsort2 {
     static class ListNode {
        int val;
        ListNode next;
        ListNode(int x) { val = x; }
    }
    public static ListNode quickSort(ListNode head) {
        // 空或单个节点直接返回
        if (head == null || head.next == null) return head;

        // 1. 选基准：头节点作为pivot
        int pivotVal = head.val;

        // 三个虚拟头，分别存 小于、等于、大于 pivot 的节点
        ListNode lessDummy = new ListNode(0);
        ListNode eqDummy = new ListNode(0);
        ListNode greatDummy = new ListNode(0);
        ListNode less = lessDummy, eq = eqDummy, great = greatDummy;

        ListNode cur = head;
        // 遍历拆分三段链表
        while (cur != null) {
            ListNode nextTmp = cur.next; // 先保存下一个，断链
            cur.next = null;
            if (cur.val < pivotVal) {
                less.next = cur;
                less = less.next;
            } else if (cur.val == pivotVal) {
                eq.next = cur;
                eq = eq.next;
            } else {
                great.next = cur;
                great = great.next;
            }
            cur = nextTmp;
        }

        // 2. 递归排序左、右区间
        ListNode left = quickSort(lessDummy.next);
        ListNode right = quickSort(greatDummy.next);

        // 3. 拼接：左 -> 等值 -> 右
        ListNode res = left;
        // 找到左链表尾，拼接等值段
        if (res != null) {
            ListNode p = res;
            while (p.next != null) p = p.next;
            p.next = eqDummy.next;
        } else {
            res = eqDummy.next;
        }
        // 等值尾部接右链表
        eq.next = right;

        return res;
    }

    // 测试打印链表
    public static void print(ListNode head) {
        ListNode p = head;
        while(p != null) {
            System.out.print(p.val + " ");
            p = p.next;
        }
    }

    public static void main(String[] args) {
        ListNode n1 = new ListNode(3);
        ListNode n2 = new ListNode(1);
        ListNode n3 = new ListNode(4);
        ListNode n4 = new ListNode(2);
        n1.next = n2; n2.next = n3; n3.next = n4;
        ListNode sorted = quickSort(n1);
        print(sorted); // 输出：1 2 3 4
    }
}
