package com.github.adeynack.executors;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * @param <T> type of the state to protect.
 */
public class StateGuard<T> {

  private volatile T state;
  private final SequencedExecutor sequencedExecutor;

  public StateGuard(T initialState, Executor baseExecutor) {
    this.state = initialState;
    this.sequencedExecutor = new SequencedExecutor(baseExecutor);
  }

  public CompletionStage<Void> mutate(Function<T, T> stateMutation) {
    return CompletableFuture.runAsync(
        () -> {
          T stateBefore = state;
          state = stateMutation.apply(stateBefore);
        },
        sequencedExecutor);
  }

}
