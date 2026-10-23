package com.afshar.codechallenge.application;

import com.afshar.codechallenge.support.AccountIntegrationTestSupport;
import com.afshar.codechallenge.domain.TransactionType;
import com.afshar.codechallenge.domain.exception.AccountAlreadyExistException;
import com.afshar.codechallenge.domain.exception.InsufficientFundsException;
import com.afshar.codechallenge.domain.exception.TransactionDuplicationException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.transaction.support.TransactionTemplate;

import static com.afshar.codechallenge.application.ConcurrentTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class AccountConcurrencyTests extends AccountIntegrationTestSupport {

    @Test
    void concurrentCreationHasExactlyOneWinner() throws Exception {
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                try {
                    service.createAccount("alice", 1000);
                    return true;
                } catch (AccountAlreadyExistException expected) {
                    return false;
                }
            });
        }
        var results = concurrently(tasks);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(accounts.count()).isOne();
        assertThat(service.getBalance("alice")).isEqualTo(1000);
    }

    @Test
    void thousandConcurrentMixedOperationsDoNotLoseUpdates() throws Exception {
        service.createAccount("alice", 100_000);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String id = Integer.toString(i);
            tasks.add(() -> {
                service.credit("alice", 3, "deposit-" + id);
                return null;
            });
            tasks.add(() -> {
                service.debit("alice", 2, "withdrawal-" + id);
                return null;
            });
        }

        concurrently(tasks);

        assertThat(service.getBalance("alice")).isEqualTo(100_500);
        assertThat(transactions.count()).isEqualTo(1000);
    }

    @Test
    void competingDebitsHaveExactlyOneWinner() throws Exception {
        service.createAccount("alice", 1000);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String id = "withdrawal" + i;
            tasks.add(() -> {
                try {
                    service.debit("alice", 700, id);
                    return true;
                } catch (InsufficientFundsException expected) {
                    return false;
                }
            });
        }
        var results = concurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(service.getBalance("alice")).isEqualTo(300);
        assertThat(transactions.count()).isOne();
    }

    @Test
    void oppositeTransfersCompleteAndConserveMoney() throws Exception {
        service.createAccount("alice", 10_000);
        service.createAccount("bob", 10_000);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String id = Integer.toString(i);
            tasks.add(() -> {
                service.transfer("alice", "bob", 3, "alice-to-bob-" + id);
                return null;
            });
            tasks.add(() -> {
                service.transfer("bob", "alice", 2, "bob-to-alice-" + id);
                return null;
            });
        }

        concurrently(tasks);

        assertThat(service.getBalance("alice")).isEqualTo(9900);
        assertThat(service.getBalance("bob")).isEqualTo(10_100);
        assertThat(service.getBalance("alice") + service.getBalance("bob")).isEqualTo(20_000);
        assertThat(transactions.count()).isEqualTo(200);
    }

    @Test
    void lockedAccountDoesNotBlockAnIndependentAccount() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                accounts.findLockedByAccountId("alice").orElseThrow();
                locked.countDown();
                await(release);
            }));
            await(locked);
            executor.submit(() -> service.credit("bob", 100, "independent")).get(5, TimeUnit.SECONDS);
            assertThat(service.getBalance("bob")).isEqualTo(600);
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void simultaneousCreditRetriesApplyOnlyOnce() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        List<Callable<Boolean>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(() -> {
                try {
                    service.credit("alice", 100, "shared-request");
                    return true;
                } catch (TransactionDuplicationException expected) {
                    return false;
                }
            });
        }

        var results = concurrently(requests);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(results).filteredOn(result -> !result).hasSize(19);
        assertThat(service.getBalance("alice")).isEqualTo(1100);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isOne();
        var recorded = transactions.findById("shared-request").orElseThrow();
        assertThat(recorded.getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(recorded.getAmount()).isEqualTo(100);
    }

    @Test
    void aDelayedCreditRetryIsRejectedAfterAnotherRequestCommits() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        // Pause the first request after its existence check until the other request commits.
        MethodInterceptor delayFirstCheck = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("existsById")
                    && first.compareAndSet(true, false)) {
                checked.countDown();
                await(release);
            }
            return result;
        };
        Advised repositoryProxy = (Advised) transactions;
        repositoryProxy.addAdvice(0, delayFirstCheck);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> delayed = executor.submit(() -> service.credit("alice", 100, "shared-request"));
            await(checked);
            service.credit("alice", 100, "shared-request");
            release.countDown();
            assertThatThrownBy(() -> delayed.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TransactionDuplicationException.class);
            assertThat(service.getBalance("alice")).isEqualTo(1100);
            assertThat(service.getBalance("bob")).isEqualTo(500);
            assertThat(transactions.count()).isOne();
        } finally {
            release.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            } finally {
                repositoryProxy.removeAdvice(delayFirstCheck);
            }
        }
    }

    @Test
    void simultaneousDebitRetriesApplyOnlyOnce() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        List<Callable<Boolean>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(() -> {
                try {
                    service.debit("alice", 100, "shared-request");
                    return true;
                } catch (TransactionDuplicationException expected) {
                    return false;
                }
            });
        }

        var results = concurrently(requests);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(results).filteredOn(result -> !result).hasSize(19);
        assertThat(service.getBalance("alice")).isEqualTo(900);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isOne();
        var recorded = transactions.findById("shared-request").orElseThrow();
        assertThat(recorded.getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(recorded.getAmount()).isEqualTo(100);
    }

    @Test
    void aDelayedDebitRetryIsRejectedAfterAnotherRequestCommits() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        // Pause the first request after its existence check until the other request commits.
        MethodInterceptor delayFirstCheck = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("existsById")
                    && first.compareAndSet(true, false)) {
                checked.countDown();
                await(release);
            }
            return result;
        };
        Advised repositoryProxy = (Advised) transactions;
        repositoryProxy.addAdvice(0, delayFirstCheck);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> delayed = executor.submit(() -> service.debit("alice", 100, "shared-request"));
            await(checked);
            service.debit("alice", 100, "shared-request");
            release.countDown();
            assertThatThrownBy(() -> delayed.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TransactionDuplicationException.class);
            assertThat(service.getBalance("alice")).isEqualTo(900);
            assertThat(service.getBalance("bob")).isEqualTo(500);
            assertThat(transactions.count()).isOne();
        } finally {
            release.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            } finally {
                repositoryProxy.removeAdvice(delayFirstCheck);
            }
        }
    }

    @Test
    void simultaneousTransferRetriesApplyOnlyOnce() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        List<Callable<Boolean>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(() -> {
                try {
                    service.transfer("alice", "bob", 100, "shared-request");
                    return true;
                } catch (TransactionDuplicationException expected) {
                    return false;
                }
            });
        }

        var results = concurrently(requests);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(results).filteredOn(result -> !result).hasSize(19);
        assertThat(service.getBalance("alice")).isEqualTo(900);
        assertThat(service.getBalance("bob")).isEqualTo(600);
        assertThat(transactions.count()).isOne();
        var recorded = transactions.findById("shared-request").orElseThrow();
        assertThat(recorded.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(recorded.getAmount()).isEqualTo(100);
    }

    @Test
    void aDelayedTransferRetryIsRejectedAfterAnotherRequestCommits() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        // Pause the first request after its existence check until the other request commits.
        MethodInterceptor delayFirstCheck = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("existsById")
                    && first.compareAndSet(true, false)) {
                checked.countDown();
                await(release);
            }
            return result;
        };
        Advised repositoryProxy = (Advised) transactions;
        repositoryProxy.addAdvice(0, delayFirstCheck);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> delayed = executor.submit(() -> service.transfer("alice", "bob", 100, "shared-request"));
            await(checked);
            service.transfer("alice", "bob", 100, "shared-request");
            release.countDown();
            assertThatThrownBy(() -> delayed.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TransactionDuplicationException.class);
            assertThat(service.getBalance("alice")).isEqualTo(900);
            assertThat(service.getBalance("bob")).isEqualTo(600);
            assertThat(transactions.count()).isOne();
        } finally {
            release.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            } finally {
                repositoryProxy.removeAdvice(delayFirstCheck);
            }
        }
    }

    @Test
    void conflictingRequestsWithTheSameIdPreserveOnlyTheWinningRequest() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        record Outcome(TransactionType type, long amount, String source, String destination,
                       long aliceBalance, long bobBalance) {}
        List<Callable<Outcome>> requests = List.of(
                () -> {
                    try {
                        service.credit("alice", 100, "shared-request");
                        return new Outcome(TransactionType.CREDIT, 100, null, "alice", 1100, 500);
                    } catch (TransactionDuplicationException expected) {
                        return null;
                    }
                },
                () -> {
                    try {
                        service.credit("alice", 250, "shared-request");
                        return new Outcome(TransactionType.CREDIT, 250, null, "alice", 1250, 500);
                    } catch (TransactionDuplicationException expected) {
                        return null;
                    }
                },
                () -> {
                    try {
                        service.debit("alice", 70, "shared-request");
                        return new Outcome(TransactionType.DEBIT, 70, "alice", null, 930, 500);
                    } catch (TransactionDuplicationException expected) {
                        return null;
                    }
                },
                () -> {
                    try {
                        service.transfer("alice", "bob", 150, "shared-request");
                        return new Outcome(TransactionType.TRANSFER, 150, "alice", "bob", 850, 650);
                    } catch (TransactionDuplicationException expected) {
                        return null;
                    }
                });

        var winners = concurrently(requests).stream().filter(java.util.Objects::nonNull).toList();

        assertThat(winners).hasSize(1);
        Outcome winner = winners.getFirst();
        assertThat(transactions.count()).isOne();
        var recorded = transactions.findById("shared-request").orElseThrow();
        assertThat(recorded.getType()).isEqualTo(winner.type());
        assertThat(recorded.getAmount()).isEqualTo(winner.amount());
        assertThat(recorded.getSourceAccountId()).isEqualTo(winner.source());
        assertThat(recorded.getDestinationAccountId()).isEqualTo(winner.destination());
        assertThat(service.getBalance("alice")).isEqualTo(winner.aliceBalance());
        assertThat(service.getBalance("bob")).isEqualTo(winner.bobBalance());
    }
}

