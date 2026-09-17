package ch.it4user.fintube;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FinTubeApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinTubeApplication.class, args);
    }
}
