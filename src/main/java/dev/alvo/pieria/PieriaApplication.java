package dev.alvo.pieria;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PieriaApplication {

  public static void main(String[] args) {
    SpringApplication.run(PieriaApplication.class, args);
  }

}
