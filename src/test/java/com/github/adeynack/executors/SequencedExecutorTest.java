package com.github.adeynack.executors;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

  private Runnable createTask(final int taskId, final boolean rePost, final boolean fail) {
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
              sequencedExecutor.execute(createTask(taskId + 100, false, false));
            }
            if (fail) {
              throw new RuntimeException(String.format("Task %s fails", taskId));
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
      } finally {
        System.out.println(String.format("[%s] End of runnable.", taskId));
        int t = createdTaskCounter.decrementAndGet();
        System.out.println(String.format("- There are now %s tasks created.", t));
        if (t == 0) {
          startedTaskWait.complete(0);
        }
      }
    };
  }

  @Test
  public void twoTasksAreNotExecutedAtTheSameTime() throws Exception {
    IntStream.range(0, 12)
             .forEach(i -> sequencedExecutor.execute(createTask(i, false, false)));
    startedTaskWait.get(1, TimeUnit.MINUTES);
    assertEquals("No lock should be refused.", 0, refusedLocks.get());
  }

  @Test
  public void twoTasksAreNotExecutedAtTheSameTimeWhenSubmittedFromAnExecutedTask() throws Exception {
    IntStream.range(0, 12)
             .forEach(i -> sequencedExecutor.execute(createTask(i, true, false)));
    startedTaskWait.get(1, TimeUnit.MINUTES);
    assertEquals("No lock should be refused.", 0, refusedLocks.get());
  }

  @Test
  public void aFailingTaskDoesNotCrashTheExecutor() throws Exception {
    final List<CompletableFuture<Void>> futures =
        IntStream.range(0, 12)
                 .mapToObj(i -> CompletableFuture.runAsync(createTask(i, false, i == 5), sequencedExecutor))
                 .collect(Collectors.toList());

    // Expecting the execution to fail.
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[futures.size()]))
                       .get(1, TimeUnit.MINUTES);
      fail("Expecting one of the future to throw an exception");
    } catch (ExecutionException executionException) {
      final Throwable cause = executionException.getCause();
      assertThat(cause, instanceOf(RuntimeException.class));
      assertEquals("Task 5 fails", cause.getMessage());
    }

    assertEquals("No lock should be refused.", 0, refusedLocks.get());

    // future at index 5 should have failed.
    assertTrue(futures.get(5).isCompletedExceptionally());
    // all others should have not
    assertFalse(futures.get(0).isCompletedExceptionally());
    assertFalse(futures.get(1).isCompletedExceptionally());
    assertFalse(futures.get(2).isCompletedExceptionally());
    assertFalse(futures.get(3).isCompletedExceptionally());
    assertFalse(futures.get(4).isCompletedExceptionally());
    assertFalse(futures.get(6).isCompletedExceptionally());
    assertFalse(futures.get(7).isCompletedExceptionally());
    assertFalse(futures.get(8).isCompletedExceptionally());
    assertFalse(futures.get(9).isCompletedExceptionally());
    assertFalse(futures.get(10).isCompletedExceptionally());
    assertFalse(futures.get(11).isCompletedExceptionally());
  }

  @Test
  public void whenUsedInAFutureItReturnsTheValue() throws Exception {
    final List<CompletableFuture<Integer>> futures =
        IntStream.range(0, 12)
                 .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                   createTask(i, false, false).run();
                   return i;
                 }, sequencedExecutor))
                 .collect(Collectors.toList());

    CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[futures.size()]))
                     .get(1, TimeUnit.MINUTES);

    assertEquals("No lock should be refused.", 0, refusedLocks.get());

    assertEquals(0, futures.get(0).get().intValue());
    assertEquals(1, futures.get(1).get().intValue());
    assertEquals(2, futures.get(2).get().intValue());
    assertEquals(3, futures.get(3).get().intValue());
    assertEquals(4, futures.get(4).get().intValue());
    assertEquals(5, futures.get(5).get().intValue());
    assertEquals(6, futures.get(6).get().intValue());
    assertEquals(7, futures.get(7).get().intValue());
    assertEquals(8, futures.get(8).get().intValue());
    assertEquals(9, futures.get(9).get().intValue());
    assertEquals(10, futures.get(10).get().intValue());
    assertEquals(11, futures.get(11).get().intValue());
  }

}
