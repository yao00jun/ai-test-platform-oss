package com.aitest.analysis;

import com.aitest.analysis.ast.JavaCodeParser;
import com.aitest.analysis.diff.GitDiffAnalyzer;
import com.aitest.analysis.impact.*;
import com.aitest.analysis.source.SourceFile;
import com.aitest.execution.Values;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GitDiffAnalyzerTest {
    @Test void deletionUsesOldCallersAndRenamedMethodsRemainDistinctFromUnchangedSiblings() {
        String before = """
                package shop;
                @RestController class Api {
                    Service service;
                    @GetMapping("/removed") int removed() { return service.deleted(); }
                    @GetMapping("/renamed") int renamed() { return service.oldName(); }
                    @GetMapping("/stable") int stable() { return service.stable(); }
                }
                class Service {
                    int deleted() { return 5; }
                    int oldName() { return 7; }
                    int stable() { return 9; }
                }
                """;
        String after = before.replace("    @GetMapping(\"/removed\") int removed() { return service.deleted(); }\n", "")
                .replace("    int deleted() { return 5; }\n", "").replace("oldName", "newName")
                .replace("    int stable()", "    int added() { return 11; }\n    int stable()");
        var oldAst = ast("中文/Api.java", before); var newAst = ast("中文/Api.java", after);
        var diff = new GitDiffAnalyzer().compare(List.of(SourceFile.text("BASELINE", "中文/Api.java", before)), List.of(SourceFile.text("BACKEND", "中文/Api.java", after)), oldAst, newAst, () -> { });
        assertThat(Values.objects(diff.get("changedMethods"))).anySatisfy(m -> assertThat(m).containsEntry("signature", "deleted()").containsEntry("action", "DELETED"))
                .anySatisfy(m -> assertThat(m).containsEntry("signature", "newName()").containsEntry("action", "RENAMED"))
                .anySatisfy(m -> assertThat(m).containsEntry("signature", "added()").containsEntry("action", "ADDED"));
        assertThat(Values.objects(diff.get("changedMethods"))).noneMatch(m -> "stable()".equals(m.get("signature")));
        var impact = new BlastRadiusService(new CallGraphBuilder()).analyze(diff, oldAst, newAst, () -> { });
        assertThat(Values.objects(impact.get("affectedEndpoints"))).anySatisfy(e -> assertThat(e).containsEntry("path", "/removed").containsEntry("sourceVersion", "BASELINE"));
        assertThat(Values.objects(impact.get("affectedEndpoints"))).extracting(e -> e.get("path")).doesNotContain("/stable");
        assertThat(Values.objects(diff.get("files"))).singleElement().satisfies(file -> assertThat(file.get("patch").toString()).contains("中文/Api.java", "@@"));
    }

    @Test void cyclicDispatchAndAmbiguousOverloadsPreserveEveryPossibleImplementation() {
        var ast = ast("Graph.java", """
                package shop;
                interface Port { int hit(int id); }
                class One implements Port { public int hit(int id) { return second(id); } int second(int id) { return hit(id); } }
                class Two implements Port { public int hit(int id) { return 2; } }
                class Overloads { void call(Integer x) {} void call(String x) {} }
                @RestController class Api {
                    Port port; Overloads overloads;
                    @GetMapping("/go") int go() { overloads.call(null); return port.hit(1); }
                }
                """);
        var graph = new CallGraphBuilder().build(ast, () -> { });
        assertThat(Values.objects(graph.get("edges"))).filteredOn(e -> e.get("from").toString().contains("Api#go()"))
                .extracting(e -> e.get("to")).contains("Graph.java::shop.One#hit(int)", "Graph.java::shop.Two#hit(int)", "Graph.java::shop.Overloads#call(Integer)", "Graph.java::shop.Overloads#call(String)");
        Map<String, Object> change = Map.of("changedMethods", List.of(Map.of("beforeNodeId", "Graph.java::shop.One#second(int)", "afterNodeId", "Graph.java::shop.One#second(int)")), "files", List.of(), "classChanges", List.of());
        var result = new BlastRadiusService(new CallGraphBuilder()).analyze(change, ast, ast, () -> { });
        assertThat(Values.objects(result.get("affectedEndpoints"))).extracting(e -> e.get("path")).containsOnly("/go");
    }

    @Test void sameNameVariablesInSeparateScopesMustNotDiscardOneRuntimePossibility() {
        var ast = ast("Scopes.java", """
                class First { void invoke() {} }
                class Second { void invoke() {} }
                class Caller {
                    void dispatch(boolean flag) {
                        if (flag) { First worker = new First(); worker.invoke(); }
                        else { Second worker = new Second(); worker.invoke(); }
                    }
                }
                """);
        var graph = new CallGraphBuilder().build(ast, () -> { });
        assertThat(Values.objects(graph.get("edges"))).extracting(e -> e.get("to")).contains("Scopes.java::First#invoke()", "Scopes.java::Second#invoke()");
    }

    @Test void duplicateQualifiedClassNamesRetainTheirSourcePathsAndDynamicCallsRemainVisible() {
        var first = ast("module-a/Port.java", "package shop; class Port { void run() {} }");
        var second = ast("module-b/Port.java", "package shop; class Port { void run() {} }");
        var caller = ast("Api.java", "package shop; class Api { Port port; void call() { port.run(); factory().run(); } Object factory() { return null; } }");
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String key : JavaCodeParser.empty().keySet()) { List<Map<String, Object>> values = new ArrayList<>(Values.objects(first.get(key))); values.addAll(Values.objects(second.get(key))); values.addAll(Values.objects(caller.get(key))); merged.put(key, values); }
        var graph = new CallGraphBuilder().build(merged, () -> { });
        assertThat(Values.objects(graph.get("edges"))).extracting(e -> e.get("to")).contains("module-a/Port.java::shop.Port#run()", "module-b/Port.java::shop.Port#run()");
        assertThat(Values.objects(graph.get("unresolvedCalls"))).anySatisfy(call -> assertThat(call).containsEntry("receiver", "factory()"));
    }
    @Test void constructorsAndMethodReferencesParticipateInImpactInsteadOfDisappearing() {
        String before = """
                package shop;
                class Worker {
                    int state;
                    Worker() { state = 1; }
                    int run() { return state; }
                }
                @RestController class Api {
                    Worker worker;
                    @GetMapping("/create") int create() { return new Worker().run(); }
                    @GetMapping("/callback") Runnable callback() { return worker::run; }
                }
                """;
        String after = before.replace("state = 1", "state = 2").replace("return state", "return state + 1");
        var oldAst = ast("Work.java", before); var newAst = ast("Work.java", after);
        var graph = new CallGraphBuilder().build(newAst, () -> { });
        assertThat(Values.objects(graph.get("edges"))).anySatisfy(edge -> assertThat(edge).containsEntry("from", "Work.java::shop.Api#create()").containsEntry("to", "Work.java::shop.Worker#<init>()"));
        assertThat(Values.objects(graph.get("edges"))).anySatisfy(edge -> assertThat(edge).containsEntry("from", "Work.java::shop.Api#callback()").containsEntry("to", "Work.java::shop.Worker#run()"));
        var diff = new GitDiffAnalyzer().compare(List.of(SourceFile.text("BASELINE", "Work.java", before)), List.of(SourceFile.text("BACKEND", "Work.java", after)), oldAst, newAst, () -> { });
        var result = new BlastRadiusService(new CallGraphBuilder()).analyze(diff, oldAst, newAst, () -> { });
        assertThat(Values.objects(result.get("affectedEndpoints"))).extracting(endpoint -> endpoint.get("path")).contains("/create", "/callback");
    }
    @Test void ordinaryClassNamedMethodDoesNotCollideWithOrBecomeAConstructorCall() {
        String before = """
                class Worker {
                    Worker() { }
                    void Worker() { consume(); }
                    void consume() { }
                }
                @RestController class Api {
                    Worker worker;
                    @GetMapping("/create") Worker create() { return new Worker(); }
                    @GetMapping("/invoke") void invoke() { worker.Worker(); }
                }
                """;
        String after = before.replace("void Worker() { consume(); }", "void Worker() { consume(); consume(); }");
        var oldAst = ast("Names.java", before); var newAst = ast("Names.java", after);
        var graph = new CallGraphBuilder().build(newAst, () -> { });
        assertThat(Values.objects(graph.get("nodes"))).extracting(n -> n.get("id")).doesNotHaveDuplicates();
        assertThat(Values.objects(graph.get("edges"))).filteredOn(e -> e.get("from").equals("Names.java::Api#create()"))
                .extracting(e -> e.get("to")).containsExactly("Names.java::Worker#<init>()");
        assertThat(Values.objects(graph.get("edges"))).filteredOn(e -> e.get("from").equals("Names.java::Api#invoke()"))
                .extracting(e -> e.get("to")).containsExactly("Names.java::Worker#Worker()");
        var diff = new GitDiffAnalyzer().compare(List.of(SourceFile.text("BASELINE", "Names.java", before)), List.of(SourceFile.text("BACKEND", "Names.java", after)), oldAst, newAst, () -> { });
        var impact = new BlastRadiusService(new CallGraphBuilder()).analyze(diff, oldAst, newAst, () -> { });
        assertThat(Values.objects(impact.get("affectedEndpoints"))).extracting(e -> e.get("path")).containsOnly("/invoke");
    }

    @Test void identicalBodiesAcrossDifferentOwnersOrParameterTypesAreNotClaimedAsRenames() {
        String before = """
                class First {
                    int previous(int value) { return 1; }
                }
                class Second {
                }
                """;
        List<String> versions = List.of("""
                class First {
                }
                class Second {
                    int next(int value) { return 1; }
                }
                """, before.replace("previous(int", "next(String"));
        for (String after : versions) {
            var diff = new GitDiffAnalyzer().compare(List.of(SourceFile.text("BASELINE", "Owners.java", before)), List.of(SourceFile.text("BACKEND", "Owners.java", after)), ast("Owners.java", before), ast("Owners.java", after), () -> { });
            assertThat(Values.objects(diff.get("changedMethods"))).extracting(m -> m.get("action")).containsExactlyInAnyOrder("DELETED", "ADDED");
        }
    }

    private Map<String, Object> ast(String path, String text) {
        List<SourceDiagnostic> diagnostics = new ArrayList<>(); var parsed = new JavaCodeParser().parse(path, text, diagnostics);
        assertThat(diagnostics).isEmpty(); return new LinkedHashMap<>(parsed);
    }
}
