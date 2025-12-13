package com.financecoach.userservice.repository;

import com.financecoach.userservice.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByToken(String token);

    Optional<PasswordResetToken> findByUserIdAndUsedFalseAndExpiryDateAfter(
            UUID userId,
            LocalDateTime now
    );

    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiryDate < :now")
    void deleteExpiredTokens(@Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    // Count recent tokens for rate limiting
    @Query("SELECT COUNT(t) FROM PasswordResetToken t " +
            "WHERE t.userId = :userId AND t.createdAt > :since")
    long countRecentTokensByUserId(@Param("userId") UUID userId,
                                   @Param("since") LocalDateTime since);

    @Modifying
    @Query("UPDATE PasswordResetToken t " +
            "SET t.used = true " +
            "WHERE t.userId = :userId AND t.used = false AND t.expiryDate > :now")
    void markActiveTokensAsUsed(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

}
