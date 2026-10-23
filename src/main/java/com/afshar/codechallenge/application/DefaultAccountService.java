package com.afshar.codechallenge.application;

import com.afshar.codechallenge.domain.AccountEntity;
import com.afshar.codechallenge.domain.TransactionEntity;
import com.afshar.codechallenge.domain.TransactionType;
import com.afshar.codechallenge.domain.exception.AccountAlreadyExistException;
import com.afshar.codechallenge.domain.exception.AccountNotFoundException;
import com.afshar.codechallenge.domain.exception.TransactionDuplicationException;
import com.afshar.codechallenge.infra.AccountRepository;
import com.afshar.codechallenge.infra.EntityCreator;
import com.afshar.codechallenge.infra.TransactionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Service
@Validated
@Profile("h2 | postgres")
public class DefaultAccountService implements AccountService {

    private final AccountRepository accountRepository;

    private final TransactionRepository transactionRepository;

    private final EntityCreator entityCreator;

    public DefaultAccountService(AccountRepository accountRepository,
                                 TransactionRepository transactionRepository,
                                 EntityCreator entityCreator) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entityCreator = entityCreator;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String createAccount(String accountId, long initAmount) {
        AccountEntity accountEntity = AccountEntity.builder()
                .accountId(accountId)
                .balance(initAmount)
                .build();
        try {
            entityCreator.persistAndFlush(accountEntity);
            return accountEntity.getAccountId();
        } catch (DataIntegrityViolationException exception) {
            if (isUniqueConstraintViolation(exception)) {
                throw new AccountAlreadyExistException("Account already exists", exception);
            }
            throw exception;
        }

    }

    @Override
    @Transactional(readOnly = true)
    public long getBalance(String accountId) {
        AccountEntity account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));
        return account.getBalance();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void credit(String accountId, long amount, String transactionId) {

        TransactionEntity transaction = TransactionEntity.builder()
                .amount(amount)
                .transactionId(transactionId)
                .destinationAccountId(accountId)
                .type(TransactionType.CREDIT)
                .build();
        checkAndReserveTransaction(transaction);

        AccountEntity account = lockAccount(accountId);

        account.credit(amount);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void debit(String accountId, long amount, String transactionId) {
        TransactionEntity transaction = TransactionEntity.builder()
                .amount(amount)
                .transactionId(transactionId)
                .sourceAccountId(accountId)
                .type(TransactionType.DEBIT)
                .build();

        checkAndReserveTransaction(transaction);

        AccountEntity account = lockAccount(accountId);

        account.debit(amount);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {

        if (sourceAccountId.equals(destinationAccountId))
            throw new IllegalArgumentException("Source and destination must be different");

        TransactionEntity transaction = TransactionEntity.builder()
                .amount(amount)
                .transactionId(transactionId)
                .sourceAccountId(sourceAccountId)
                .type(TransactionType.TRANSFER)
                .destinationAccountId(destinationAccountId)
                .build();

        checkAndReserveTransaction(transaction);

        Map<String, AccountEntity> accountEntityMap = Stream.of(
                        sourceAccountId,
                        destinationAccountId
                )
                .sorted()
                .map(this::lockAccount)
                .collect(Collectors.toMap(AccountEntity::getAccountId, Function.identity()));


        AccountEntity source = accountEntityMap.get(sourceAccountId);
        AccountEntity destination = accountEntityMap.get(destinationAccountId);

        source.debit(amount);
        destination.credit(amount);
    }

    private void checkAndReserveTransaction(TransactionEntity transaction) {
        if (transactionRepository.existsById(transaction.getTransactionId()))
            throw new TransactionDuplicationException(transaction.getTransactionId());

        try {
            entityCreator.persistAndFlush(transaction);
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueConstraintViolation(ex)) {
                throw new TransactionDuplicationException(transaction.getTransactionId(), ex);
            }
            throw ex;
        }
    }

    private boolean isUniqueConstraintViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE) {
                return true;
            }
        }
        return false;
    }


    private AccountEntity lockAccount(String accountId) {
        return accountRepository.findLockedByAccountId(accountId)
                .orElseThrow(() ->
                        new AccountNotFoundException("Accoutn not found : " + accountId));
    }

}
