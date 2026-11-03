package com.storenode;

import com.tngtech.archunit.base.HasDescription;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The architecture, enforced. These tests read the compiled classes and fail the build when a rule is broken,
 * so the layering described in DESIGN.md cannot quietly rot.
 */
@AnalyzeClasses(packages = "com.storenode", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // ---- the dependency rule: arrows point inwards ----

    @ArchTest
    static final ArchRule domainUsesOnlyTheJdk = classes()
            .that().resideInAPackage("com.storenode.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.storenode.domain..");

    @ArchTest
    static final ArchRule applicationUsesOnlyTheJdkAndTheDomain = classes()
            .that().resideInAPackage("com.storenode.application..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.storenode.domain..", "com.storenode.application..");

    @ArchTest
    static final ArchRule webDoesNotReachIntoInfrastructure = noClasses()
            .that().resideInAPackage("com.storenode.web..")
            .should().dependOnClassesThat().resideInAPackage("com.storenode.infrastructure..");

    @ArchTest
    static final ArchRule infrastructureDoesNotReachIntoWeb = noClasses()
            .that().resideInAPackage("com.storenode.infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("com.storenode.web..");

    @ArchTest
    static final ArchRule theLayersHaveNoCycles = slices()
            .matching("com.storenode.(*)..")
            .should().beFreeOfCycles();

    // ---- shape of the code ----

    @ArchTest
    static final ArchRule portsAreInterfaces = classes()
            .that().resideInAPackage("com.storenode.application.port..")
            .and().areTopLevelClasses()
            .should().beInterfaces();

    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should().beAnnotatedWith(Autowired.class)
            .because("dependencies come through the constructor, so a class cannot exist half-built");

    // ---- money and time ----

    private static final Set<String> FLOATING_POINT = Set.of("double", "float", "java.lang.Double", "java.lang.Float");

    private static final ArchCondition<JavaClass> notUseFloatingPoint =
            new ArchCondition<>("not use double or float in fields, return types or parameters") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    javaClass.getFields().forEach(field -> flag(field.getRawType(), field, events));
                    javaClass.getMethods().forEach(method -> {
                        flag(method.getRawReturnType(), method, events);
                        method.getRawParameterTypes().forEach(type -> flag(type, method, events));
                    });
                }

                private void flag(JavaClass type, HasDescription where, ConditionEvents events) {
                    if (FLOATING_POINT.contains(type.getName())) {
                        events.add(SimpleConditionEvent.violated(where, where.getDescription() + " uses " + type.getName()));
                    }
                }
            };

    @ArchTest
    static final ArchRule businessCodeNeverUsesFloatingPointNumbers = classes()
            .that().resideInAnyPackage("com.storenode.domain..", "com.storenode.application..")
            .should(notUseFloatingPoint);

    @ArchTest
    static final ArchRule businessCodeNeverTurnsMoneyIntoAFloatOrBuildsItFromOne = noClasses()
            .that().resideInAnyPackage("com.storenode.domain..", "com.storenode.application..")
            .should().callMethod(BigDecimal.class, "doubleValue")
            .orShould().callMethod(BigDecimal.class, "floatValue")
            .orShould().callConstructor(BigDecimal.class, double.class)
            .orShould().callMethod(BigDecimal.class, "valueOf", double.class);

    // The one exception is Ids: a UUID v7 carries its creation time, so generating an id needs a time source.
    @ArchTest
    static final ArchRule businessCodeNeverReadsTheClockItself = noClasses()
            .that().resideInAnyPackage("com.storenode.domain..", "com.storenode.application..")
            .and().doNotHaveFullyQualifiedName("com.storenode.domain.shared.Ids")
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(OffsetDateTime.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .because("time comes from an injected Clock, so tests can fix it");
}
