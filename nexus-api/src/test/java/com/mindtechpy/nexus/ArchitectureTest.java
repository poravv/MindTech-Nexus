package com.mindtechpy.nexus;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/** Hexagonal boundaries (ADR-001): dependencies only point inwards. Breaking a rule fails the build. */
@AnalyzeClasses(packages = "com.mindtechpy.nexus", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_depends_only_on_itself_and_the_jdk = classes()
            .that().resideInAPackage("..nexus.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("..nexus.domain..", "java..")
            .because("el dominio no conoce Spring, Jakarta, Jackson ni infraestructura");

    @ArchTest
    static final ArchRule application_depends_only_on_domain_and_the_jdk = classes()
            .that().resideInAPackage("..nexus.application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("..nexus.application..", "..nexus.domain..", "java..")
            .because("los casos de uso solo hablan con puertos y dominio");
}
