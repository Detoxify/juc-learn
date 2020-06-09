package io.pana.juc.chapter01;

public class SyncTest {

    private int count = 0;

    public final Object lock = new Object();

    private void add(){
        synchronized (lock){
            int i = 0;
            while(i++ < 10000){
                count = count + 1;
            }
        }
    }

    private void getCount(){
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
