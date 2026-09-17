package ch.it4user.fintube.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "sessions")
public class SessionEntity {
  @Id @Column(length = 255) private String token;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
  @Column(name = "expires_at", nullable = false) private String expiresAt;
  protected SessionEntity() { }
  public SessionEntity(String token, UserEntity user, String expiresAt){this.token=token;this.user=user;this.expiresAt=expiresAt;}
  public UserEntity getUser(){return user;}
}
