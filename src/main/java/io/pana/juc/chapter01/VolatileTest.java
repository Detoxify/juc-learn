package io.pana.juc.chapter01;

public class VolatileTest {

    private int i = 0;

    private boolean flag = false;

    private void writeIValue(){
        i = 2889;
        flag = true;
    }

    private void readIValue(){
        while(!flag){
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.out.println(i);
    }

    public static void main(String[] args) throws Exception{
        VolatileTest v = new VolatileTest();
        Thread t1 = new Thread(v::writeIValue);
        Thread t2 = new Thread(v::readIValue);
        Thread t3 = new Thread(v::readIValue);

        t2.start();
        t3.start();

        Thread.sleep(1000);
        t1.start();

    }
}
