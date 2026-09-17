package ch.it4user.fintube.persistence;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runs schema migrations before any JPA repository is used. */
@Configuration
public class LiquibaseConfiguration {
  @Bean
  SpringLiquibase liquibase(DataSource dataSource) {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource);
    liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
    return liquibase;
  }
}
