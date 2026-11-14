package com.aiworkforce.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.aiworkforce.core.common.persistence.TenantScoped;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import java.util.Set;
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

    /** Methods inherited from Spring Data that ignore the organization (forbidden on tenant repositories). */
    private static final Set<String> UNSCOPED_METHODS = Set.of("findById", "findAll", "findAllById", "existsById",
            "getReferenceById", "getById", "getOne", "count", "deleteById", "deleteAll", "deleteAllById",
            "deleteAllInBatch", "deleteAllByIdInBatch");

    @ArchTest
    static final ArchRule tenantRepositoryQueriesTakeTheOrganization = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(TenantScoped.class)
            .should().haveNameMatching(".*Organization.*")
            .because("every query on organization-owned data must be scoped by the organization id");

    @ArchTest
    static final ArchRule unscopedRepositoryMethodsAreNeverCalledOnTenantData = noClasses()
            .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("an unscoped method of a @TenantScoped repository") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTargetOwner().isAnnotatedWith(TenantScoped.class)
                            && UNSCOPED_METHODS.contains(call.getName());
                }
            })
            .because("findById/findAll would bypass tenant isolation");

    @ArchTest
    static final ArchRule repositoriesAreNotVisibleOutsideTheirModule = classes()
            .that().areAssignableTo(Repository.class).and().areInterfaces()
            .should().notBePublic()
            .because("other modules go through the owning module's services");

    // ---- workforce modules (Sprint B2): fixed dependency order, ports instead of cycles ----
    private static final String CORE = "com.aiworkforce.core.";

    @ArchTest
    static final ArchRule workforceKernelDependsOnNoModule = noClasses()
            .that().resideInAPackage(CORE + "workforce..")
            .should().dependOnClassesThat().resideInAnyPackage(CORE + "department..", CORE + "position..",
                    CORE + "employee..", CORE + "team..", CORE + "access..", CORE + "organization..")
            .because("the shared kernel only declares ports; modules depend on it, never the reverse");

    @ArchTest
    static final ArchRule departmentsDoNotDependOnOtherWorkforceModules = noClasses()
            .that().resideInAPackage(CORE + "department..")
            .should().dependOnClassesThat().resideInAnyPackage(CORE + "position..", CORE + "employee..", CORE + "team..")
            .because("departments reach employees only through the EmployeeDirectory port");

    @ArchTest
    static final ArchRule positionsOnlyDependOnDepartments = noClasses()
            .that().resideInAPackage(CORE + "position..")
            .should().dependOnClassesThat().resideInAnyPackage(CORE + "employee..", CORE + "team..");

    @ArchTest
    static final ArchRule employeesDoNotDependOnTeams = noClasses()
            .that().resideInAPackage(CORE + "employee..")
            .should().dependOnClassesThat().resideInAPackage(CORE + "team..")
            .because("teams react to employee lifecycle through EmployeeLifecycleParticipant");

    @ArchTest
    static final ArchRule teamsDoNotDependOnEmployeesOrPositions = noClasses()
            .that().resideInAPackage(CORE + "team..")
            .should().dependOnClassesThat().resideInAnyPackage(CORE + "employee..", CORE + "position..")
            .because("team members are employees, but only through the EmployeeDirectory port");

    @ArchTest
    static final ArchRule identityAndAccessKnowNothingOfTheWorkforce = noClasses()
            .that().resideInAnyPackage(CORE + "identity..", CORE + "auth..", CORE + "audit..", CORE + "access..",
                    CORE + "organization..", CORE + "invitation..")
            .should().dependOnClassesThat().resideInAnyPackage(CORE + "department..", CORE + "position..",
                    CORE + "employee..", CORE + "team..", CORE + "workforce..", CORE + "agent..", CORE + "provider..")
            .because("authorization and tenancy do not depend on the HR domain built on top of them");

    @ArchTest
    static final ArchRule workforceServicesAlwaysCheckTheTenantFirst = classes()
            .that().areAnnotatedWith(Service.class)
            .and().resideInAnyPackage(CORE + "department..", CORE + "position..", CORE + "employee..", CORE + "team..")
            .and().haveSimpleNameEndingWith("Service")
            .should().dependOnClassesThat().haveFullyQualifiedName(CORE + "access.TenantAccessService")
            .because("every workforce service starts operations with the membership and permission check");

    // ---- AI agents and providers (Sprint B3) ----

    @ArchTest
    static final ArchRule humanWorkforceModulesKnowNothingOfAgentsOrProviders = noClasses()
            .that().resideInAnyPackage(CORE + "department..", CORE + "position..", CORE + "employee..", CORE + "team..")
            .should().dependOnClassesThat().resideInAnyPackage(CORE + "agent..", CORE + "provider..")
            .because("agents reach them through ports (AgentDirectory) and TeamDirectory, never the reverse");

    @ArchTest
    static final ArchRule providersDependOnNoWorkforceModuleNorOnAgents = noClasses()
            .that().resideInAPackage(CORE + "provider..")
            .should().dependOnClassesThat().resideInAnyPackage(CORE + "agent..", CORE + "department..", CORE + "position..",
                    CORE + "employee..", CORE + "team..", CORE + "workforce..")
            .because("provider connections are infrastructure: agents depend on them (ConnectionUsage is a port)");

    @ArchTest
    static final ArchRule onlyTheProviderModuleCanRevealASecret = noClasses()
            .that().resideOutsideOfPackage(CORE + "provider..")
            .should().callMethod(com.aiworkforce.core.provider.SecretStore.class, "reveal", java.util.UUID.class, java.util.UUID.class)
            .because("credentials are decrypted only by the provider adapters, never elsewhere");

    @ArchTest
    static final ArchRule agentsNeverTouchTheSecretStore = noClasses()
            .that().resideInAPackage(CORE + "agent..")
            .should().dependOnClassesThat().haveSimpleName("SecretStore")
            .because("an agent references a provider connection, never a credential");

    @ArchTest
    static final ArchRule agentAndProviderServicesAlwaysCheckTheTenantFirst = classes()
            .that().areAnnotatedWith(Service.class)
            .and().resideInAnyPackage(CORE + "agent..", CORE + "provider..")
            .and().haveSimpleNameEndingWith("Service")
            .and().doNotHaveSimpleName("AesGcmSecretStore")
            .should().dependOnClassesThat().haveFullyQualifiedName(CORE + "access.TenantAccessService")
            .because("every operation starts with the membership and permission check");

    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should().beAnnotatedWith(Autowired.class)
            .allowEmptyShould(true)
            .because("constructor injection keeps dependencies explicit and testable");
}
