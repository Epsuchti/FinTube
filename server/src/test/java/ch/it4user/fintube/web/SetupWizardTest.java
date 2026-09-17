package ch.it4user.fintube.web;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SetupWizardTest {
 private static final String TOKEN = "setup-token-for-an-initial-admin-test";
 private static final Path DATA_DIR = tempDirectory();

 @Autowired MockMvc mvc;

 @DynamicPropertySource
 static void properties(DynamicPropertyRegistry registry) {
  registry.add("fintube.data-dir", () -> DATA_DIR.toString());
  registry.add("fintube.setup-admin-token", () -> TOKEN);
 }

 @Test void setupRequiresTheOneTimeTokenAndCreatesTheFirstAdmin() throws Exception {
  mvc.perform(get("/api/setup/status"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.setupRequired").value(true));

  mvc.perform(post("/api/setup/create-admin").contentType(MediaType.APPLICATION_JSON)
          .content("{\"username\":\"first-admin\",\"password\":\"correct horse battery staple\",\"token\":\"wrong\"}"))
      .andExpect(status().isUnauthorized());

  mvc.perform(post("/api/setup/create-admin").contentType(MediaType.APPLICATION_JSON)
          .content("{\"username\":\"first-admin\",\"email\":\"admin@example.test\",\"password\":\"correct horse battery staple\",\"token\":\"" + TOKEN + "\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.role").value("ADMIN"))
      .andExpect(cookie().exists("FT_SESSION"));

  mvc.perform(get("/api/setup/status"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.setupRequired").value(false));
  mvc.perform(post("/api/setup/create-admin").contentType(MediaType.APPLICATION_JSON)
          .content("{\"username\":\"other-admin\",\"password\":\"correct horse battery staple\",\"token\":\"" + TOKEN + "\"}"))
      .andExpect(status().isConflict());
 }

 private static Path tempDirectory() {
  try { return Files.createTempDirectory("fintube-setup-test-"); }
  catch (Exception e) { throw new ExceptionInInitializerError(e); }
 }
}
