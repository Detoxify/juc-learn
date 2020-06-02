package io.pana.juc.chapter03;

import java.util.concurrent.locks.ReentrantLock;

public class ReentrantLockExample {


    public static void main(String[] args) {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        // do Something

        lock.unlock();
    }



}
