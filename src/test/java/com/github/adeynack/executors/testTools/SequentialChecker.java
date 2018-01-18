package com.github.adeynack.executors.testTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class SequentialChecker {

  private final AtomicInteger parallelTaskCounter = new AtomicInteger();
  private final Queue<TaskLogEntry> log = new ConcurrentLinkedQueue<>();

  public Map<String, TaskLogEntry> getLogByTaskName() {
    return log.stream().collect(Collectors.toMap(
        entry -> entry.taskId,
        entry -> entry));
  }

  public List<TaskLogEntry> getLog() {
    return new ArrayList<>(log);
  }

  public void check(String taskId, boolean mustBeSequential, Runnable r) {
    check(taskId, mustBeSequential, () -> {
      r.run();
      return 0;
    });
  }

  public <T> T check(String taskId, boolean mustBeSequencial, Supplier<T> r) {
    final int parallelTasksAfterThisOneStarted =
        mustBeSequencial ? parallelTaskCounter.incrementAndGet() : parallelTaskCounter.get();
    final String threadName = Thread.currentThread().getName();
    log.add(new TaskLogEntry(threadName, taskId, parallelTasksAfterThisOneStarted));
    System.out.println(String.format(
        "[%s] Task start on thread %s. Current parallel tasks: %s",
        taskId,
        threadName,
        parallelTasksAfterThisOneStarted));
    try {
      return r.get();
    } finally {
      final int parallelTasksAfter =
          mustBeSequencial ? parallelTaskCounter.decrementAndGet() : parallelTaskCounter.get();
      System.out.println(String.format("[%s] Task ends. Current parallel tasks: %s", taskId, parallelTasksAfter));
    }
  }

  public void assertMaxParallelTaskCount(final int expected) {
    final int maxParallelTasks = getLog().stream().mapToInt(entry -> entry.parallelTasks).max().orElse(0);
    assertEquals(
        String.format("Expected %s tasks to have been executed in parallel. Got %s", expected, maxParallelTasks),
        expected,
        maxParallelTasks);
  }

  @SuppressWarnings("WeakerAccess")
  public static class TaskLogEntry {
    public final String threadName;
    public final String taskId;
    public final int parallelTasks;

    public TaskLogEntry(String threadName, String taskId, int parallelTasks) {
      this.threadName = threadName;
      this.taskId = taskId;
      this.parallelTasks = parallelTasks;
    }

  }

}
