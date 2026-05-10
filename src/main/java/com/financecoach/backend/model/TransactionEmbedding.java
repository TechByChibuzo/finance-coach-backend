package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Entity
@Table(name = "transaction_embeddings")
public class TransactionEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private String transactionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Setter
    @Column(name = "embedding", columnDefinition = "vector(1536)", nullable = false)
    @JdbcTypeCode(SqlTypes.VECTOR)
    private float[] embedding;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    @Column(name = "created_at")
    private final LocalDateTime createdAt = LocalDateTime.now();

    public TransactionEmbedding() {}

    public TransactionEmbedding(String transactionId, UUID userId,
                                float[] embedding, String content,
                                Map<String, Object> metadata) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.embedding = embedding;
        this.content = content;
        this.metadata = metadata;
    }

}