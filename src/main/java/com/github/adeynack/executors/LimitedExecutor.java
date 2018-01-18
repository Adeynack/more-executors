package com.github.adeynack.executors;

import org.jetbrains.annotations.NotNull;

import javax.annotation.concurrent.GuardedBy;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("WeakerAccess") // Library class
public class LimitedExecutor implements Executor {

  private final ReentrantLock mainLock = new ReentrantLock();

  private final Executor baseExecutor;

  private final int parallelTaskLimit;

  @GuardedBy("mainLock")
  private final Queue<Runnable> taskQueue = new ArrayDeque<>();

  /**
   * indicates the number of tasks already submitted to the underlying {@link Executor}.
   */
  @GuardedBy("mainLock")
  private volatile int tasksSubmitted = 0;

  public LimitedExecutor(Executor baseExecutor, int parallelTaskLimit) {
    this.baseExecutor = baseExecutor;
    this.parallelTaskLimit = parallelTaskLimit;
  }

  @Override
  public void execute(@NotNull Runnable command) {
    mainLock.lock();
    try {
      if (tasksSubmitted >= parallelTaskLimit) {
        taskQueue.add(command);
      } else {
        final int actualTasksSubmitted = tasksSubmitted;
        tasksSubmitted = actualTasksSubmitted + 1;
        baseExecutor.execute(createTaskWrapper(command));
      }
    } finally {
      mainLock.unlock();
    }
  }

  private Runnable createTaskWrapper(Runnable command) {
    return () -> {
      try {
        command.run();
      } finally {
        mainLock.lock();
        try {
          Runnable nextTask = taskQueue.poll();
          if (nextTask == null) {
            final int actualTasksSubmitted = tasksSubmitted;
            tasksSubmitted = actualTasksSubmitted - 1;
          } else {
            baseExecutor.execute(createTaskWrapper(nextTask));
          }
        } finally {
          mainLock.unlock();
        }
      }
    };
  }

  /**
   * @return the current count of tasks submitted to the underlying {@link Executor}.
   */
  public int getTasksSubmitted() {
    int actualTasksSubmitted;
    mainLock.lock();
    try {
      actualTasksSubmitted = tasksSubmitted;
    } finally {
      mainLock.unlock();
    }
    return actualTasksSubmitted;
  }

  /**
   * @return a copy of the current queue of tasks.
   */
  public Queue<Runnable> getTaskQueue() {
    return new ArrayDeque<>(this.taskQueue);
  }

}
