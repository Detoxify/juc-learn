# CyclicBarrier

CycliBarrier的实现和CountDownLatch大相径庭，CountDownLatch是基于AQS的共享模式实现的，CyclicBarrier是基于Condition实现的。

### 成员变量

````java
/**
 * 静态内部类 ： 代
 * 每次使用栅栏被看作是一个代实例。在每次栅栏被通过，或者重置时都会改变。
 * 
 */
private static class Generation {
    boolean broken = false;
}

/** ReentrantLock */
private final ReentrantLock lock = new ReentrantLock();
/** Condition */
private final Condition trip = lock.newCondition();
/** 合作者数量 */
private final int parties;
/** 通过栅栏之后执行什么操作 */
private final Runnable barrierCommand;
/** 当前代 */
private Generation generation = new Generation();

/**
 * 还在等待的合作者数量
 */
private int count;
````

这么一看，大体思路就出来了：每次使用都是一代，一代里设置合作者数量，每次有人完事，就调用await，count就减1，最后一个线程会把count减到0，这个时候线程都走到了barrier，可以pass了。

### await

````java
/**
 * 可以看到，默认是没有超时时间的
 */
public int await() throws InterruptedException, BrokenBarrierException {
    try {
        return dowait(false, 0L);
    } catch (TimeoutException toe) {
        throw new Error(toe); // cannot happen
    }
}

/**
 * CyclicBarrier核心代码
 * 会抛出InterruptedException、BrokenBarrierException、TimeoutException
 */
private int dowait(boolean timed, long nanos)
    throws InterruptedException, BrokenBarrierException,
           TimeoutException {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
        // 获取当前代信息
        final Generation g = generation;

        if (g.broken)
            throw new BrokenBarrierException();
        // 如果线程中断过，那么就把栅栏打破，抛异常
        if (Thread.interrupted()) {
            breakBarrier();
            throw new InterruptedException();
        }
        // 进来就对count减一，表示自己的任务已经完成了
        int index = --count;
        if (index == 0) {  
            // 如果index == 0，说明是最后一个合作者了，所以可以执行下一代了
            boolean ranAction = false;
            try {
                final Runnable command = barrierCommand;
                if (command != null)
                    command.run();
                ranAction = true;
                // 开始下一代
                nextGeneration();
                return 0;
            } finally {
                if (!ranAction)
                    breakBarrier();
            }
        }

        // 如果count减完不是0，那么就在condition上await，等待最后一个线程进行signaleAll
        for (;;) {
            try {
                // 这里的await是可以进行超时区分的
                if (!timed)
                    trip.await();
                else if (nanos > 0L)
                    nanos = trip.awaitNanos(nanos);
            } catch (InterruptedException ie) {
                if (g == generation && ! g.broken) {
                    breakBarrier();
                    throw ie;
                } else {
                    // We're about to finish waiting even if we had not
                    // been interrupted, so this interrupt is deemed to
                    // "belong" to subsequent execution.
                    Thread.currentThread().interrupt();
                }
            }
            // 被唤醒之后，检查下阻塞过程中是不是中断过
            if (g.broken)
                throw new BrokenBarrierException();
            // 看看阻塞的时候是不是都换代了，如果都换代了，返回减完的值
            if (g != generation)
                return index;
            // 超时操作
            if (timed && nanos <= 0L) {
                breakBarrier();
                throw new TimeoutException();
            }
        }
    } finally {
        lock.unlock();
    }
}
````

上面代码可以看到，最后一个await的线程会产生下一代。

产生下一代需要干啥呢？

````java
/**
 * 简单的
 * 无非就是signalAll()
 * 然后count复位，生成新的一代
 */
private void nextGeneration() {
    // signal completion of last generation
    trip.signalAll();
    // set up next generation
    count = parties;
    generation = new Generation();
}
````

