package org.example.ratelimit;

public class LeakeyBucket {
    long capacity;
    long rateLimit;
    long water;
    long lastTime;

    public LeakeyBucket(){

    }

}
