package com.github.adeynack.executors;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

public class SequencedExecutor implements Executor {

  private final Executor baseExecutor;
  private final ReentrantLock mainLock = new ReentrantLock();
  private final Queue<Runnable> taskQueue = new LinkedList<>();

  /**
   * indicates if a task already was submitted to the underlying {@link Executor}.
   */
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
