package com.vigil.bootstrap;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring is deliberately confined to the composition root. */
@SpringBootApplication
public class VigilApplication {
    public static void main(String[] args) {
        var ephemeral = EphemeralDemoStorage.prepare(args, System.getenv()).orElse(null);
        var application = new SpringApplication(VigilApplication.class);
        if (ephemeral != null) {
            application.setDefaultProperties(Map.of(
                    "vigil.sqlite.path", ephemeral.databaseFile().toString(),
                    "vigil.sqlite.minimum-free-bytes", "1048576"));
            // The hook below closes Spring before deleting the SQLite files.
            application.setRegisterShutdownHook(false);
        }
        try {
            var context = application.run(args);
            if (ephemeral != null) {
                var storage = ephemeral;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        context.close();
                    } finally {
                        storage.close();
                    }
                }, "vigil-demo-cleanup"));
            }
        } catch (RuntimeException failure) {
            if (ephemeral != null) ephemeral.close();
            throw failure;
        }
    }
}
