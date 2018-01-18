package com.github.adeynack.executors;

import com.github.adeynack.executors.testTools.SequentialChecker;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class StateGuardTest {

  private SequentialChecker sequentialChecker;
  private Service service;

  static class ServiceState {
    public final Map<String, Integer> usersCounters;

    ServiceState(final Map<String, Integer> usersCounters) {
      this.usersCounters = usersCounters;
    }
  }

  class Service {

    private final StateGuard<ServiceState> state;

    Service(Executor serviceBaseExecutor, Map<String, Integer> initialState) {
      state = new StateGuard<>(
          new ServiceState(initialState),
          serviceBaseExecutor);
    }

    CompletionStage<Void> addNewUser(final String userName) {
      return state.change(st -> sequentialChecker.check(
          String.format("addNewUser %s", userName),
          true,
          () -> {
            final Map<String, Integer> updatedUserCounters = new HashMap<>(st.usersCounters);
            if (updatedUserCounters.containsKey(userName)) {
              throw new IllegalStateException("User is already listed.");
            } else {
              updatedUserCounters.put(userName, 0);
            }
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            return new ServiceState(updatedUserCounters);
          }));
    }

    Set<String> getUserNames(int callSequentialNumber) {
      return sequentialChecker.check(
          String.format("getUserNames %s", callSequentialNumber),
          false,
          () -> state.immediateRead(
              st -> {
                try {
                  Thread.sleep(100);
                  if (callSequentialNumber < 0) {
                    throw new IllegalStateException("You asked for it.");
                  }
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                return st.usersCounters.keySet();
              }
          ));
    }

    CompletionStage<Integer> incrementAndGet(final String username) {
      return state.readAndChange(st -> sequentialChecker.check(
          String.format("incrementAndGet %s", username),
          true,
          () -> {
            final Map<String, Integer> usersCounters = st.usersCounters;
            if (!usersCounters.containsKey(username)) {
              throw new NullPointerException(String.format("User \"%s\" is not listed.", username));
            }
            int actualValue = usersCounters.get(username);
            int updatedValue = actualValue + 1;
            final Map<String, Integer> updatedUserCounters = new HashMap<>(usersCounters);
            updatedUserCounters.put(username, updatedValue);
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            return new StateDecision<>(
                new ServiceState(updatedUserCounters),
                updatedValue);
          }));
    }

  }

  @Before
  public void before() {
    Executor baseExecutor = Executors.newFixedThreadPool(
        4,
        new ThreadFactoryBuilder().setNameFormat("state-guard-test-pool-%d").build());
    sequentialChecker = new SequentialChecker();
    Map<String, Integer> serviceState = new HashMap<>();
    serviceState.put("Klipitar", 33);
    serviceState.put("Borey Papatwika", 42);
    service = new Service(baseExecutor, serviceState);
  }

  @Test
  public void methodChangePerformsOperationsOnTheSequencedExecutor() throws Exception {

    final CompletableFuture<?>[] futures =
        IntStream.range(0, 12)
                 .mapToObj(i -> service.addNewUser(String.format("New User %s", i)).toCompletableFuture())
                 .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(futures).get(1, TimeUnit.MINUTES);

    sequentialChecker.assertMaxParallelTaskCount(1);

    final Map<String, SequentialChecker.TaskLogEntry> log = sequentialChecker.getLogByTaskName();
    assertThat(log.size(), equalTo(12));
    IntStream.range(0, 12).forEach(i -> assertThat(
        log.get(String.format("addNewUser New User %s", i)).threadName,
        startsWith("state-guard-test-pool-")));
  }

  @Test
  public void methodChangeFailsGracefully() throws Exception {
    final CompletableFuture<Void> future = service.addNewUser("Klipitar").toCompletableFuture();
    try {
      future.get();
      fail("Expecting the future to throw an exception");
    } catch (ExecutionException executionException) {
      final Throwable cause = executionException.getCause();
      assertThat(cause, instanceOf(IllegalStateException.class));
      assertEquals("User is already listed.", cause.getMessage());
    }
  }

  @Test
  public void methodImmediateGetPerformsOperationsOnTheSameThreadAsTheCaller() {

    final List<Set<String>> values =
        IntStream.range(0, 12)
                 .mapToObj(i -> service.getUserNames(i))
                 .collect(Collectors.toList());

    sequentialChecker.assertMaxParallelTaskCount(0);

    final String currentThreadName = Thread.currentThread().getName();
    final Map<String, SequentialChecker.TaskLogEntry> log = sequentialChecker.getLogByTaskName();
    assertThat(log.size(), equalTo(12));

    IntStream.range(0, 12).forEach(i -> assertThat(
        log.get(String.format("getUserNames %s", i)).threadName,
        equalTo(currentThreadName)));

    final Set<String> expectedNames = new HashSet<>();
    expectedNames.add("Klipitar");
    expectedNames.add("Borey Papatwika");

    IntStream.range(0, 12).forEach(i -> assertThat(
        values.get(0),
        equalTo(expectedNames)));

  }

  @Test
  public void methodImmediateGetThrowsExceptionOnError() throws Exception {
    try {
      service.getUserNames(-1);
      fail("Expecting operation to throw an exception");
    } catch (Exception e) {
      assertThat(e, instanceOf(IllegalStateException.class));
      assertEquals("You asked for it.", e.getMessage());
    }
  }

  @Test
  public void methodReadAndChangePerformsOperationsOnTheSequencedExecutor() throws Exception {

    final List<CompletableFuture<Integer>> futures =
        IntStream.range(0, 12)
                 .mapToObj(i -> service.incrementAndGet(i < 6 ? "Klipitar" : "Borey Papatwika").toCompletableFuture())
                 .collect(Collectors.toList());

    CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).get(1, TimeUnit.MINUTES);

    sequentialChecker.assertMaxParallelTaskCount(1);

    final List<SequentialChecker.TaskLogEntry> log = sequentialChecker.getLog();

    assertThat(log.size(), equalTo(12));

    assertThat(log.stream().filter(l -> l.taskId.equals("incrementAndGet Klipitar")).count(),
               equalTo(6L));
    assertThat(log.stream().filter(l -> l.taskId.equals("incrementAndGet Borey Papatwika")).count(),
               equalTo(6L));

    IntStream.range(0, 12).forEach(i -> assertThat(
        log.get(i).threadName,
        startsWith("state-guard-test-pool-")));

    final List<Integer> valuesForKlipitar =
        IntStream.range(0, 6)
                 .mapToObj(i -> {
                   try {
                     return futures.get(i).get(1, TimeUnit.MINUTES);
                   } catch (InterruptedException | ExecutionException | TimeoutException e) {
                     throw new RuntimeException(e);
                   }
                 })
                 .collect(Collectors.toList());
    assertThat(valuesForKlipitar, containsInAnyOrder(34, 35, 36, 37, 38, 39));

    final List<Integer> valuesForPapatwika =
        IntStream.range(6, 12)
                 .mapToObj(i -> {
                   try {
                     return futures.get(i).get(1, TimeUnit.MINUTES);
                   } catch (InterruptedException | ExecutionException | TimeoutException e) {
                     throw new RuntimeException(e);
                   }
                 })
                 .collect(Collectors.toList());
    assertThat(valuesForPapatwika, containsInAnyOrder(43, 44, 45, 46, 47, 48));
  }

  @Test
  public void methodReadAndChangeFailsGracefully() throws Exception {
    final CompletableFuture<Integer> future = service.incrementAndGet("I do not exist").toCompletableFuture();
    try {
      future.get();
      fail("Expecting the future to throw an exception");
    } catch (ExecutionException executionException) {
      final Throwable cause = executionException.getCause();
      assertThat(cause, instanceOf(NullPointerException.class));
      assertEquals("User \"I do not exist\" is not listed.", cause.getMessage());
    }
  }

}
