package com.example.controltower;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

@AnalyzeClasses(packages = ArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String ROOT = "com.example.controltower";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String APPLICATION = ROOT + ".application..";
    private static final String ADAPTER = ROOT + ".adapter..";
    private static final String TOOL = ROOT + ".adapter.in.tool..";
    private static final String MCP = ROOT + ".adapter.in.mcp..";
    private static final String SIMULATOR = ROOT + ".simulator..";

    // domain과 application 규칙에 allowEmptyShould를 두지 않는다.
    // 지원하지 않는 바이트코드를 ArchUnit이 조용히 건너뛰면 여기서 실패해 알 수 있다.
    @ArchTest
    static final ArchRule domainDependsOnJdkOnly = classes()
            .that().resideInAPackage(DOMAIN)
            .should().onlyDependOnClassesThat().resideInAnyPackage(DOMAIN, "java..");

    // Reactor는 스트리밍 포트의 타입으로 쓰는 유일한 예외다.
    @ArchTest
    static final ArchRule applicationDependsOnDomainJdkReactorOnly = classes()
            .that().resideInAPackage(APPLICATION)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(APPLICATION, DOMAIN, "java..", "reactor.core..");

    @ArchTest
    static final ArchRule adaptersDoNotReferenceEachOther = slices()
            .matching(ROOT + ".adapter.(*).(*)..")
            .should().notDependOnEachOther();

    @ArchTest
    static final ArchRule toolAdapterCallsInboundPortsOnly = noClasses()
            .that().resideInAPackage(TOOL)
            .should().dependOnClassesThat(
                    resideInAnyPackage(ROOT + ".application.service..", ROOT + ".application.port.out..")
                            .or(resideInAPackage(ADAPTER).and(not(resideInAPackage(TOOL)))))
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule mcpAdapterCallsInboundPortsOnly = noClasses()
            .that().resideInAPackage(MCP)
            .should().dependOnClassesThat(
                    resideInAnyPackage(ROOT + ".application.service..", ROOT + ".application.port.out..")
                            .or(resideInAPackage(ADAPTER).and(not(resideInAPackage(MCP)))))
            .allowEmptyShould(true);

    // 들어오는 어댑터는 port.in으로만 애플리케이션에 들어간다. 나가는 포트를 직접 부르면 서비스를 건너뛴다.
    @ArchTest
    static final ArchRule inboundAdaptersDoNotUseOutboundPorts = noClasses()
            .that().resideInAPackage(ROOT + ".adapter.in..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".application.port.out..");

    // 시뮬레이터와 업무 코드는 HTTP로만 만난다.
    @ArchTest
    static final ArchRule simulatorIsolatedFromBusinessCode = CompositeArchRule
            .of(noClasses()
                    .that().resideInAPackage(SIMULATOR)
                    .should().dependOnClassesThat().resideInAnyPackage(DOMAIN, APPLICATION, ADAPTER)
                    .allowEmptyShould(true))
            .and(noClasses()
                    .that().resideInAnyPackage(DOMAIN, APPLICATION, ADAPTER)
                    .should().dependOnClassesThat().resideInAPackage(SIMULATOR));

    // 웹 스택은 WebFlux다. 서블릿 API를 쓰면 요청 스레드 모델이 섞인다.
    @ArchTest
    static final ArchRule noServletApi = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "org.springframework.web.servlet..");

    @ArchTest
    static final ArchRule springOnlyOutsideCore = noClasses()
            .that().resideOutsideOfPackages(ADAPTER, ROOT + ".config..", ROOT + ".demo..", SIMULATOR)
            .and().doNotBelongToAnyOf(ControlTowerApplication.class)
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");
}
