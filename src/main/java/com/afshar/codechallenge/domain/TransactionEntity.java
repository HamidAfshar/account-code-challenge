package com.afshar.codechallenge.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "Transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionEntity {

    @Id
    private String transactionId;

    @Enumerated(EnumType.STRING)
    private TransactionType type;

    private String sourceAccountId;

    private String destinationAccountId;

    private long amount;
}