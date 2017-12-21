package com.github.adeynack.executors;

import org.junit.Before;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class StateGuardTest {

  private Executor baseExecutor;
  private Service service;
  private Lock testLock;
  private AtomicInteger refusedLocks;

  static class ServiceState {
    public final OffsetDateTime lastUpdated;
    public final Map<String, Integer> usersCounters;

    ServiceState(OffsetDateTime lastUpdated, Map<String, Integer> usersCounters) {
      this.lastUpdated = lastUpdated;
      this.usersCounters = usersCounters;
    }
  }

  private <T> T withTestLock(String taskId, Supplier<T> task) throws RuntimeException {
    System.out.println(String.format("[%s] Waiting on testLock", taskId));
    final boolean gotLock = testLock.tryLock();
    try {
      if (!gotLock) {
        System.out.println(String.format("[%s] ERROR: Could not obtain lock", taskId));
        refusedLocks.incrementAndGet();
      } else {
        System.out.println(String.format("[%s] Lock obtained.", taskId));
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
      return task.get();
    } finally {
      if (gotLock) {
        System.out.println(String.format("[%s] Unlocking testLock", taskId));
        testLock.unlock();
      }
    }
  }

  class Service {

    private final StateGuard<ServiceState> state;

    Service(Executor serviceBaseExecutor) {
      state = new StateGuard<>(
          new ServiceState(
              OffsetDateTime.now(),
              new HashMap<>()),
          serviceBaseExecutor);
    }

    public CompletionStage<Void> addNewUser(String userName) {
      return state.mutate(st -> withTestLock(String.format("addNewUser %s", userName), () -> {
        final Map<String, Integer> newUserCounters = new HashMap<>(st.usersCounters);
        if (newUserCounters.containsKey(userName)) {
          throw new IllegalStateException("User is already listed.");
        } else {
          newUserCounters.put(userName, 0);
        }
        return new ServiceState(OffsetDateTime.now(), newUserCounters);
      }));
    }

  }

  @Before
  public void before() {
    refusedLocks = new AtomicInteger();
    testLock = new ReentrantLock();
    baseExecutor = Executors.newFixedThreadPool(4);
    service = new Service(baseExecutor);
  }

  // TODO : Write an actual test!

}
