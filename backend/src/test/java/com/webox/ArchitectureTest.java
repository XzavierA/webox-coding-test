package com.webox;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private final JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.webox");

    @Test
    void domainCodeDoesNotDependOnApiOrInfrastructure() {
        noClasses().that().resideInAPackage("com.webox..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("com.webox..api..", "com.webox..infrastructure..")
                .check(classes);
    }

    @Test
    void apiAdaptersDoNotDependOnInfrastructureAdapters() {
        noClasses().that().resideInAPackage("com.webox..api..")
                .should().dependOnClassesThat().resideInAPackage("com.webox..infrastructure..")
                .check(classes);
    }
}
