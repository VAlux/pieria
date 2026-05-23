package dev.alvo.pieria;

import org.springframework.boot.SpringApplication;

public class TestPieriaApplication {

  public static void main(String[] args) {
    SpringApplication.from(PieriaApplication::main).with(TestcontainersConfiguration.class).run(args);
  }

}
