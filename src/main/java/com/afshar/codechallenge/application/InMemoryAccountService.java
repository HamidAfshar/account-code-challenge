package com.afshar.codechallenge.application;

import com.afshar.codechallenge.domain.AccountEntity;
import com.afshar.codechallenge.domain.exception.AccountAlreadyExistException;
import com.afshar.codechallenge.domain.exception.AccountNotFoundException;
import com.afshar.codechallenge.domain.exception.TransactionDuplicationException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
@Validated
@Profile("inMemory")
public class InMemoryAccountService implements AccountService {

    private final ConcurrentHashMap<String, AccountState> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> completedTransactions = new ConcurrentHashMap<>();

    @Override
    public String createAccount(String accountId, long initAmount) {
        AccountEntity entity = AccountEntity.builder().accountId(accountId).balance(initAmount).build();
        AccountState accountState = new AccountState(entity);
        if (accounts.putIfAbsent(accountId, accountState) != null) {
            throw new AccountAlreadyExistException("Account already exists");
        }
        return accountId;
    }

    @Override
    public long getBalance(String accountId) {
        AccountState accountState = getAccount(accountId);
        accountState.lock.readLock().lock();
        try {
            return accountState.account.getBalance();
        } finally {
            accountState.lock.readLock().unlock();
        }
    }

    @Override
    public void credit(String accountId, long amount, String transactionId) {
        AccountState account = getAccount(accountId);
        account.lock.writeLock().lock();
        try {
            executeOnce(transactionId, () -> account.account.credit(amount));
        } finally {
            account.lock.writeLock().unlock();
        }
    }

    @Override
    public void debit(String accountId, long amount, String transactionId) {
        AccountState accountState = getAccount(accountId);
        accountState.lock.writeLock().lock();
        try {
            executeOnce(transactionId, () -> accountState.account.debit(amount));
        } finally {
            accountState.lock.writeLock().unlock();
        }
    }

    @Override
    public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
        if (sourceAccountId.equals(destinationAccountId))
            throw new IllegalArgumentException("Source and destination must be different");

        AccountState source = getAccount(sourceAccountId);
        AccountState destination = getAccount(destinationAccountId);
        AccountState first = sourceAccountId.compareTo(destinationAccountId) < 0 ? source : destination;
        AccountState second = first == source ? destination : source;

        first.lock.writeLock().lock();
        try {
            second.lock.writeLock().lock();
            try {
                executeOnce(transactionId, () -> {
                    AccountEntity sourceCopy = source.account.snapshot();
                    AccountEntity destinationCopy = destination.account.snapshot();
                    sourceCopy.debit(amount);
                    destinationCopy.credit(amount);
                    source.account = sourceCopy;
                    destination.account = destinationCopy;
                });
            } finally {
                second.lock.writeLock().unlock();
            }
        } finally {
            first.lock.writeLock().unlock();
        }
    }

    private void executeOnce(String transactionId, Runnable operation) {
        completedTransactions.compute(transactionId, (id, completed) -> {
            if (completed != null) {
                throw new TransactionDuplicationException(id);
            }
            operation.run();
            return Boolean.TRUE;
        });
    }

    private AccountState getAccount(String accountId) {
        AccountState account = accounts.get(accountId);
        if (account == null) {
            throw new AccountNotFoundException("Account not found: " + accountId);
        }
        return account;
    }

    private static final class AccountState {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private AccountEntity account;

        private AccountState(AccountEntity account) {
            this.account = account;
        }
    }
}
