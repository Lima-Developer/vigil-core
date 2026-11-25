package com.vigil.bootstrap;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring is deliberately confined to the composition root. */
@SpringBootApplication
public class VigilApplication {
    public static void main(String[] args) {
        var ephemeral = EphemeralDemoStorage.prepare(args, System.getenv()).orElse(null);
        var application = new SpringApplication(VigilApplication.class);
        String[] effectiveArguments = args;
        if (ephemeral != null) {
            effectiveArguments = withEphemeralStorageArguments(args, ephemeral);
            // The demo profile carries the fixed fingerprint key so the explicitly requested demo runs end to end.
            application.setAdditionalProfiles("demo");
            // The hook below closes Spring before deleting the SQLite files.
            application.setRegisterShutdownHook(false);
        }
        try {
            var context = application.run(effectiveArguments);
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

    private static String[] withEphemeralStorageArguments(String[] arguments, EphemeralDemoStorage storage) {
        var values = Arrays.copyOf(arguments, arguments.length + 2);
        values[arguments.length] = "--vigil.sqlite.path=" + storage.databaseFile();
        values[arguments.length + 1] = "--vigil.sqlite.minimum-free-bytes=1048576";
        return values;
    }
}
