CountDownLatch

CountDownLatch是AQS中比较常用的类，可以多个线程共享使用，规定一个值，大家一起去countDown。latch是门闩的意思，所以CountDownLatch也的作业也和门闩类似。

先来看下Doug Lea在CountDownLatch给我们提供的例子：

### Example

```java

class Driver {
	   void main() throws InterruptedException {
         // 定义一个开始信号：1
         CountDownLatch startSignal = new CountDownLatch(1);
         // 定义一个完成信号：N
         CountDownLatch doneSignal = new CountDownLatch(N);
         // 创建多个线程，把开始信号和完成信号都传进去
         // Worker的构造在下面，先去看一下
         for (int i = 0; i < N; ++i) 
           new Thread(new Worker(startSignal, doneSignal)).start();
				 // 等一会，让所有线程都到startSignal.await()
         doSomethingElse();
       	 // 开始信号countDown，大家一起开始执行任务
         startSignal.countDown();
         doSomethingElse();
       	 // 开始在这等所有Worker执行完任务
         doneSignal.await(); 
   	 }
}

class Worker implements Runnable {
    private final CountDownLatch startSignal;
    private final CountDownLatch doneSignal;
    Worker(CountDownLatch startSignal, CountDownLatch doneSignal) {
        this.startSignal = startSignal;
        this.doneSignal = doneSignal;
    }
    public void run() {
        try {
          	// 所有Worker都在这等着 startSignal 的countDown
            startSignal.await();
          	// 开始工作
            doWork();
          	// 工作完成后，对doneSignal countDown一下
            doneSignal.countDown();
        } catch (InterruptedException ex) {}
    }
 
    void doWork() { ... }
}
```

这是CountDownLatch的一种经典用法，多线程协作完成任务，同时开始执行任务，等任务全部完成做数据处理。

CountDownLatch是如何实现`await`和`countDown`的呢？答案是通过`state`，来具体看下源码吧。

### 内部类Sync

```java
private static final class Sync extends AbstractQueuedSynchronizer {
    private static final long serialVersionUID = 4982264981922014374L;
		
  	// 通过构造方法，为AQS的state设置值
    Sync(int count) {
        setState(count);
    }
		// 读AQS的state
    int getCount() {
        return getState();
    }
		
  	// 共享模式获取资源
  	// 如果state == 0，则返回1，否则返回-1
    protected int tryAcquireShared(int acquires) {
        return (getState() == 0) ? 1 : -1;
    }
		
  	// 共享模式释放资源
  	// CAS设置state
    protected boolean tryReleaseShared(int releases) {
        for (;;) {
            int c = getState();
            if (c == 0)
                return false;
            int nextc = c-1;
            if (compareAndSetState(c, nextc))
                return nextc == 0;
        }
    }
}
```

从上面代码可以发现，CountDownLatch中有一个Sync内部类，和ReentrantLock的结构很像。

### await

```java
public void await() throws InterruptedException {
    sync.acquireSharedInterruptibly(1);
}

public final void acquireSharedInterruptibly(int arg)
        throws InterruptedException {
    if (Thread.interrupted())
        throw new InterruptedException();
  	// 如果成功，直接返回
    // 如果失败，说明现在state ！= 0，执行doAcquireSharedInterruptibly
    // 因为创建CountDownLatch就给state赋了大于0的值，所以这里一定会执行doAcquireSharedInterruptibly
    if (tryAcquireShared(arg) < 0)
        doAcquireSharedInterruptibly(arg);
}

/**
 * 共享 + 可中断获取
 * 基本上没啥可看的，就是把当前线程包成一个Node，然后park住
 * 相当于await就是让当前线程park住了
 * 这个时候是没有加锁的，只是加入到同步队列中了而已
 */
private void doAcquireSharedInterruptibly(int arg)
    throws InterruptedException {
  	// 以SHARED模式，放到同步队列中
    final Node node = addWaiter(Node.SHARED);
    boolean failed = true;
    try {
        for (;;) {
            final Node p = node.predecessor();
            if (p == head) {
              	// 第一次await，这个时候r肯定是-1，不走下面if方法
                int r = tryAcquireShared(arg);
                if (r >= 0) {
                    setHeadAndPropagate(node, r);
                    p.next = null;
                    failed = false;
                    return;
                }
            }
            if (shouldParkAfterFailedAcquire(p, node) &&
                parkAndCheckInterrupt()) // 这里才会真正的park住，后面还会讲，这里需要记一下
                throw new InterruptedException();
        }
    } finally {
        if (failed)
            cancelAcquire(node);
    }
}
```

既然线程已经被`park`住了，那么我们看一下如何`unpark`的吧。

### countDown

```java
public void countDown() {
    sync.releaseShared(1);
}

public final boolean releaseShared(int arg) {
  	// tryReleaseShared在内部类Sync中，也就是对state每次减1
  	// 当tryReleaseShared减到0的时候，返回true，进入方法，执行doReleaseShared
    if (tryReleaseShared(arg)) {
      	// 真正的unpark
        doReleaseShared();
        return true;
    }
    return false;
}

/**
 * 共享模式下的释放动作 -- 通知后继节点确保传播
 */
private void doReleaseShared() {
    for (;;) {
      	// 获取head节点
        Node h = head;
        if (h != null && h != tail) {
          	// head不为空，切head不是tail，说明同步队列中有Node
            int ws = h.waitStatus;
          	// 看下head节点，是不是SIGNAL
            if (ws == Node.SIGNAL) {
              	// 是SIGNAL，CAS设置head的waitStatus = 0
                if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                   	// CAS失败了就继续循环
                  	// CAS失败了说明这个值已经被别人成功设置过了
                    continue;
              	// CAS成功 唤醒head的后继节点
                unparkSuccessor(h);
            }
          	// waitStatus == 0，说明已经head节点已经被人设置成0了，说明后继节点已经被唤醒了
          	// CAS把head再设置成Node.PROPAGATE
          	// 这种for(;;)里面控制两个步骤真的很酷啊
            else if (ws == 0 &&
                     !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
              	// CAS把head节点waitStatus设置为PROPAGATE失败了，继续下次循环
                continue;
        }
        if (h == head) 
          	// 如果h还是head，break掉
          	// 为什么h还是head的时候break掉呢？
          	// 因为这个时候说明刚刚unpark的线程还没占领head，如果head一直未变，好像也不能说明啥问题
          	// 真特么高级啊
            break;
    }
}

/**
 * 唤醒后继节点
 */
private void unparkSuccessor(Node node) {
    // 看看当前节点是不是-1，如果是CAS设置成0
    int ws = node.waitStatus;
    if (ws < 0)
        compareAndSetWaitStatus(node, ws, 0);

    // 获取下一个节点
    Node s = node.next;
  	// s == null || s.waitStatus > 0 说明next节点不需要唤醒
  	// 从tail开始，找一个排在最前面的
    if (s == null || s.waitStatus > 0) {
        s = null;
        for (Node t = tail; t != null && t != node; t = t.prev)
            if (t.waitStatus <= 0)
                s = t;
    }
  	// unpark
    if (s != null)
        LockSupport.unpark(s.thread);
}
```

上面的操作已经把`await`的线程给唤醒了。

假设说有多个线程`await`呢？

CAS设置head节点的Node.PROPAGATE又是干啥呢？

我们回到线程被park住的地方，继续往下走：

```java
private void doAcquireSharedInterruptibly(int arg)
    throws InterruptedException {
    final Node node = addWaiter(Node.SHARED);
    boolean failed = true;
    try {
        for (;;) {
          	// 2⃣️被unpark之后的第一次循环，获取到unpark节点的前驱节点
            final Node p = node.predecessor();
          	// 3⃣️前驱节点理论上应该是head，因为是按顺序排队的，head唤醒的肯定是head后面的节点
            if (p == head) {
              	// 这是state是0了，r也就是1了
                int r = tryAcquireShared(arg);
                if (r >= 0) {
                  	// 4⃣️重要部分：设置head并且传播，看看里面干啥了
                    setHeadAndPropagate(node, r);
                    p.next = null; 
                    failed = false;
                    return;
                }
            }
            if (shouldParkAfterFailedAcquire(p, node) &&
                // 1⃣️park操作在这里
                // 被unpark之后，继续执行，如果没有中断过，那么会继续走for循环
                parkAndCheckInterrupt())
                throw new InterruptedException();
        }
    } finally {
        if (failed)
            cancelAcquire(node);
    }
}

private void setHeadAndPropagate(Node node, int propagate) {
  	// 记录一下原来的head
    Node h = head; 
  	// 把刚唤醒的节点设置为新的head
    setHead(node);
    // 下面说的是，唤醒当前 node 之后的节点，即 t3 已经醒了，马上唤醒 t4
    // 类似的，如果 t4 后面还有 t5，那么 t4 醒了以后，马上将 t5 给唤醒了
  	// propagate进来的时候就是r，r就是1，1就会进入if
    if (propagate > 0 || h == null || h.waitStatus < 0 ||
        (h = head) == null || h.waitStatus < 0) {
        Node s = node.next;
        if (s == null || s.isShared())
            doReleaseShared();
    }
}
```

