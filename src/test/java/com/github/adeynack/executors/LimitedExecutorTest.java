package com.github.adeynack.executors;

import com.github.adeynack.executors.testTools.SequentialChecker;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("Duplicates") // tolerated for test code
public class LimitedExecutorTest {

  private LimitedExecutor limitedExecutor;
  private SequentialChecker sequentialChecker;
  private AtomicInteger createdTaskCounter;
  private CompletableFuture<Integer> startedTaskWait;

  private final Field fieldTasksSubmitted = LimitedExecutor.class.getDeclaredField("tasksSubmitted");
  private final Field fieldTaskQueue = LimitedExecutor.class.getDeclaredField("taskQueue");

  public LimitedExecutorTest() throws NoSuchFieldException {
  }

  @Before
  public void before() {
    final Executor baseExecutor = Executors.newFixedThreadPool(
        4,
        new ThreadFactoryBuilder().setNameFormat("limited-executor-test-pool-%d").build());
    limitedExecutor = new LimitedExecutor(baseExecutor, 3);
    sequentialChecker = new SequentialChecker();
    createdTaskCounter = new AtomicInteger();
    startedTaskWait = new CompletableFuture<>();
  }

  private Runnable createTask(final Integer taskId, final boolean rePost, final boolean fail) {
    int nt = createdTaskCounter.incrementAndGet();
    System.out.println(String.format("+ There are now %s tasks created.", nt));
    return () -> {
      try {
        sequentialChecker.check(taskId.toString(), true, () -> {
          if (rePost) {
            limitedExecutor.execute(createTask(taskId + 100, false, false));
          }
          if (fail) {
            throw new RuntimeException(String.format("Task %s fails", taskId));
          }
          try {
            Thread.sleep(200);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        });
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

  private void assertAllTasksExecutedOnLimitedExecutor() {
    sequentialChecker.getLog().forEach((entry) -> {
      assertThat(entry.threadName, startsWith("limited-executor-test-pool-"));
    });
  }

  @Test
  public void notMoreThan3TasksAreExecutedAtTheSameTime() throws Exception {
    IntStream.range(0, 24)
             .forEach(i -> limitedExecutor.execute(createTask(i, false, false)));
    startedTaskWait.get(1, TimeUnit.MINUTES);
    sequentialChecker.assertMaxParallelTaskCount(3);
    assertAllTasksExecutedOnLimitedExecutor();
    assertThat(limitedExecutor.getTasksSubmitted(), equalTo(0));
    assertThat(limitedExecutor.getTaskQueue(), empty());
  }

  @Test
  public void notMoreThan3TasksAreExecutedAtTheSameTimeWhenSubmittedFromAnExecutedTask() throws Exception {
    IntStream.range(0, 24)
             .forEach(i -> limitedExecutor.execute(createTask(i, true, false)));
    startedTaskWait.get(1, TimeUnit.MINUTES);
    sequentialChecker.assertMaxParallelTaskCount(3);
    assertAllTasksExecutedOnLimitedExecutor();
    assertThat(limitedExecutor.getTasksSubmitted(), equalTo(0));
    assertThat(limitedExecutor.getTaskQueue(), empty());
  }

  @Test
  public void aFailingTaskDoesNotCrashTheExecutor() throws Exception {
    final List<CompletableFuture<Void>> futures =
        IntStream.range(0, 24)
                 .mapToObj(i -> CompletableFuture.runAsync(createTask(i, false, i == 5), limitedExecutor))
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

    sequentialChecker.assertMaxParallelTaskCount(3);

    // future at index 5 should have failed.
    assertTrue(futures.get(5).isCompletedExceptionally());
    // all others should have not
    IntStream.range(0, 24)
             .filter(i -> i != 5)
             .forEach(i -> assertFalse(futures.get(i).isCompletedExceptionally()));

    assertAllTasksExecutedOnLimitedExecutor();
    assertThat(limitedExecutor.getTasksSubmitted(), equalTo(0));
    assertThat(limitedExecutor.getTaskQueue(), empty());
  }

  @Test
  public void whenUsedInAFutureItReturnsTheValue() throws Exception {
    final List<CompletableFuture<Integer>> futures =
        IntStream.range(0, 24)
                 .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                   createTask(i, false, false).run();
                   return i;
                 }, limitedExecutor))
                 .collect(Collectors.toList());

    CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[futures.size()]))
                     .get(1, TimeUnit.MINUTES);

    sequentialChecker.assertMaxParallelTaskCount(3);

    for (int i = 0; i < 24; ++i) {
      assertEquals(i, futures.get(i).get().intValue());
    }

    assertAllTasksExecutedOnLimitedExecutor();
    assertThat(limitedExecutor.getTasksSubmitted(), equalTo(0));
    assertThat(limitedExecutor.getTaskQueue(), empty());
  }

}
