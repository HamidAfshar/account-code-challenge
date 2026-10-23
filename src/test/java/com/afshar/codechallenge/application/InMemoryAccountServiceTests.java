package com.afshar.codechallenge.application;

import com.afshar.codechallenge.domain.exception.AccountAlreadyExistException;
import com.afshar.codechallenge.domain.exception.AccountNotFoundException;
import com.afshar.codechallenge.domain.exception.InsufficientFundsException;
import com.afshar.codechallenge.domain.exception.TransactionDuplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class InMemoryAccountServiceTests {
    private InMemoryAccountService service;

    @BeforeEach
    void setUp() {
        service = new InMemoryAccountService();
    }

    @Test
    void creationReturnsAccountIdAndOperationsUpdateBalances() {
        var returned = service.createAccount("alice", 100);
        service.createAccount("bob", 0);
        assertThat(returned).isEqualTo("alice");
        service.credit("alice", 50, "credit");
        service.debit("alice", 10, "debit");
        service.transfer("alice", "bob", 40, "transfer");
        assertThat(service.getBalance("alice")).isEqualTo(100);
        assertThat(service.getBalance("bob")).isEqualTo(40);
        assertThatThrownBy(() -> service.createAccount("alice", 0))
                .isInstanceOf(AccountAlreadyExistException.class);
        assertThat(service.getBalance("alice")).isEqualTo(100);
    }

    @Test
    void successfulTransactionIdsAreUniqueAcrossOperationsAndAccounts() {
        service.createAccount("alice", 100);
        service.createAccount("bob", 100);
        service.credit("alice", 10, "same");
        assertThatThrownBy(() -> service.credit("bob", 10, "same"))
                .isInstanceOf(TransactionDuplicationException.class);
        assertThatThrownBy(() -> service.debit("alice", 10, "same"))
                .isInstanceOf(TransactionDuplicationException.class);
        assertThatThrownBy(() -> service.transfer("bob", "alice", 10, "same"))
                .isInstanceOf(TransactionDuplicationException.class);
        assertThat(service.getBalance("alice")).isEqualTo(110);
        assertThat(service.getBalance("bob")).isEqualTo(100);
    }

    @Test
    void failedOperationsCanBeRetriedWithoutPartialChanges() {
        service.createAccount("alice", 100);
        assertThatThrownBy(() -> service.transfer("alice", "bob", 10, "missing"))
                .isInstanceOf(AccountNotFoundException.class);
        service.createAccount("bob", 0);
        service.transfer("alice", "bob", 10, "missing");
        assertThatThrownBy(() -> service.debit("alice", 100, "funds"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThatThrownBy(() -> service.transfer("alice", "bob", 100, "funds"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(service.getBalance("alice")).isEqualTo(90);
        assertThat(service.getBalance("bob")).isEqualTo(10);
        service.debit("alice", 1, "funds");
        assertThatThrownBy(() -> service.transfer("alice", "alice", 1, "self"))
                .isInstanceOf(IllegalArgumentException.class);
        service.credit("alice", 1, "self");
        assertThatThrownBy(() -> service.credit("new", 1, "new-credit"))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> service.debit("new", 1, "new-debit"))
                .isInstanceOf(AccountNotFoundException.class);
        service.createAccount("new", 0);
        service.credit("new", 1, "new-credit");
        service.debit("new", 1, "new-debit");
    }

    @Test
    void overflowDoesNotChangeEitherBalanceOrConsumeTransactionId() {
        service.createAccount("alice", 100);
        service.createAccount("bob", Long.MAX_VALUE);
        assertThatThrownBy(() -> service.transfer("alice", "bob", 10, "transfer"))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> service.credit("bob", 1, "credit"))
                .isInstanceOf(ArithmeticException.class);
        assertThat(service.getBalance("alice")).isEqualTo(100);
        assertThat(service.getBalance("bob")).isEqualTo(Long.MAX_VALUE);
        service.debit("bob", 11, "space");
        service.transfer("alice", "bob", 10, "transfer");
        service.credit("bob", 1, "credit");
        assertThat(service.getBalance("bob")).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void concurrentCreationHasExactlyOneWinner() throws Exception {
        var results = ConcurrentTestSupport.concurrently(IntStream.range(0, 50)
                .<Callable<Boolean>>mapToObj(i -> () -> {
                    try {
                        service.createAccount("alice", i);
                        return true;
                    } catch (AccountAlreadyExistException exception) {
                        return false;
                    }
                }).toList());
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isOne();
    }

    @Test
    void concurrentMixedUpdatesDoNotLoseMoney() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 1000);
        ConcurrentTestSupport.concurrently(IntStream.range(0, 1000)
                .<Callable<Void>>mapToObj(i -> () -> {
                    switch (i % 4) {
                        case 0 -> service.credit("alice", 1, "tx-" + i);
                        case 1 -> service.debit("alice", 1, "tx-" + i);
                        case 2 -> service.transfer("alice", "bob", 1, "tx-" + i);
                        default -> service.transfer("bob", "alice", 1, "tx-" + i);
                    }
                    return null;
                }).toList());
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(1000);
    }

    @Test
    void competingDebitsCannotOverdraw() throws Exception {
        service.createAccount("alice", 1);
        var results = ConcurrentTestSupport.concurrently(IntStream.range(0, 50)
                .<Callable<Boolean>>mapToObj(i -> () -> {
                    try {
                        service.debit("alice", 1, "debit-" + i);
                        return true;
                    } catch (InsufficientFundsException exception) {
                        return false;
                    }
                }).toList());
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isOne();
        assertThat(service.getBalance("alice")).isZero();
    }

    @Test
    void concurrentConflictingTransactionsApplyExactlyOnce() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 1000);
        record Outcome(long aliceBalance, long bobBalance) {}
        var results = ConcurrentTestSupport.concurrently(IntStream.range(0, 100)
                .<Callable<Outcome>>mapToObj(i -> () -> {
                    try {
                        return switch (i % 3) {
                            case 0 -> {
                                service.credit("alice", 1, "same");
                                yield new Outcome(1001, 1000);
                            }
                            case 1 -> {
                                service.debit("bob", 1, "same");
                                yield new Outcome(1000, 999);
                            }
                            default -> {
                                service.transfer("alice", "bob", 1, "same");
                                yield new Outcome(999, 1001);
                            }
                        };
                    } catch (TransactionDuplicationException exception) {
                        return null;
                    }
                }).toList());
        var winners = results.stream().filter(java.util.Objects::nonNull).toList();
        assertThat(winners).hasSize(1);
        Outcome winner = winners.getFirst();
        assertThat(service.getBalance("alice")).isEqualTo(winner.aliceBalance());
        assertThat(service.getBalance("bob")).isEqualTo(winner.bobBalance());
    }

    @Test
    void lockedAccountDoesNotBlockIndependentAccounts() throws Exception {
        service.createAccount("alice", 100);
        service.createAccount("bob", 100);
        // Hold a real account lock to prove that no service-wide lock serializes operations.
        var accountsField = InMemoryAccountService.class.getDeclaredField("accounts");
        accountsField.setAccessible(true);
        var accounts = (java.util.Map<?, ?>) accountsField.get(service);
        var alice = accounts.get("alice");
        var lockField = alice.getClass().getDeclaredField("lock");
        lockField.setAccessible(true);
        var lock = (ReentrantReadWriteLock) lockField.get(alice);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        lock.writeLock().lock();
        try {
            // Aa and BB deliberately have the same String hash code.
            var blocked = executor.submit(() -> service.credit("alice", 1, "Aa"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(lock.hasQueuedThreads()).isTrue();
            executor.submit(() -> service.credit("bob", 1, "BB")).get(5, TimeUnit.SECONDS);
            assertThat(blocked.isDone()).isFalse();
            assertThat(service.getBalance("bob")).isEqualTo(101);
        } finally {
            lock.writeLock().unlock();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
