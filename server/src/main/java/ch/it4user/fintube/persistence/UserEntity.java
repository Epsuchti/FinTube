package ch.it4user.fintube.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 48)
    private String username;
    @Column(unique = true, length = 320)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(nullable = false, length = 16)
    private String role;
    @Column(name = "filesystem_slug", nullable = false, unique = true, length = 80)
    private String filesystemSlug;
    @Column(name = "created_at", nullable = false)
    private String createdAt;
    @Column(name = "updated_at", nullable = false)
    private String updatedAt;

    protected UserEntity() {
    }

    public UserEntity(String username, String email, String passwordHash, String role, String filesystemSlug, String createdAt, String updatedAt) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.filesystemSlug = filesystemSlug;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public String getFilesystemSlug() {
        return filesystemSlug;
    }

    public void setPasswordHash(String value) {
        passwordHash = value;
    }

    public void setRole(String value) {
        role = value;
    }

    public void setUpdatedAt(String value) {
        updatedAt = value;
    }
}
