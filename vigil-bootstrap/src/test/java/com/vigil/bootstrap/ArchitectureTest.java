package com.vigil.bootstrap;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.vigil.core.domain.fixture.ForbiddenDomainAdapterDependency;
import org.junit.jupiter.api.Test;

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

    private static final ArchRule BOOTSTRAP_ONLY_COMPOSES = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.vigil.bootstrap..")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.sqlite..")
            .because("o bootstrap compõe adapters e encaminha comandos, sem JDBC próprio");

    @Test
    void shouldKeepBootstrapFreeOfJdbc() {
        BOOTSTRAP_ONLY_COMPOSES.check(productionClasses());
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

    private static com.tngtech.archunit.core.domain.JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.vigil");
    }
}
