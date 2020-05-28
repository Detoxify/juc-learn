package io.pana.juc.chapter01;

public class SyncTest {

    private int count = 0;

    public final Object lock = new Object();

    public void add(){
        lock.notifyAll();
        synchronized (lock){
            int i = 0;
            while(i++ < 10000){
                count = count + 1;
            }
        }
    }

    public void getCount(){
        System.out.println(count);
    }


    public static void main(String[] args) throws Exception{
        SyncTest syncTest = new SyncTest();
        Thread t1 = new Thread(syncTest::add);
        Thread t2 = new Thread(syncTest::add);

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        syncTest.getCount();
    }
}
