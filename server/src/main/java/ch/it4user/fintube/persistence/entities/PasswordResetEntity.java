package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "password_resets")
public class PasswordResetEntity {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;
    @Column(name = "expires_at", nullable = false, length = 64)
    private String expiresAt;
    @Column(name = "requested_at", nullable = false, length = 64)
    private String requestedAt;

    protected PasswordResetEntity() {}

    public PasswordResetEntity(Long userId, String codeHash, String expiresAt, String requestedAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.requestedAt = requestedAt;
    }

    public String getCodeHash() { return codeHash; }
    public String getExpiresAt() { return expiresAt; }
    public String getRequestedAt() { return requestedAt; }
}
