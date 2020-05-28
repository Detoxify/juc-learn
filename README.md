# <i class="fab fa-java"></i>j.u.c学习笔记

j.u.c包是java SDK自带的并发包，笔记主要包含两个部分：源码 + 示例，着重分析平时工作中用到的比较多的类。

主要是对自己最近学习知识的总结，因为我在学习过程中发现，学习新东西的时候感觉自己懂了，但是过段时间回想起来，印象就会很模糊。

所以需要有一个总结的过程，将知识变为自己的。

参考资料挺多的，主要推荐下下面两个：
* 极客时间的一门课程，写的很好，偏实战，推荐把每篇文章的评论都看下。[Java并发编程实战](https://time.geekbang.org/column/intro/100023901)
* 一个大佬的博客，主要是juc下面各种源码的分析，还有一些其他框架的源码分析。[javadoop](https://www.javadoop.com/)

目前的规划如下：

* 并发理论基础
  * 并发问题产生的原因
  * 并发问题的解决方案
  * 线程
  * 管程
* java同步原语
  * synchronized
  * volatile
* j.u.c包
  * 总览
  * AQS
  * ReentrantLock
  * Condition
  * CountDownLatch
  * CyclicBarrier
  * Semaphore
  * ArrayBolckingQueue
  * LinkedBlockingQueue
  * Future
  * Executor
  * ConcurrentHashMap
