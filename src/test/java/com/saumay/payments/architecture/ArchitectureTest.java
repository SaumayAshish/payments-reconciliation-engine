package com.saumay.payments.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Package-dependency rules from docs/architecture.md section 3.1. The pure core must stay free of frameworks so
 * that determinism and money safety are properties of plain Java (ADR-0001, ADR-0003).
 */
class ArchitectureTest {

    private static final String ROOT = "com.saumay.payments";
    private static final String[] PURE_CORE = {ROOT + ".domain..", ROOT + ".matching..", ROOT + ".ingestion.."};

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT);

    @Test
    void pureCoreDependsOnNoFrameworkOrPersistenceCode() {
        noClasses().that().resideInAnyPackage(PURE_CORE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "java.sql..", "javax.sql..", "com.fasterxml..",
                        "org.postgresql..", "org.flywaydb..", "io.micrometer..", "org.slf4j..")
                .because("domain, matching and ingestion are plain Java")
                .check(CLASSES);
    }

    @Test
    void domainDependsOnNothingAboveIt() {
        noClasses().that().resideInAPackage(ROOT + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".matching..", ROOT + ".ingestion..", ROOT + ".application..",
                        ROOT + ".infrastructure..", ROOT + ".api..", ROOT + ".config..")
                .check(CLASSES);
    }

    @Test
    void nothingOutsideTheApiLayerDependsOnIt() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".api..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".api..")
                .check(CLASSES);
    }

    @Test
    void coreAndApplicationDoNotDependOnInfrastructure() {
        noClasses().that().resideInAnyPackage(
                        ROOT + ".domain..", ROOT + ".matching..", ROOT + ".ingestion..", ROOT + ".application..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure..")
                .check(CLASSES);
    }

    @Test
    void bigDecimalIsConfinedToTheNormaliserPackage() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".ingestion.normalise..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.math.BigDecimal")
                .because("money enters as text and leaves as a long (ADR-0001)")
                .check(CLASSES);
    }
}