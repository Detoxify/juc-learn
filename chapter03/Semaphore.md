Semaphore

在Doug Lea的java doc中，Semaphore维持着一个许可证的集合。没次acquire的时候发一张许可证，没次release的时候往池子里放一张许可证。Semaphore通常用于控制线程访问某些资源。

下面先看下Doug Lea给我们展示的使用例子：

### Example

```java
class Pool
		// 最大可用数
    private static final int MAX_AVAILABLE = 100;
		// 最大可用数为100的资源池
    private final Semaphore available = new Semaphore(MAX_AVAILABLE, true);
		
		// 获取下一个可用的item
    public Object getItem() throws InterruptedException 
      	// 先acquire一下
        available.acquire();
				// 获取item
        return getNextAvailableItem();
    }
		
		// 让一个资源进池子
    public void putItem(Object x) {
        if (markAsUnused(x))
        available.release();
    }

    // Not a particularly efficient data structure; just for demo
    protected Object[] items = ... whatever kinds of items being managed
    protected boolean[] used = new boolean[MAX_AVAILABLE];
		
		/**
		 * 从池子里拿资源
		 */
    protected synchronized Object getNextAvailableItem() {
      	// 从0开始遍历，看看哪个位置的item没有被使用，返回items对应位置未被使用的item
        for (int i = 0; i < MAX_AVAILABLE; ++i) {
            if (!used[i]) {
              	// 设置资源已被占用
                used[i] = true;
                return items[i];
            }
        }
        return null;
    }

		/**
		 * 把资源放回池子
		 */
		protected synchronized boolean markAsUnused(Object item) {
       for (int i = 0; i < MAX_AVAILABLE; ++i) {
           if (item == items[i]) {
                if (used[i]) {
                    used[i] = false;
                    return true;
                } else
                  	return false;
           }
       }
     return false;
   }
}
```

看起来Semaphore实现了对池化资源的控制，主要依靠`available.acquire()`和`available.release()`做到的，下面分析下这两个方法。

Semaphore也是基于AQS来实现的，有一个内部类Sync。那我们跟分析CountDownLatch一样，先看下Sync的源码。

### 内部类Sync

```java
/**
 * 抽象内部类 Sync
 * 所以肯定还有具体实现，也就是FairSync和NonFairSync呗
 */
abstract static class Sync extends AbstractQueuedSynchronizer {
    private static final long serialVersionUID = 1192457210091910933L;
		
  	/**
  	 * 根据permits实例话Sync，设置state
  	 */ 
    Sync(int permits) {
        setState(permits);
    }

    final int getPermits() {
        return getState();
    }

  	/**
  	 * 非公平尝试抢资源（共享）
  	 */
    final int nonfairTryAcquireShared(int acquires) {
        for (;;) {
          	// 获取可用资源
            int available = getState();
          	// 剩余资源 = 可用资源 - 请求资源
            int remaining = available - acquires;
          	// 剩余资源 < 0，直接返回
          	// 剩余资源 > 0，CAS抢资源，成功返回，失败再自旋来抢
            if (remaining < 0 ||
                compareAndSetState(available, remaining))
                return remaining;
        }
    }

  	/**
  	 * 尝试释放资源（共享）
  	 */
    protected final boolean tryReleaseShared(int releases) {
        for (;;) {
          	// 获取当前state
            int current = getState();
          	// 恢复之后的资源数
            int next = current + releases;
            if (next < current) // overflow
                throw new Error("Maximum permit count exceeded");
          	// CAS设置资源，失败自旋
            if (compareAndSetState(current, next))
                return true;
        }
    }

  	/**
  	 * 减少许可证
  	 */
    final void reducePermits(int reductions) {
        for (;;) {
            int current = getState();
            int next = current - reductions;
            if (next > current) // underflow
                throw new Error("Permit count underflow");
            if (compareAndSetState(current, next))
                return;
        }
    }
		
  	/**
  	 * 排干许可证，也就是许可证数量变到0
  	 */
    final int drainPermits() {
        for (;;) {
            int current = getState();
            if (current == 0 || compareAndSetState(current, 0))
                return current;
        }
    }
}

/**
 * 非公平版本
 */
static final class NonfairSync extends Sync {
    private static final long serialVersionUID = -2694183684443567898L;

    NonfairSync(int permits) {
        super(permits);
    }

    protected int tryAcquireShared(int acquires) {
        return nonfairTryAcquireShared(acquires);
    }
}

/**
 * 公平版本
 */
static final class FairSync extends Sync {
    private static final long serialVersionUID = 2014338818796000944L;

    FairSync(int permits) {
        super(permits);
    }

    protected int tryAcquireShared(int acquires) {
        for (;;) {
          	// 多了一个排队检查
            if (hasQueuedPredecessors())
                return -1;
            int available = getState();
            int remaining = available - acquires;
            if (remaining < 0 ||
                compareAndSetState(available, remaining))
                return remaining;
        }
    }
}
```

### acquire

```java
public void acquire() throws InterruptedException {
    sync.acquireSharedInterruptibly(1);
}

public final void acquireSharedInterruptibly(int arg)
        throws InterruptedException {
    if (Thread.interrupted())
        throw new InterruptedException();
  	// tryAcquireShared方法的公平版本和非公平版本实现都在上面，回头瞅一眼
  	// 如果tryAcquireShared返回值小于0，说明获取资源失败了
    if (tryAcquireShared(arg) < 0)
        doAcquireSharedInterruptibly(arg);
}

/**
 * 这个方法应该有印象吧，CountDownLatch中一样的方法
 * 第一次进来park线程
 * 在这个情境下，被唤醒之后去尝试获取资源，看看能不能抢到，抢不到就继续park，等待unpark
 */
private void doAcquireSharedInterruptibly(int arg)
    throws InterruptedException {
    final Node node = addWaiter(Node.SHARED);
    boolean failed = true;
    try {
        for (;;) {
            final Node p = node.predecessor();
            if (p == head) {
                int r = tryAcquireShared(arg);
                if (r >= 0) {
                  	// 这里和CountDownLatch一样，会从头开始唤醒所有等待线程
                  	// 不同的是CountDownLatch会所有线程都进来
                  	// Semaphore还需要抢到资源才进来
                    setHeadAndPropagate(node, r);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
            }
            if (shouldParkAfterFailedAcquire(p, node) &&
                parkAndCheckInterrupt())
                throw new InterruptedException();
        }
    } finally {
        if (failed)
            cancelAcquire(node);
    }
}
```

### release

```java
public void release(int permits) {
    if (permits < 0) throw new IllegalArgumentException();
    sync.releaseShared(permits);
}

public final boolean releaseShared(int arg) {
    if (tryReleaseShared(arg)) {
        doReleaseShared();
        return true;
    }
    return false;
}

private void doReleaseShared() {
    for (;;) {
        Node h = head;
        if (h != null && h != tail) {
            int ws = h.waitStatus;
            if (ws == Node.SIGNAL) {
                if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                    continue;            // loop to recheck cases
                unparkSuccessor(h);
            }
            else if (ws == 0 &&
                     !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                continue;                // loop on failed CAS
        }
        if (h == head)                   // loop if head changed
            break;
    }
}
```

`release`跟之前`CountDownLatch`的代码也是一样的，state减1之后，唤醒后继节点。