package ch.it4user.fintube.core;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import jakarta.servlet.http.*;
import java.sql.*; import java.time.*; import java.security.SecureRandom; import java.util.*;
@Service public class AuthService {
  final Database db; final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12); final SecureRandom random=new SecureRandom();
  public AuthService(Database db){this.db=db;}
  public record Principal(long id,String username,String role,String slug) { public boolean admin(){return "ADMIN".equals(role);} }
  public String hash(String p){return passwords.encode(p);} public boolean matches(String p,String h){return passwords.matches(p,h);}
  public Principal current(HttpServletRequest r) throws SQLException { String token=null; if(r.getCookies()!=null)for(Cookie c:r.getCookies())if("FT_SESSION".equals(c.getName()))token=c.getValue(); if(token==null)return null;
    try(Connection c=db.open(); PreparedStatement p=c.prepareStatement("SELECT u.id,u.username,u.role,u.filesystem_slug FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token=? AND s.expires_at>?")){p.setString(1,token);p.setString(2,Database.now());ResultSet x=p.executeQuery();return x.next()?new Principal(x.getLong(1),x.getString(2),x.getString(3),x.getString(4)):null;}}
  public String login(long user) throws SQLException { byte[] b=new byte[32];random.nextBytes(b);String t=Base64.getUrlEncoder().withoutPadding().encodeToString(b);try(Connection c=db.open();PreparedStatement p=c.prepareStatement("INSERT INTO sessions VALUES(?,?,?)")){p.setString(1,t);p.setLong(2,user);p.setString(3,Instant.now().plus(Duration.ofDays(14)).toString());p.executeUpdate();}return t; }
  public void logout(HttpServletRequest r) throws SQLException {if(r.getCookies()!=null)try(Connection c=db.open();PreparedStatement p=c.prepareStatement("DELETE FROM sessions WHERE token=?")){for(Cookie x:r.getCookies())if("FT_SESSION".equals(x.getName())){p.setString(1,x.getValue());p.executeUpdate();}}}
  public String slug(String username) throws SQLException {String s=username.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");if(s.isBlank())s="user";String base=s;int i=2;while(true){try(Connection c=db.open();PreparedStatement p=c.prepareStatement("SELECT 1 FROM users WHERE filesystem_slug=?")){p.setString(1,s);if(!p.executeQuery().next())return s;}s=base+"-"+i++;}}
}
