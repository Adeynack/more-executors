package com.github.adeynack.executors;

import org.jetbrains.annotations.NotNull;

import javax.annotation.concurrent.GuardedBy;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("WeakerAccess") // Library class
public class SequencedExecutor implements Executor {

  private final ReentrantLock mainLock = new ReentrantLock();

  private final Executor baseExecutor;

  @GuardedBy("mainLock")
  private final Queue<Runnable> taskQueue = new ArrayDeque<>();

  /**
   * indicates if a task already was submitted to the underlying {@link Executor}.
   */
  @GuardedBy("mainLock")
  private volatile Boolean taskSubmitted = false;

  public SequencedExecutor(Executor baseExecutor) {
    this.baseExecutor = baseExecutor;
  }

  @Override
  public void execute(@NotNull Runnable command) {
    mainLock.lock();
    try {
      if (taskSubmitted) {
        taskQueue.add(command);
      } else {
        taskSubmitted = true;
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
            taskSubmitted = false;
          } else {
            baseExecutor.execute(createTaskWrapper(nextTask));
          }
        } finally {
          mainLock.unlock();
        }
      }
    };
  }

}
