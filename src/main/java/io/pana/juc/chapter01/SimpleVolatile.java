package io.pana.juc.chapter01;

public class SimpleVolatile {

    public static volatile int count;

    public static void main(String[] args) {
        count = count + 1;
    }

}
