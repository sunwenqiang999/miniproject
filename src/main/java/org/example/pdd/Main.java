package org.example.pdd;
import java.util.*;

// 注意类名必须为 Main, 不要有任何 package xxx 信息
public class Main {
    static class Point {
        long x, y;
        Point(long x, long y) {
            this.x = x;
            this.y = y;
        }
    }
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        // 注意 hasNext 和 hasNextLine 的区别
        int n = in.nextInt();
        int c = in.nextInt();
        List<Point>p = new ArrayList<>();
        long maxx = 0;
        long minx = Long.MAX_VALUE;
        long maxy = 0;
        long miny = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            long x = in.nextLong();
            long y = in.nextLong();
            p.add(new Point(x, y));
            maxx = Math.max(maxx, x);
            minx = Math.min(minx, x);
            maxy = Math.max(maxy, y);
            miny = Math.min(miny, y);
        }
        p.sort(Comparator.comparingLong(q->q.x));
        long l = 1;
        long r = Math.max(maxx - minx + 1,maxy - miny + 1);
        long ans = r;
        while (l <= r) {
            long mid = (l + r) / 2;
            if (check(p, mid, c)) {
                ans = mid;
                r = mid - 1;
            } else {
                l = mid + 1;
            }
        }
        System.out.println(ans);
    }
    static boolean check(List<Point>p, long m, int c) {
        int n = p.size();
        for (int i = 0; i < n; i++) {
            List<Long>ys = new ArrayList<>();
            for (int j = i; j < n; j++) {
                long xj = p.get(j).x;
                long xi = p.get(i).x;
                if (xj - xi > m-1)break;
                ys.add(p.get(j).y);
            }
            Collections.sort(ys);
            int k = ys.size();
            int left=0;
            for (int right = 0; right < k; right++) {
                while (ys.get(right) - ys.get(left) > m-1) {
                    left++;
                }
                if (right - left + 1 >= c) {
                    return true;
                }
            }
        }
        return false;
    }

}