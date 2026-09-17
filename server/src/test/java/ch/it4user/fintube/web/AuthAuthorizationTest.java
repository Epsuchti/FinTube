package ch.it4user.fintube.web;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import java.util.UUID;
import jakarta.servlet.http.Cookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="fintube.data-dir=${user.dir}/target/fintube-test")
@AutoConfigureMockMvc
class AuthAuthorizationTest {
 @Autowired MockMvc mvc;
 @Test void registrationHashesPasswordAndProtectsPrivateRoutes() throws Exception {
   String name="u"+UUID.randomUUID().toString().replace("-","").substring(0,12);
   MvcResult registered=mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+name+"\",\"password\":\"correct horse battery staple\"}"))
       .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("USER")).andReturn();
   String cookie=registered.getResponse().getHeader("Set-Cookie").split(";",2)[0];
   Cookie session=new Cookie("FT_SESSION",cookie.substring("FT_SESSION=".length()));
   mvc.perform(get("/api/subscriptions").cookie(session)).andExpect(status().isOk());
   mvc.perform(get("/api/admin/settings").cookie(session)).andExpect(status().isForbidden());
   mvc.perform(get("/api/subscriptions")).andExpect(status().isUnauthorized());
   mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+name+"\",\"password\":\"wrong password wrong\"}"))
       .andExpect(status().isUnauthorized());
 }
}
