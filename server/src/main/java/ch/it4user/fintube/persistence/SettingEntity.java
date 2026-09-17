package ch.it4user.fintube.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "settings")
public class SettingEntity {
  @Id @Column(name = "key", length = 255) private String key;
  @Lob @Column(name = "value", nullable = false) private String value;
  @Column(nullable = false) private int secret;
  @Column(name = "updated_at", nullable = false) private String updatedAt;
  protected SettingEntity() { }
  public SettingEntity(String key,String value,int secret,String updatedAt){this.key=key;this.value=value;this.secret=secret;this.updatedAt=updatedAt;}
  public String getKey(){return key;} public String getValue(){return value;} public int getSecret(){return secret;}
  public void setValue(String value){this.value=value;} public void setSecret(int value){secret=value;} public void setUpdatedAt(String value){updatedAt=value;}
}
