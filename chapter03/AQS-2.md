# AQS-2

上一篇文章AQS中，已经通过源码分析了ReentrantLock的`lock`和`unlock`方法。

今天主要通过ReentrantLock来看下两个部分的内容：

1. 公平锁和非公平锁的实现差异
2. AQS中的ConditionObject

### FairSync vs NonfairSync

```java
/**
 * 非公平锁-ReentrantLock默认使用
 */
static final class NonfairSync extends Sync {
    private static final long serialVersionUID = 7316153563782823691L;

    /**
     * 上来就先抢锁试试，万一成了呢
     * 抢不到在去走公平锁那一套，什么判断state，入队，unpark，巴拉巴拉
     */
    final void lock() {
        if (compareAndSetState(0, 1))
            setExclusiveOwnerThread(Thread.currentThread());
        else
            acquire(1);
    }

    protected final boolean tryAcquire(int acquires) {
        return nonfairTryAcquire(acquires);
    }
}

/**
 * 公平锁，上篇文章已经说清楚了
 */
static final class FairSync extends Sync {
    private static final long serialVersionUID = -3000897897090466540L;

    final void lock() {
        acquire(1);
    }

    /**
     * 公平锁多一个判断：!hasQueuedPredecessors()
     */
    protected final boolean tryAcquire(int acquires) {
        final Thread current = Thread.currentThread();
        int c = getState();
        if (c == 0) {
            if (!hasQueuedPredecessors() &&
                compareAndSetState(0, acquires)) {
                setExclusiveOwnerThread(current);
                return true;
            }
        }
        else if (current == getExclusiveOwnerThread()) {
            int nextc = c + acquires;
            if (nextc < 0)
                throw new Error("Maximum lock count exceeded");
            setState(nextc);
            return true;
        }
        return false;
    }
}
```

非公平锁和公平锁，只要是两个地方不同：

1. 非公平锁lock之后，直接进行一次CAS抢锁
2. 非公平锁CAS失败之后，如果发现当前state == 0，之前会CAS再抢一次，不关心前面有没有线程等待

由此可见，非公平锁的性能会更好，因为吞吐量比较大，同时也可能会造成饥饿问题。

### ConditionObject

`ConditionObject`翻译过来就是条件对象，类似于Object里面的wait，不过更加灵活，一个lock上面可以生成多个Condition。和wait一样，Condition.await()需要先获取到lock才能执行。

先来一个自己搞出来的简单例子：

```java
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
```

控制台打印如下：

>condition1 await
>condition2 await
>signal condition2
>condition2 signal
>signal condition1
>condition1 signal

通过控制台打印，我们可以发现如下规则：

1. `await`方法可以是使线程在当前位置阻塞
2. 阻塞可以通过`signalAll`进行唤醒
3. `await`方法会释放锁，让其他线程可以获取锁，然后唤醒它

下面，通过源码来看看Doug Lea到底干了啥。

ConditionObject实现了Condition接口，接口方法如下：

```java
public interface Condition {
    void await() throws InterruptedException;
    void awaitUninterruptibly();
    long awaitNanos(long nanosTimeout) throws InterruptedException;
    boolean await(long time, TimeUnit unit) throws InterruptedException;
    boolean awaitUntil(Date deadline) throws InterruptedException;
    void signal();
    void signalAll();
}
```

对于Condition接口的实现，主要关注四个方法：`await()`、`awaitNanos(timeout)`、`signal()`、`signalAll()`，这几个方法在AQS中都有相关实现。

#### await()

```java
/**
 * 实现可中断的条件等待
 * 1.如果线程被中断，抛出InterruptedException
 * 2.保存getState返回的lock state
 * 3.释放锁
 * 4.在被通知和中断之前，阻塞
 * 5.重新acquire时，使用saveState
 * 6.如果在4中中断了，抛InterruptedException
 */
public final void await() throws InterruptedException {
  	// 先判断一波中断状态
    if (Thread.interrupted())
        throw new InterruptedException();
  	// 添加到条件队列的尾部，Node里面的nextWaiter，返回刚包装好的Node对象
    Node node = addConditionWaiter();
  	// 放弃锁，里面会unpark其他排队等锁的线程
    int savedState = fullyRelease(node);
    int interruptMode = 0;
    while (!isOnSyncQueue(node)) {
      	// 如果不在同步队列，就一直自旋
      	// 每次被unpark之后，都会判断一下isOnSyncQueue(node)
      	// 如果node在条件队列上，那就park住
        LockSupport.park(this);
      	// 判断wait中是不是发生过中断，中断过就break
      	// 如果在signal之前已经中断，interruptMode == THROW_IE
      	// 如果在signal之后中断，interruptMode == REINTERRUPT
        if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
            break;
    }
  	// 走到这里，说明两种情况发生了：
  	// 1：node已经在阻塞队列， 可以抢锁了
  	// 2：发生过中断，break过来的
  	// 等待获取锁，在这之前应该unpark将它放到同步队列的
    if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
        interruptMode = REINTERRUPT;
  	// 如果nextWaiter ！= null，把取消的节点都给移除
    if (node.nextWaiter != null) 
        unlinkCancelledWaiters();
  	// 如果发生过了中断，处理中断
    if (interruptMode != 0)
        reportInterruptAfterWait(interruptMode);
}
```

#### await(timeout)

```java
public final long awaitNanos(long nanosTimeout)
        throws InterruptedException {
    if (Thread.interrupted())
        throw new InterruptedException();
    Node node = addConditionWaiter();
    int savedState = fullyRelease(node);
  	// 获取一个deadline
    final long deadline = System.nanoTime() + nanosTimeout;
    int interruptMode = 0;
    while (!isOnSyncQueue(node)) {
      	// 检测下nanosTimeout是否大于0，小于等于0直接取消
      	// 因为在自旋方法了，没次都会用deadline去减
      	// 超时了会直接break
        if (nanosTimeout <= 0L) {
            transferAfterCancelledWait(node);
            break;
        }
      	// spinForTimeoutThreshold -> 自旋超时阈值 1000
      	// 如果还不足1000纳秒（1毫秒），直接自旋，不用park了
        if (nanosTimeout >= spinForTimeoutThreshold)
          	// LockSupport.parkNanos()方法，底层帮忙实现了park
            LockSupport.parkNanos(this, nanosTimeout);
        if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
            break;
        nanosTimeout = deadline - System.nanoTime();
    }
  	// 如果因为超时了，过来抢锁怎么办？
  	// unparkSuccessor()的时候，后从tail开始，找一个排队在最前面的，并且waitStatus<=0的进行唤醒
  	// 如果是超时的话，node虽然被移到了同步队列，但是waitStatus是取消状态，等于1，不会被唤醒的
    if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
        interruptMode = REINTERRUPT;
    if (node.nextWaiter != null)
        unlinkCancelledWaiters();
    if (interruptMode != 0)
        reportInterruptAfterWait(interruptMode);
    return deadline - System.nanoTime();
}
```

#### signal

signal是用来唤醒一个await线程的

```java
/**
 * 移动等待时间最长的线程
 * 从某个条件的等待队列移到锁的同步等待队列中
 */
public final void signal() {
  	// 看下是不是持有锁的线程来的，不是直接抛IllegalMonitorStateException
    if (!isHeldExclusively())
        throw new IllegalMonitorStateException();
  	// 找到firstWaiter
    Node first = firstWaiter;
  	// 没有firstWaiter，不作处理
    if (first != null)
      	// 唤醒firstWaiter
        doSignal(first);
}

/**
 * 从条件队列的对头开始遍历，找到第一个需要转移的node
 * 因为fullRelease的时候，有可能把节点状态设置为CANCELED
 */
private void doSignal(Node first) {
    do {
      	// 如果first后面没有了，要把lastWaiter置为空
        if ( (firstWaiter = first.nextWaiter) == null)
            lastWaiter = null;
        first.nextWaiter = null;
    } while (!transferForSignal(first) &&
             (first = firstWaiter) != null);
  	// 这里 while 循环，如果 first 转移不成功，那么选择 first 后面的第一个节点进行转移，依此类推
}

/**
 * 将一个节点从条件队列转移到同步队列
 */
final boolean transferForSignal(Node node) {
    // 如果不能改变状态，说明节点已经被取消了
    if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
        return false;

    // node入队
    Node p = enq(node);
    int ws = p.waitStatus;
  	// 上面刚刚设置了个0，所以会走CAS，设置SIGNAL，以便通知后继节点
    if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
      	// 入队和修改状态成功之后，unpark线程
        LockSupport.unpark(node.thread);
    return true;
}
```

#### signalAll()

`signalAll()`就是唤醒所有等待在条件上的线程，全都放到同步队列中去

```java
/**
 * 跟上面差不多，区别在doSignalAll(first)
 */
public final void signalAll() {
    if (!isHeldExclusively())
        throw new IllegalMonitorStateException();
    Node first = firstWaiter;
    if (first != null)
        doSignalAll(first);
}

/**
 * while循环中，不止唤醒一个，从first开始，逐个唤醒
 * transferForSignal(first)和上面一样，就不重复说了
 */
private void doSignalAll(Node first) {
    lastWaiter = firstWaiter = null;
    do {
        Node next = first.nextWaiter;
        first.nextWaiter = null;
        transferForSignal(first);
        first = next;
    } while (first != null);
}
```

### 总结

这篇文章主要还是分析了Condition接口上的四个方法，总结一下Condition的实现如下：

1. `await`操作，将线程封装成Node，用Node中nextWaiter穿成单链表，当前node连到链表的尾部
2. 释放自己持有的锁，也就是`fullRelease(getState())`
3. `park()`住自己，等待别人唤醒，这个时候`await`就被阻塞在这里
4. `signalAll()`，从头遍历nextWaiter单链表，一个接一个将它们移到同步队列，移完之后进行unpark