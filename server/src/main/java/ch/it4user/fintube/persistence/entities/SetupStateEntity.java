package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "setup_state")
public class SetupStateEntity {
    @Id
    @Column(name = "key", length = 255)
    private String key;

    @Lob
    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    protected SetupStateEntity() {
    }

    public SetupStateEntity(String key, String value, String createdAt) {
        this.key = key;
        this.value = value;
        this.createdAt = createdAt;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}
