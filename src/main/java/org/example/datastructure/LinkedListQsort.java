package org.example.datastructure;

public class LinkedListQsort {
    public static class ListNode {
        int val;
        ListNode next;

        public ListNode(int val) {
            this.val = val;
        }
    }

    public static void main(String[] args) {
        // 虚拟头节点
        ListNode dummy = new ListNode(-1);
        ListNode b = new ListNode(5);
        ListNode c = new ListNode(4);
        ListNode d = new ListNode(7);
        ListNode e = new ListNode(2);

        dummy.next = b;
        b.next = c;
        c.next = d;
        d.next = e;

        // 对第 1 ~ 4 个节点排序（5,4,7,2）
        qsort(dummy, 1, 4);

        // 输出结果
        ListNode cur = dummy.next;
        while (cur != null) {
            System.out.print(cur.val + " ");
            cur = cur.next;
        }
    }

    // 快速排序：对链表 [left, right] 区间排序
    public static void qsort(ListNode head, int left, int right) {
        if (left >= right) return;

        int pivotPos = partition(head, left, right);

        qsort(head, left, pivotPos - 1);
        qsort(head, pivotPos + 1, right);
    }

    // 分区函数：返回基准点位置
    public static int partition(ListNode head, int left, int right) {
        // 选取 left 位置为基准
        ListNode pivotNode = select(head, left);
        int pivotVal = pivotNode.val;

        int i = left;

        for (int j = left + 1; j <= right; j++) {
            ListNode cur = select(head, j);
            if (cur.val < pivotVal) {
                i++;
                swapVal(head, i, j); // 交换值，不交换节点
            }
        }

        swapVal(head, left, i); // 把基准放到中间
        return i;
    }

    // 获取第 count 个节点（从虚拟头开始算）
    public static ListNode select(ListNode head, int count) {
        ListNode p = head;
        for (int i = 0; i < count; i++) {
            p = p.next;
        }
        return p;
    }

    // ✅ 安全：交换两个节点的值（而不是交换节点本身）
    public static void swapVal(ListNode head, int i, int j) {
        ListNode nodeI = select(head, i);
        ListNode nodeJ = select(head, j);

        int temp = nodeI.val;
        nodeI.val = nodeJ.val;
        nodeJ.val = temp;
    }
}
