package ch.it4user.fintube.service;
import ch.it4user.fintube.core.Database;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ch.it4user.fintube.persistence.*;
import jakarta.servlet.http.*;
import java.sql.*; import java.time.*; import java.security.SecureRandom; import java.util.*;
@Service public class AuthService {
  final Database db; final UserRepository users; final SessionRepository sessions; final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12); final SecureRandom random=new SecureRandom();
  public AuthService(Database db,UserRepository users,SessionRepository sessions){this.db=db;this.users=users;this.sessions=sessions;}
  public record Principal(long id,String username,String role,String slug) { public boolean admin(){return "ADMIN".equals(role);} }
  public String hash(String p){return passwords.encode(p);} public boolean matches(String p,String h){return passwords.matches(p,h);}
  @Transactional(readOnly=true) public Principal current(HttpServletRequest r) { String token=null; if(r.getCookies()!=null)for(Cookie c:r.getCookies())if("FT_SESSION".equals(c.getName()))token=c.getValue(); if(token==null)return null; return sessions.findByTokenAndExpiresAtAfter(token,Database.now()).map(s->{UserEntity u=s.getUser();return new Principal(u.getId(),u.getUsername(),u.getRole(),u.getFilesystemSlug());}).orElse(null);}
  @Transactional public String login(long user) { byte[] b=new byte[32];random.nextBytes(b);String t=Base64.getUrlEncoder().withoutPadding().encodeToString(b);sessions.save(new SessionEntity(t,users.getReferenceById(user),Instant.now().plus(Duration.ofDays(14)).toString()));return t; }
  @Transactional public void logout(HttpServletRequest r) {if(r.getCookies()!=null)for(Cookie x:r.getCookies())if("FT_SESSION".equals(x.getName()))sessions.deleteByToken(x.getValue());}
  public String slug(String username) {String s=username.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");if(s.isBlank())s="user";String base=s;int i=2;while(users.existsByFilesystemSlug(s))s=base+"-"+i++;return s;}
}
