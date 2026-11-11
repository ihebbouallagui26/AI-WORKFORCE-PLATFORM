package com.aiworkforce.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Executable version of the module and layer rules in
 * docs/architecture/CORE_BACKEND_ARCHITECTURE.md. Rules that target layers not written yet
 * (entities, repositories) allow empty matches and start enforcing as soon as code exists.
 */
@AnalyzeClasses(packages = "com.aiworkforce.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles = slices()
            .matching("com.aiworkforce.core.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule commonDoesNotDependOnBusinessModules = classes()
            .that().resideInAPackage("com.aiworkforce.core.common..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "com.aiworkforce.core.common..", "java..", "jakarta..", "org.springframework..", "org.slf4j..")
            .because("common is shared infrastructure and must stay independent of business modules");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .allowEmptyShould(true)
            .because("controllers handle HTTP only; persistence goes through services");

    @ArchTest
    static final ArchRule controllersDoNotExposeEntities = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .allowEmptyShould(true)
            .because("JPA entities are never part of the API contract: controllers use DTOs");

    @ArchTest
    static final ArchRule servicesDoNotDependOnControllers = noClasses()
            .that().areAnnotatedWith(Service.class)
            .should().dependOnClassesThat().areAnnotatedWith(RestController.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should().beAnnotatedWith(Autowired.class)
            .allowEmptyShould(true)
            .because("constructor injection keeps dependencies explicit and testable");
}
