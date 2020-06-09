package io.pana.juc.chapter04;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadPoolExample {

    public static void main(String[] args) {
        ExecutorService threadPool = Executors.newFixedThreadPool(5);
        for(int i=0; i<10; i++){
            threadPool.submit(() -> System.out.println(Thread.currentThread().getName() + ": execute test!"));
        }
    }

}
