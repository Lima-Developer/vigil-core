package com.vigil.bootstrap;

import com.vigil.application.exception.CaseNotFound;
import com.vigil.application.exception.ConcurrentCaseUpdate;
import com.vigil.application.exception.InvalidAnalysisCaseTransition;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Public entry point of Vigil, callable without terminating the JVM.
 *
 * <h2>Syntax</h2>
 * <pre>
 * (no arguments)                                  health check (see {@link Main})
 * case discover                                   opens the case of the fixed scenario query
 * case triage --case &lt;uuid&gt; --reason &lt;text&gt;     moves a DISCOVERED case to TRIAGED
 * case show --case &lt;uuid&gt;                         prints status, version, history and audit event types
 * </pre>
 * Options may appear in any order, each at most once, and each takes exactly one argument (quote a reason that
 * has spaces). The case commands read the SQLite file from {@code VIGIL_DATABASE_PATH}, migrate it and close
 * every connection before returning.
 *
 * <h2>Scenario</h2>
 * {@code case discover} always observes the same {@code SELECT} (PG_STAT_STATEMENTS, constant window and metrics)
 * under a constant idempotency key, so repeating it returns the same case with {@code created=false}. The case is
 * identified by the {@code uuid} printed by {@code discover}, which {@code triage} and {@code show} take in
 * {@code --case}. Triage is attributed to the constant actor {@code vigil-cli}.
 *
 * <h2>Output (stdout, one {@code key=value} record per line)</h2>
 * <pre>
 * discover: case=&lt;uuid&gt; created=&lt;true|false&gt;
 * triage:   case=&lt;uuid&gt; status=TRIAGED
 * show:     case=&lt;uuid&gt; status=&lt;status&gt; version=&lt;n&gt;
 *           history=&lt;FROM&gt;-&gt;&lt;TO&gt; reason=&lt;text&gt;      (one per transition)
 *           event=&lt;type&gt;                           (one per persisted domain event, in order; no payload)
 * </pre>
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>{@code 0} success;</li>
 *   <li>{@code 2} invalid usage or configuration: unknown command, missing/duplicated/unknown option, malformed
 *       uuid, blank reason, missing {@code VIGIL_DATABASE_PATH};</li>
 *   <li>{@code 1} operational failure: case not found, case not triageable, concurrent update, unavailable or
 *       corrupt storage.</li>
 * </ul>
 * Errors go to stderr with fixed text; driver messages, SQL, stack traces and the raw reason are never printed.
 */
public final class VigilCli {
    private static final String USAGE = "Uso: case discover | case triage --case <uuid> --reason <motivo> "
            + "| case show --case <uuid>";

    private VigilCli() {
    }

    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return Main.run(env, out, err);
        }
        try {
            return dispatch(args, env, out);
        } catch (UsageException usage) {
            err.println(usage.getMessage());
            return 2;
        } catch (CaseNotFound notFound) {
            err.println("Caso não encontrado.");
            return 1;
        } catch (InvalidAnalysisCaseTransition transition) {
            err.println("O caso não pode ser triado no estado atual.");
            return 1;
        } catch (ConcurrentCaseUpdate conflict) {
            err.println("O caso foi alterado por outra operação. Tente novamente.");
            return 1;
        } catch (RuntimeException failure) {
            err.println("Falha ao acessar o armazenamento. Verifique o volume e a integridade do banco.");
            return 1;
        }
    }

    private static int dispatch(String[] args, Map<String, String> env, PrintStream out) {
        if (args.length < 2 || !args[0].equals("case")) {
            throw new UsageException(USAGE);
        }
        return switch (args[1]) {
            case "discover" -> {
                options(args, Set.of());
                var flow = CaseFlow.open(databaseFile(env));
                var result = flow.discover();
                out.println("case=" + result.caseId() + " created=" + result.created());
                yield 0;
            }
            case "triage" -> {
                var options = options(args, Set.of("--case", "--reason"));
                var caseId = caseId(options);
                var reason = options.get("--reason");
                if (reason == null || reason.isBlank()) {
                    throw new UsageException("Informe --reason com um motivo não vazio.");
                }
                var flow = CaseFlow.open(databaseFile(env));
                flow.triage(caseId, reason);
                out.println("case=" + caseId + " status=" + flow.find(caseId).status());
                yield 0;
            }
            case "show" -> {
                var caseId = caseId(options(args, Set.of("--case")));
                show(CaseFlow.open(databaseFile(env)), caseId, out);
                yield 0;
            }
            default -> throw new UsageException(USAGE);
        };
    }

    private static void show(CaseFlow flow, UUID caseId, PrintStream out) {
        var analysisCase = flow.find(caseId);
        out.println("case=" + caseId + " status=" + analysisCase.status() + " version=" + analysisCase.version());
        analysisCase.events().forEach(event -> out.println("history=" + event.fromStatus() + "->" + event.toStatus()
                + " reason=" + singleLine(event.reason())));
        flow.auditOf(caseId).forEach(event -> out.println("event=" + event.type()));
    }

    /** Keeps a stored reason on one output line so it cannot forge other records. */
    private static String singleLine(String text) {
        return text.replaceAll("\\p{Cntrl}+", " ");
    }

    private static Map<String, String> options(String[] args, Set<String> allowed) {
        var options = new HashMap<String, String>();
        for (int index = 2; index < args.length; index += 2) {
            var name = args[index];
            if (!allowed.contains(name) || index + 1 >= args.length || options.containsKey(name)) {
                throw new UsageException(USAGE);
            }
            options.put(name, args[index + 1]);
        }
        return options;
    }

    private static UUID caseId(Map<String, String> options) {
        var value = options.get("--case");
        if (value == null) {
            throw new UsageException("Informe --case com o uuid do caso.");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new UsageException("--case deve ser um uuid válido.");
        }
    }

    private static Path databaseFile(Map<String, String> env) {
        var databasePath = env.get("VIGIL_DATABASE_PATH");
        if (databasePath == null || databasePath.isBlank()) {
            throw new UsageException("VIGIL_DATABASE_PATH é obrigatória. Informe o caminho do arquivo SQLite.");
        }
        try {
            return Path.of(databasePath);
        } catch (RuntimeException invalid) {
            throw new UsageException("VIGIL_DATABASE_PATH não é um caminho válido.");
        }
    }

    private static final class UsageException extends RuntimeException {
        private UsageException(String message) {
            super(message);
        }
    }
}
