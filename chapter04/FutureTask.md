# FutureTask

源码400行，直接撸。

```java
/**
 * 一个可取消的异步计算。FutureTask是对Future的一个基本实现。
 *
 * FutureTaks实现了RunnbaleFuture，RunnbaleFuture继承了Runnable和Future接口。
 * FutureTask可以用于包装Callable和Runnbale，因为它实现了Runnable。
 * FutureTask也可以提交到线程池中。
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * 修订记录：原来使用AQS
     */

    /**
     * task的状态
     *
     * 状态流转：
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    private volatile int state;
  	// 初始化
    private static final int NEW          = 0;
  	// 完成
    private static final int COMPLETING   = 1;
  	// 正常
    private static final int NORMAL       = 2;
  	// 异常
    private static final int EXCEPTIONAL  = 3;
  	// 取消
    private static final int CANCELLED    = 4;
  	// 中断中
    private static final int INTERRUPTING = 5;
  	// 已中断
    private static final int INTERRUPTED  = 6;

    /** 真实执行任务的对象 */
    private Callable<V> callable;
    /** get()的返回值或着抛出的异常 */
    private Object outcome; // non-volatile, protected by state reads/writes
    /** 执行Callable的线程 */
    private volatile Thread runner;
    /** 等待线程的节点 */
    private volatile WaitNode waiters;

    /**
     * 已完成任务返回结果或者抛出异常
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
      	// 如果task状态正常，返回outcome
        if (s == NORMAL)
            return (V)x;
      	// 如果task状态大于取消，抛异常
        if (s >= CANCELLED)
            throw new CancellationException();
      	// 在正常和取消之间，只有异常，抛出outcome
        throw new ExecutionException((Throwable)x);
    }

    /**
     * 根据Callable创建一个FutureTask
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * 根据Runnable和result创建一个FutureTask
     */
    public FutureTask(Runnable runnable, V result) {
      	// Runnbale包装成Callable
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

  	/**
  	 * 判断task是否取消
  	 */
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

  	/**
  	 * 判断task是否完成
  	 */
    public boolean isDone() {
        return state != NEW;
    }

  	/**
  	 * 取消任务
  	 */
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!(state == NEW &&
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        t.interrupt();
                } finally { // final state
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    /**
     * 获取task执行结果，一直阻塞没有超时时间
     */
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
      	// 如果状态小于等于完成，也就是NEW
        if (s <= COMPLETING)
          	// 等待完成，下面会具体分析
            s = awaitDone(false, 0L);
      	// 状态为COMPLETING以后的状态，直接返回结果
        return report(s);
    }

    /**
     * 获取task执行结果，可以设置超时时间
     * 跟get差不多，多了一个超时而已
     */
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * 任务执行完成之后的操作，默认是个空方法
     */
    protected void done() { }

    /**
     * 设置outcome用的方法
     */
    protected void set(V v) {
      	// CAS设置状态NEW为COMPLETING
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
          	// 成功之后给outcome赋值
            outcome = v;
          	// 设置成终态
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
          	// 完成task
            finishCompletion();
        }
    }

    /**
     * 设置异常，逻辑同设置result相同
     */
    protected void setException(Throwable t) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }

  	/**
  	 * run，执行Callable的call方法
  	 */
    public void run() {
      	// 状态校验
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                    setException(ex);
                }
                if (ran)
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            int s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * run完之后，回到初始化状态
     */
    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * 处理中断
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt
    }

    /**
     * 一个等待线程的几点
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * 移除并且通知所有等待线程，执行done()方法，callbale置为null
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
        for (WaitNode q; (q = waiters) != null;) {
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }
				// 执行done方法
        done();

        callable = null;        // to reduce footprint
    }

    /**
     * 等待任务完成，或正常完成，或超时，或中断
     *
     * @param 是否指定超时时间
     * @param 纳秒
     * @return task的状态
     */
    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;
      	// 这里是个死循环，ruturn有两种情况：
        // 1.task状态为终态：state > COMPLETING
      	// 2.task超时了
        for (;;) {
          	// 中断判断
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            int s = state;
          	// 状态大于完成
            if (s > COMPLETING) {
              	// q不等于null说明
                if (q != null)
                    q.thread = null;
              	// 返回状态
                return s;
            }
          	// 状态等于完成，线程yield
          	// yield相当于线程让步，让出CPU，从执行状态变为可执行状态
            else if (s == COMPLETING) // cannot time out yet
                Thread.yield();
          	// 如果q==null，说明还未初始化
            else if (q == null)
                q = new WaitNode();
          	// 排队是否成功，非成功的话，去排队
            else if (!queued)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                     q.next = waiters, q);
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
              	// park住，带超时时间
                LockSupport.parkNanos(this, nanos);
            }
            else
              	// park住
                LockSupport.park(this);
        }
    }

    /**
     * 移除waiter
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (;;) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                          q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
```

其实整体捋下来，感觉代码不是很清晰。

但是我们可以记住两个点，就可以知道FutureTask是如何进行异步执行的：

1. `get()`方法中，有一个`awaitDone`，他是一个自旋方法，当任务未完成时会进行park
2. `run()`方法中，会调用`Callable`的`call`方法，`call`方法执行结束，会对outcome进行set，这时会执行finishCompletion方法，该方法中会进行unpark