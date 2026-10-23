package com.afshar.codechallenge;

import com.afshar.codechallenge.application.AccountService;
import com.afshar.codechallenge.application.DefaultAccountService;
import com.afshar.codechallenge.application.InMemoryAccountService;
import com.afshar.codechallenge.infra.AccountRepository;
import com.afshar.codechallenge.infra.EntityCreator;
import com.afshar.codechallenge.infra.TransactionRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;

class AccountStorageProfileTests {
    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgres", "inMemory"})
    void profileSelectsOneServiceAndValidatesItsInputs(String profile) {
        // PostgreSQL bean wiring is checked with H2; a live PostgreSQL server is not required here.
        try (var context = new SpringApplicationBuilder(CodeChallengeApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .run("--spring.datasource.url=jdbc:h2:mem:profile-" + profile,
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa", "--spring.datasource.password=",
                        "--spring.jpa.show-sql=false", "--spring.main.banner-mode=off")) {
            assertThat(context.getBeansOfType(AccountService.class)).hasSize(1);
            AccountService service = context.getBean(AccountService.class);
            if (profile.equals("inMemory")) {
                assertThat(service).isInstanceOf(InMemoryAccountService.class);
                assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
                assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
                assertThat(context.getBeansOfType(AccountRepository.class)).isEmpty();
                assertThat(context.getBeansOfType(TransactionRepository.class)).isEmpty();
                assertThat(context.getBeansOfType(EntityCreator.class)).isEmpty();
                assertThat(context.getBeansOfType(PlatformTransactionManager.class)).isEmpty();
            } else {
                assertThat(service).isInstanceOf(DefaultAccountService.class);
                assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                assertThat(context.getBeansOfType(AccountRepository.class)).hasSize(1);
                assertThat(context.getBeansOfType(EntityCreator.class)).hasSize(1);
            }
            assertThat(service.createAccount("alice", 100)).isEqualTo("alice");
            service.createAccount("bob", 0);
            service.credit("alice", 10, "credit");
            service.debit("alice", 5, "debit");
            service.transfer("alice", "bob", 5, "transfer");
            assertThat(service.getBalance("alice")).isEqualTo(100);
            assertThat(service.getBalance("bob")).isEqualTo(5);
            assertThatThrownBy(() -> service.createAccount("negative", -1))
                    .isInstanceOf(ConstraintViolationException.class);
            for (String invalid : new String[]{null, "", " ", "x".repeat(256)}) {
                assertThatThrownBy(() -> service.createAccount(invalid, 0))
                        .isInstanceOf(ConstraintViolationException.class);
                assertThatThrownBy(() -> service.getBalance(invalid))
                        .isInstanceOf(ConstraintViolationException.class);
                assertThatThrownBy(() -> service.credit("alice", 1, invalid))
                        .isInstanceOf(ConstraintViolationException.class);
                assertThatThrownBy(() -> service.debit(invalid, 1, "invalid"))
                        .isInstanceOf(ConstraintViolationException.class);
                assertThatThrownBy(() -> service.transfer("alice", invalid, 1, "invalid"))
                        .isInstanceOf(ConstraintViolationException.class);
            }
            for (long invalid : new long[]{0, -1}) {
                assertThatThrownBy(() -> service.credit("alice", invalid, "invalid"))
                        .isInstanceOf(ConstraintViolationException.class);
                assertThatThrownBy(() -> service.debit("alice", invalid, "invalid"))
                        .isInstanceOf(ConstraintViolationException.class);
                assertThatThrownBy(() -> service.transfer("alice", "bob", invalid, "invalid"))
                        .isInstanceOf(ConstraintViolationException.class);
            }
            service.credit("alice", 1, "invalid");
            assertThat(service.getBalance("alice")).isEqualTo(101);
        }
    }
}
