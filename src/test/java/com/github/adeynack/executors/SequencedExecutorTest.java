package com.github.adeynack.executors;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

public class SequencedExecutorTest {

  private Executor sequencedExecutor;
  private AtomicInteger refusedLocks;
  private Lock testLock;
  private AtomicInteger createdTaskCounter;
  private CompletableFuture<Integer> startedTaskWait;

  @Before
  public void before() {
    final Executor baseExecutor = Executors.newFixedThreadPool(4);
    sequencedExecutor = new SequencedExecutor(baseExecutor);
    refusedLocks = new AtomicInteger();
    testLock = new ReentrantLock();
    createdTaskCounter = new AtomicInteger();
    startedTaskWait = new CompletableFuture<>();
  }

  private Runnable createTask(final int taskId, final boolean rePost) {
    int nt = createdTaskCounter.incrementAndGet();
    System.out.println(String.format("+ There are now %s tasks created.", nt));
    return () -> {
      try {
        System.out.println(String.format("[%s] Waiting on testLock", taskId));
        final boolean gotLock = testLock.tryLock();
        try {
          if (!gotLock) {
            System.out.println(String.format("[%s] ERROR: Could not obtain lock", taskId));
            refusedLocks.incrementAndGet();
          } else {
            System.out.println(String.format("[%s] Lock obtained.", taskId));
            if (rePost) {
              sequencedExecutor.execute(createTask(taskId + 100, false));
            }
            Thread.sleep(100);
          }
        } finally {
          if (gotLock) {
            System.out.println(String.format("[%s] Unlocking testLock", taskId));
            testLock.unlock();
          }
        }
      } catch (InterruptedException e) {
        System.err.println(String.format("[%s] %s", taskId, e));
      }
      System.out.println(String.format("[%s] End of runnable.", taskId));
      int t = createdTaskCounter.decrementAndGet();
      System.out.println(String.format("- There are now %s tasks created.", t));
      if (t == 0) {
        startedTaskWait.complete(0);
      }
    };
  }

  @Test
  public void twoTasksAreNotExecutedAtTheSameTime() throws Exception {
    IntStream.range(0, 12)
             .forEach(i -> sequencedExecutor.execute(createTask(i, false)));
    startedTaskWait.get(1, TimeUnit.MINUTES);
    Assert.assertEquals("No lock should be refused.", 0, refusedLocks.get());
  }

  @Test
  public void twoTasksAreNotExecutedAtTheSameTimeWhenSubmittedFromAnExecutedTask() throws Exception {
    IntStream.range(0, 12)
             .forEach(i -> sequencedExecutor.execute(createTask(i, true)));
    startedTaskWait.get(1, TimeUnit.MINUTES);
    Assert.assertEquals("No lock should be refused.", 0, refusedLocks.get());
  }

}
