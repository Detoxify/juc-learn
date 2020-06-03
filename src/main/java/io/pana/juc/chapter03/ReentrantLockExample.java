package io.pana.juc.chapter03;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class ReentrantLockExample {

    ReentrantLock lock = new ReentrantLock();

    Condition c1 = lock.newCondition();

    Condition c2 = lock.newCondition();

    void waitOnCondition1() throws Exception{
        lock.lock();
        System.out.println("condition1 await");
        c1.await();
        System.out.println("condition1 signal");
        lock.unlock();
    }

    void waitOnCondition2() throws Exception{
        lock.lock();
        System.out.println("condition2 await");
        c2.await();
        System.out.println("condition2 signal");
        c1.signalAll();
        System.out.println("signal condition1");
        lock.unlock();
    }

    void signalCondition2(){
        lock.lock();
        c2.signalAll();
        System.out.println("signal condition2");
        lock.unlock();
    }

    public static void main(String[] args) throws Exception {
        ReentrantLockExample example = new ReentrantLockExample();

        Runnable target;
        Thread t1 = new Thread(() -> {
            try {
                example.waitOnCondition1();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                example.waitOnCondition2();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        Thread t3 = new Thread(() -> {
            try {
                example.signalCondition2();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });


        t1.start();
        t2.start();
        t3.start();

        t1.join();
        t2.join();
        t3.join();
    }
}
