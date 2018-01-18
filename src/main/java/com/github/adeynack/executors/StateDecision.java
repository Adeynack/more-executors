package com.github.adeynack.executors;

public class StateDecision<S, T> {

  private final S updatedState;
  private final T returnedValue;

  public StateDecision(S updatedState, T returnedValue) {
    this.updatedState = updatedState;
    this.returnedValue = returnedValue;
  }

  public S getUpdatedState() {
    return updatedState;
  }

  public T getReturnedValue() {
    return returnedValue;
  }
}
