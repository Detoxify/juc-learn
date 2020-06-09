# BlockingQueue

首先，介绍BlockingQueue的接口规则，之后会介绍几个重要的BlockingQueue的实现。

### BlockingQueue接口

BlockingQueue，字面翻译过来就是：阻塞队列。

* 阻塞：BlockQueue支持获取队列元素，如果为空时，阻塞等待队列中有元素才返回。放入元素也需要等待队列不满。
* 队列：FIFO的队列，先进先出

BlockingQueue继承自Queue，而Queue又继承自Collection接口。

BlockingQueue对于插入、移除、获取元素，提供了四种不同的方法，对应不同的场景：

1. 抛出异常
2. 返回特殊值（null或true/false）
3. 阻塞等待
4. 带超时时间的阻塞等待

|             | Throws Exception | Special value | Blocks | TImes out            |
| ----------- | ---------------- | ------------- | ------ | -------------------- |
| **Insert**  | add(e)           | offer(e)      | put(e) | offer(e, time, unit) |
| **Remove**  | remove()         | poll()        | take() | poll(time, unit)     |
| **Examine** | element()        | peek()        | -      | -                    |

BlockingQueue是用来设计实现生产者-消费者队列的。

同时BlockingQueue的实现也都是线程安全的，支持多生产者、多消费者同时生产消费。

对于BlockingQueue的各种实现，我们主要关注阻塞方法：put(e)和take()

### ArrayBlockingQueue

#### 成员变量

```java
/** 列队的所有元素 */
final Object[] items;

/** 下一个要被拿走元素的位置：take, poll, peek or remove */
int takeIndex;

/** 下一个需要放入元素的位置：put, offer, or add */
int putIndex;

/** 队列中元素的数量 */
int count;

/*
 * 并发控制使用经典的双条件算法
 */

/** 所有操作的主锁 */
final ReentrantLock lock;

/** take时要用的条件 */
private final Condition notEmpty;

/** put时要用的条件 */
private final Condition notFull;
```

通过成员变量，我们可以发现ArrayBlockingQueue底层是基于数组实现的。

具体的take和put操作是如何实现阻塞的呢？开始来撕代码。

#### put(e)

```java
/**
 * 往队列尾部插入一个元素，如果队列已满，那就阻塞等待可以插入为止
 */
public void put(E e) throws InterruptedException {
  	// BlockingQueue不能插入null
    checkNotNull(e);
  	// 锁一下
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();
    try {
      	// 如果当前元素数量 == 初始化队列大小，等待notFull条件
      	// 注意这里是个while，如果多个线程被唤醒，也只有一个线程可以成功获取到锁，其他线程可能会继续wait
        while (count == items.length)
            notFull.await();
      	// 等待结束，重新获取锁，将元素加入到队尾
        enqueue(e);
    } finally {
        lock.unlock();
    }
}

/**
 * 在putIndex位置插入元素x，
 * 持有锁的时候才能调用
 */
private void enqueue(E x) {
  	// 获取队列
    final Object[] items = this.items;
  	// 将元素x放入队列
    items[putIndex] = x;
  	// putIndex加之后看有没有超过容量
    if (++putIndex == items.length)
      	// 超过容量，下次从头开始
        putIndex = 0;
  	// 队列中元素数量加1
    count++;
  	// 队列中有元素了，唤醒等待在notEmpty条件上的第一个线程
   	// 这里为什么只唤醒一个呢？
  	// 还要看下，notEmpty被唤醒的地方干了啥
    notEmpty.signal();
}
```

到这，put(e)操作就完事了，主要分为以下两步：

1. 看下队列是不是满着，满着的话在notFull上wait，否则直接将元素插入到指定位置
2. 将元素插入时，队列中相当于有元素了，会出发notEmpty.signal()

#### take

```java
public E take() throws InterruptedException {
    // 先锁上
  	final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();
    try {
      	// 如果count == 0，在notEmpty上wait
        while (count == 0)
            notEmpty.await();
      	// 被唤醒之后，操作出队
        return dequeue();
    } finally {
        lock.unlock();
    }
}

private E dequeue() {
    final Object[] items = this.items;
    @SuppressWarnings("unchecked")
    E x = (E) items[takeIndex];
    items[takeIndex] = null;
  	// takeIndex加1，下次拿后面的
    if (++takeIndex == items.length)
        takeIndex = 0;
  	// 队列中元素数量减1
    count--;
    if (itrs != null)
        itrs.elementDequeued();
  	// 有元素被take出去了，通知notFull条件上等待的线程
    notFull.signal();
    return x;
}
```

ArrayBlockingQueue的特点：

1. put和take使用同一个锁，不能并行
2. 每次只signal一个，其实多了也没用，操作会排队
3. ArrayBlockingQueue内部使用的是数组，创建的时候必须指定大小

![](../img/array-blocking-queue.png)

如上图所示，读线程和写线程有自己的条件队列，但是他俩移出条件队列后，进入的是同一个同步队列，因为使用的是同一把锁，所以无法实现读写并发。

### LinkedBlockingQueue

#### 成员变量

```java
/**
 * 链表的节点，只有next，是一个单向链表
 */
static class Node<E> {
    E item;

    Node<E> next;

    Node(E x) { item = x; }
}

/** 绑定的容量，如果没设置就是：Integer.MAX_VALUE */
private final int capacity;

/** 链表中元素数量 */
private final AtomicInteger count = new AtomicInteger();

/**
 * 链表的头部
 * head.item == null
 */
transient Node<E> head;

/**
 * 链表的尾部
 * last.next == null
 */
private transient Node<E> last;

/** 读锁：take, poll, etc */
private final ReentrantLock takeLock = new ReentrantLock();

/** 读锁的等待条件 */
private final Condition notEmpty = takeLock.newCondition();

/** 写锁：put, offer, etc */
private final ReentrantLock putLock = new ReentrantLock();

/** 写锁的等待条件 */
private final Condition notFull = putLock.newCondition();
```

可以看到，LinkedBlockingQueue是基于单向链表实现的，同时有两个锁：读锁和写锁，那么说明读写可以并发执行，互不干涉。

* takeLock和notEmpty搭配，take的时候需要：获取到takeLock + notEmpty条件满足。
* putLock和notFull搭配，put的时候需要：获取到putLock + notFull条件满足。	

还是先看看put方法

#### put(e)

```java
/**
 * 将元素插入到队列尾部，没有空间时阻塞等待
 */
public void put(E e) throws InterruptedException {
  	// 不能插入null
    if (e == null) throw new NullPointerException();
    // -1代表抢锁失败
    int c = -1;
    Node<E> node = new Node<E>(e);
  	// 写锁
    final ReentrantLock putLock = this.putLock;
    final AtomicInteger count = this.count;
  	// 写锁锁定
    putLock.lockInterruptibly();
    try {
        // 如果队列已满，等待notFull条件
        while (count.get() == capacity) {
            notFull.await();
        }
      	// notFull条件满足，节点加入到队列中
        enqueue(node);
      	// CAS操作count加1
        c = count.getAndIncrement();
      	// 如果put进一个元素之后，队列还有空余，那就再唤醒一个等待在notFull上的线程来put
        if (c + 1 < capacity)
            notFull.signal();
    } finally {
      	// 解锁
        putLock.unlock();
    }
  	// 如果c == 0，说明队列在元素入队前是空的，getAndIncrement返回原有值
  	// 那么有元素进来，需要唤醒等待notEmpty的线程
    if (c == 0)
        signalNotEmpty();
}

/**
 * 加入到队列
 */
private void enqueue(Node<E> node) {
  	// assert putLock.isHeldByCurrentThread();
  	// assert last.next == null;
  	last = last.next = node;
}

/**
 * 元素入队后，如果需要，调用这个方法唤醒读线程来读
 */
private void signalNotEmpty() {
    final ReentrantLock takeLock = this.takeLock;
    takeLock.lock();
    try {
        notEmpty.signal();
    } finally {
        takeLock.unlock();
    }
}
```

总结一下：

1. put的时候，需要先看下是不是已满了，满了就要等待notFull条件。
2. put之前，如果队列没有元素，需要唤醒等待在notEmpty上面的条件，可以take了。

#### take

```java
public E take() throws InterruptedException {
    E x;
    int c = -1;
    final AtomicInteger count = this.count;
    final ReentrantLock takeLock = this.takeLock;
  	// 读锁
    takeLock.lockInterruptibly();
    try {
      	// 队列中没有元素，等待notEmpty条件
        while (count.get() == 0) {
            notEmpty.await();
        }
      	// 队列中有元素，进行出队操作
        x = dequeue();
      	// CAS count值减1
        c = count.getAndDecrement();
      	// 减完队列中还有元素，再唤醒一个线程来读
        if (c > 1)
            notEmpty.signal();
    } finally {
      	// 释放读锁
        takeLock.unlock();
    }
  	// 如果是已满的状态下，被take走了一个元素
  	// 通知等待在notFull条件上的线程
    if (c == capacity)
        signalNotFull();
    return x;
}

/**
 * head后面的节点出队
 */
private E dequeue() {
    Node<E> h = head;
    Node<E> first = h.next;
    h.next = h; // help GC
    head = first;
    E x = first.item;
    first.item = null;
    return x;
}

/**
 * 唤醒一个等待在notFull的线程
 */
private void signalNotFull() {
    final ReentrantLock putLock = this.putLock;
    putLock.lock();
    try {
      notFull.signal();
    } finally {
      putLock.unlock();
    }
}
```

总结一下take过程：

1. 先看下队列有没有元素，没有元素就等待在notEmpty条件上
2. 如果队列中有元素，直接取出，并且看下是不是在队列已满状态下取出的元素，如果是，通知等待在notFull的线程，可以put了。

### SynchronousQueue

待填坑

### PriorityBlockingQueue

待填坑