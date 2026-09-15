package com.aitest.exchange.codegen;

import java.util.List;
import java.util.Map;

/** Syntax only. Uploaded recorder source is never compiled, loaded or evaluated. */
final class RecorderSyntax {
    private RecorderSyntax() { }
    sealed interface Expr permits Literal, Name, Member, Call, ObjectValue, Lambda, Construct, Unsupported { int line(); }
    record Literal(Object value, int line) implements Expr { }
    record Name(String value, int line) implements Expr { }
    record Member(Expr receiver, String name, int line) implements Expr { }
    record Call(Expr receiver, String method, List<Expr> arguments, int line) implements Expr { }
    record ObjectValue(Map<String, Expr> fields, int line) implements Expr { }
    record Lambda(List<Statement> body, int line) implements Expr { }
    record Construct(String type, List<Expr> arguments, int line) implements Expr { }
    record Unsupported(String reason, int line) implements Expr { }
    record Statement(String variable, Expr expression, int line) { }
}
