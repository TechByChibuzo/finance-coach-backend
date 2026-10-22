package com.financecoach.backend.model;

import com.financecoach.backend.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A Plaid Item: one login at one institution. It owns the access token, and
 * every BankAccount linked through that login belongs to it. Plaid APIs that take
 * an access token (transactions, holdings) return data for all of the item's accounts.
 */
@Getter
@Setter
@Entity
@Table(name = "plaid_items", indexes = {
        @Index(name = "idx_plaid_items_user", columnList = "user_id")
})
public class PlaidItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plaid_item_id", nullable = false, unique = true)
    private String plaidItemId;

    @Column(name = "access_token", nullable = false)
    @Convert(converter = EncryptedStringConverter.class)
    private String accessToken;

    @Column(name = "institution_id")
    private String institutionId;

    @Column(name = "institution_name")
    private String institutionName;

    // Position in the item's /transactions/sync stream; null until the first sync
    @Column(name = "transactions_cursor", columnDefinition = "TEXT")
    private String transactionsCursor;

    // The bank's logo and brand colour, fetched once per item. The logo is Plaid's base64 PNG, or
    // a data URL of the bank's site icon when Plaid has none.
    @Column(name = "institution_logo", columnDefinition = "TEXT")
    private String institutionLogo;

    @Column(name = "institution_color", length = 16)
    private String institutionColor;

    @Column(name = "institution_branding_fetched")
    private Boolean institutionBrandingFetched;

    // Whether the bank's site icon was looked up for a bank Plaid has no logo for (null = not yet)
    @Column(name = "institution_icon_fetched")
    private Boolean institutionIconFetched;

    // Whether transactions synced before logos were stored have had them filled in (null = not yet)
    @Column(name = "logos_backfilled")
    private Boolean logosBackfilled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
