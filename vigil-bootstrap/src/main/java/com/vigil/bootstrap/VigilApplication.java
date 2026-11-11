package com.vigil.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring is deliberately confined to the composition root. The CLI remains independently runnable. */
@SpringBootApplication
public class VigilApplication {
    public static void main(String[] args) {
        SpringApplication.run(VigilApplication.class, args);
    }
}
