package com.afshar.codechallenge.infra;

import com.afshar.codechallenge.domain.AccountEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, String> {

    Optional<AccountEntity> findByAccountId(String accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountEntity> findLockedByAccountId(String accountId);
}
