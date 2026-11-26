package com.vigil.bootstrap;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.vigil.application.fixture.ForbiddenApplicationJdbcDependency;
import com.vigil.core.domain.fixture.ForbiddenDomainAdapterDependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitectureTest {

    private static final ArchRule DOMAIN_STAYS_IN_ITS_PACKAGES = ArchRuleDefinition.classes()
            .that().resideInAPackage("com.vigil.core..")
            .should().resideInAnyPackage("com.vigil.core.domain..", "com.vigil.core.exception..")
            .because("classes do domínio devem permanecer nos packages de domínio ou exceção");

    private static final ArchRule DOMAIN_IS_INFRASTRUCTURE_FREE = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.vigil.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "java.sql..", "javax.sql..", "com.fasterxml.jackson..", "com.vigil.adapters.."
            )
            .because("o domínio não pode conhecer Spring, JDBC, Jackson ou adapters");

    private static final ArchRule APPLICATION_IS_INDEPENDENT_OF_INFRASTRUCTURE = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.vigil.application..")
            .should().dependOnClassesThat().resideInAnyPackage("com.vigil.adapters..", "com.vigil.bootstrap..")
            .because("a aplicação depende de ports e domínio, nunca de adapters ou bootstrap");

    private static final ArchRule APPLICATION_NEVER_SEES_JDBC = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.vigil.application..")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
            .because("a conexão JDBC nunca atravessa a fronteira do adapter; o core só vê TargetConnectionFailure");

    private static final ArchRule SPRING_ONLY_IN_BOOTSTRAP = ArchRuleDefinition.noClasses()
            .that().resideInAnyPackage("com.vigil.core..", "com.vigil.application..", "com.vigil.adapters..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
            .because("Spring pertence somente ao composition root");

    private static final ArchRule BOOTSTRAP_ONLY_COMPOSES = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.vigil.bootstrap..")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.sqlite..")
            .because("o bootstrap compõe adapters e encaminha comandos, sem JDBC próprio");

    private static final ArchRule INFERENCE_ADAPTERS_ONLY_ACCEPT_APPROVED_REQUESTS = ArchRuleDefinition.methods()
            .that().areDeclaredInClassesThat().resideInAPackage("com.vigil.adapters.inference..")
            .and().haveName("infer")
            .should().haveRawParameterTypes(com.vigil.application.port.out.interfaces.InferencePort.InferenceRequest.class)
            .because("adapters de inferência recebem somente a requisição construída com SanitizedInferenceContext");

    @Test
    void shouldKeepBootstrapFreeOfJdbc() {
        BOOTSTRAP_ONLY_COMPOSES.check(productionClasses());
    }

    @Test
    void shouldKeepFreePromptStringsOutOfInferenceAdapters() {
        INFERENCE_ADAPTERS_ONLY_ACCEPT_APPROVED_REQUESTS.check(productionClasses());
    }

    @Test
    void shouldKeepSpringOnlyInBootstrap() {
        SPRING_ONLY_IN_BOOTSTRAP.check(productionClasses());
    }

    @Test
    void shouldKeepSpringDependenciesOutOfCoreModulePoms() throws Exception {
        var moduleDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        var root = Files.isDirectory(moduleDirectory.resolve("vigil-domain"))
                ? moduleDirectory : moduleDirectory.getParent();
        for (var module : new String[]{"vigil-domain", "vigil-application", "vigil-adapters"}) {
            assertPomHasNoSpringDependency(root.resolve(module).resolve("pom.xml"));
        }
    }

    @Test
    void pomCheckDetectsADeclaredSpringDependency(@TempDir Path directory) throws Exception {
        var pom = directory.resolve("pom.xml");
        Files.writeString(pom, """
                <project><dependencies><dependency>
                  <groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId>
                </dependency></dependencies></project>
                """);

        assertThrows(AssertionError.class, () -> assertPomHasNoSpringDependency(pom));
    }

    @Test
    void shouldKeepDomainClassesInExpectedPackages() {
        DOMAIN_STAYS_IN_ITS_PACKAGES.check(productionClasses());
    }

    @Test
    void shouldKeepDomainFreeOfInfrastructureDependencies() {
        DOMAIN_IS_INFRASTRUCTURE_FREE.check(productionClasses());
    }

    @Test
    void shouldKeepApplicationIndependentOfAdaptersAndBootstrap() {
        APPLICATION_IS_INDEPENDENT_OF_INFRASTRUCTURE.check(productionClasses());
    }

    @Test
    void shouldKeepApplicationFreeOfJdbc() {
        APPLICATION_NEVER_SEES_JDBC.check(productionClasses());
    }

    @Test
    void shouldFailWhenADomainClassDependsOnAnAdapter() {
        var violatingClass = new ClassFileImporter().importClasses(ForbiddenDomainAdapterDependency.class);

        assertThrows(AssertionError.class, () -> DOMAIN_IS_INFRASTRUCTURE_FREE.check(violatingClass));
    }

    @Test
    void shouldFailWhenAnApplicationClassLeaksJdbc() {
        var violatingClass = new ClassFileImporter().importClasses(ForbiddenApplicationJdbcDependency.class);

        assertThrows(AssertionError.class, () -> APPLICATION_NEVER_SEES_JDBC.check(violatingClass));
    }

    private static com.tngtech.archunit.core.domain.JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.vigil");
    }

    private static void assertPomHasNoSpringDependency(Path pom) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var dependencies = factory.newDocumentBuilder().parse(pom.toFile())
                .getElementsByTagNameNS("*", "dependency");
        for (int index = 0; index < dependencies.getLength(); index++) {
            var dependency = dependencies.item(index);
            var children = dependency.getChildNodes();
            for (int child = 0; child < children.getLength(); child++) {
                var node = children.item(child);
                if ("groupId".equals(node.getLocalName())
                        && node.getTextContent().strip().startsWith("org.springframework")) {
                    throw new AssertionError("Spring dependency outside vigil-bootstrap: " + pom);
                }
            }
        }
    }
}
