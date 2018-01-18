package com.github.adeynack.executors;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * @param <S> type of the state to protect.
 */
@SuppressWarnings("WeakerAccess") // Library class
public class StateGuard<S> {

  private volatile S state;
  private final SequencedExecutor sequencedExecutor;

  public StateGuard(S initialState, Executor baseExecutor) {
    this.state = initialState;
    this.sequencedExecutor = new SequencedExecutor(baseExecutor);
  }

  /**
   * Mutate the state, without any return value.
   * <p>
   * During the execution of `stateMutation`, the state context is secure. It is executed
   * using an internal {@link SequencedExecutor}. If the state is mutable (which should be
   * avoided as much as possible), it is safe to mutate it inside of `stateReader`. However,
   * the ideal way to use this is to keep the state immutable and return the new immutable
   * version of the state as the return value of `stateMutation`.
   *
   * @param stateMutation a function receiving the actual state and returning the
   *                      new, mutated, state.
   * @return a {@link CompletionStage} indicating when the change is completed.
   */
  public CompletionStage<Void> change(Function<S, S> stateMutation) {
    return CompletableFuture.runAsync(
        () -> {
          S stateBefore = state;
          state = stateMutation.apply(stateBefore);
        },
        sequencedExecutor);
  }

  /**
   * Read a value from the state.
   * <p>
   * THREADING WARNING: This is not executed in an exclusive way. The state received in
   * `stateReader` is not protected. DO NOT MUTATE IT. This is directly executed on the
   * caller's thread. The ideal way of using this is as a fast way to extract information
   * from the state at a given time.
   *
   * @param stateReader a function receiving the actual state and returning a value from it.
   * @param <T>         the type of the value to be returned.
   * @return the value returned by `stateReader`.
   */
  public <T> T immediateRead(Function<S, T> stateReader) {
    S actualState = state;
    return stateReader.apply(actualState);
  }

  /**
   * Mutate the state and return a value from it.
   * <p>
   * During the execution of `stateMutation`, the state context is secure. It is executed
   * using an internal {@link SequencedExecutor}. If the state is mutable (which should be
   * avoided as much as possible), it is safe to mutate it inside of `stateReader`. However,
   * the ideal way to use this is to keep the state immutable and return the new immutable
   * version of the state as part of the return value of `stateMutation`.
   *
   * @param stateMutation a function receiving the actual state and returning a {@link StateDecision}
   *                      with both the new state and the value to return to the caller. If it
   *                      returns `null`, then the state stays the same and `null` is returned
   *                      to the caller.
   * @param <T>           the type of the value to be returned.
   * @return a {@link CompletionStage} providing, when ready, the read value.
   */
  public <T> CompletionStage<T> readAndChange(Function<S, StateDecision<S, T>> stateMutation) {
    return CompletableFuture.supplyAsync(
        () -> {
          S actualState = state;
          StateDecision<S, T> decision = stateMutation.apply(actualState);
          if (decision == null) {
            return null;
          } else {
            state = decision.getUpdatedState();
            return decision.getReturnedValue();
          }
        },
        sequencedExecutor);
  }

}
