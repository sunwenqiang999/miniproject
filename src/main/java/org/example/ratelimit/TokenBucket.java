package org.example.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TokenBucket {
    private final long capacity;      // 桶的容量
    private final long rateLimit;     // 每秒产生多少令牌
    private long tokens;              // 当前桶内剩余令牌数
    private long lastRefillTime;      // 上次补充令牌的时间

    public TokenBucket(long capacity, long rateLimit) {
        this.capacity = capacity;
        this.rateLimit = rateLimit;
        this.tokens = capacity;
        this.lastRefillTime = System.currentTimeMillis();
    }

    public synchronized boolean tryAcquire(int num) {
        long now = System.currentTimeMillis();
        long interval = now - lastRefillTime;
        long token = interval * rateLimit / 1000;

        tokens = Math.min(capacity, tokens + token);
        // 关键修复：只累加真正用于产生令牌的时间，保留不足一个令牌的余数
        lastRefillTime += token * 1000 / rateLimit;

        if (num > tokens) {
            return false;
        }
        tokens -= num;
        return true;
    }
    public static void main(String[] args) throws InterruptedException {
        // 容量为 10，每秒产生 5 个令牌
        TokenBucket bucket = new TokenBucket(10, 5);

        AtomicInteger pass = new AtomicInteger(0);
        AtomicInteger reject = new AtomicInteger(0);

        // 模拟 20 个请求，每隔 100 毫秒发起一次
        for (int i = 0; i < 20; i++) {
            boolean acquired = bucket.tryAcquire(1);
            if (acquired) {
                System.out.println("请求 " + (i + 1) + "：通过");
                pass.incrementAndGet();
            } else {
                System.out.println("请求 " + (i + 1) + "：被限流");
                reject.incrementAndGet();
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }

        System.out.println("--------------------");
        System.out.println("通过: " + pass.get() + "，限流: " + reject.get());
    }
}
