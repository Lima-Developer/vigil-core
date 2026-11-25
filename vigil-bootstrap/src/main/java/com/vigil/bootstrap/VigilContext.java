package com.vigil.bootstrap;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Opens a headless context for one CLI invocation. Each adapter opens connections only during operations. */
final class VigilContext {
    private VigilContext() { }

    static ConfigurableApplicationContext open(Map<String, String> variables) {
        var values = new HashMap<String, Object>();
        values.put("spring.main.web-application-type", "none");
        values.put("spring.main.banner-mode", "off");
        values.put("spring.main.log-startup-info", "false");
        values.put("spring.main.register-shutdown-hook", "false");
        values.put("logging.level.root", "OFF");
        copy(variables, values, "SPRING_PROFILES_ACTIVE", "spring.profiles.active");
        copy(variables, values, "VIGIL_DATABASE_PATH", "vigil.sqlite.path");
        copy(variables, values, "VIGIL_STORAGE_MIN_FREE_BYTES", "vigil.sqlite.minimum-free-bytes");
        copy(variables, values, "VIGIL_TARGET_NAME", "vigil.target.name");
        copy(variables, values, "VIGIL_ENVIRONMENT", "vigil.target.environment");
        copy(variables, values, "VIGIL_TARGET_HOST", "vigil.target.host");
        copy(variables, values, "VIGIL_TARGET_PORT", "vigil.target.port");
        copy(variables, values, "VIGIL_TARGET_DATABASE", "vigil.target.database");
        copy(variables, values, "VIGIL_TARGET_SSL_MODE", "vigil.target.ssl-mode");
        copy(variables, values, "VIGIL_FINGERPRINT_KEY", "vigil.fingerprint.key");
        copy(variables, values, "VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS", "vigil.target.connect-timeout");
        if (variables.containsKey("VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS")) {
            values.put("vigil.target.connect-timeout", variables.get("VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS") + "s");
        }
        return new SpringApplicationBuilder(VigilApplication.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("cli", values)))
                .run();
    }

    private static void copy(Map<String, String> from, Map<String, Object> to, String variable, String property) {
        if (from.containsKey(variable)) to.put(property, from.get(variable));
    }
}
