package ch.it4user.fintube.core;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Owns the application-managed filesystem layout; persistence owns the database. */
@Component
public class ApplicationPaths {
  @Value("${fintube.data-dir}") String dataDir;
  public Path root;
  public Path usersRoot;
  public Path cacheRoot;

  @PostConstruct
  void initialize() throws Exception {
    root = Path.of(dataDir).toAbsolutePath().normalize();
    usersRoot = root.resolve("users");
    cacheRoot = root.resolve("cache");
    Files.createDirectories(usersRoot);
    Files.createDirectories(cacheRoot);
  }
}
