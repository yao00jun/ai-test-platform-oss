package com.aitest.exchange.codegen;

import com.aitest.exchange.ExchangeIssue;
import java.util.*;
import static com.aitest.exchange.codegen.RecorderSyntax.*;

/** Bounded parser for recorder statements, literals, calls, options and async test wrappers. */
final class JavascriptRecorderParser {
    private record Token(String kind, String text, Object value, int line) { }
    private static final class SyntaxFailure extends RuntimeException {
        final int line;
        SyntaxFailure(int line, String message) { super(message); this.line = line; }
    }
    private final String source;
    private final List<ExchangeIssue> errors;
    private List<Token> tokens;
    private int at;
    private int depth;
    JavascriptRecorderParser(String source, List<ExchangeIssue> errors) { this.source = source; this.errors = errors; }

    List<Statement> parse(String text) {
        try { tokens = lex(text); }
        catch (SyntaxFailure failure) { issue(failure); return List.of(); }
        return statements(false);
    }
    private List<Statement> statements(boolean block) {
        List<Statement> result = new ArrayList<>();
        while (!is("<eof>") && !(block && is("}"))) {
            int start = at, line = peek().line();
            try {
                if (take(";")) continue;
                if (take("import")) {
                    // Imports are syntax declarations, never loaded. Their later uses are still validated.
                    while (!is("<eof>") && !peek().kind().equals("string")) next();
                    if (peek().kind().equals("string")) next(); else throw failure("import 缺少模块名称");
                    take(";"); continue;
                }
                String variable = null;
                if (take("const") || take("let") || take("var")) {
                    if (take("{")) {
                        List<String> names = new ArrayList<>();
                        do { names.add(identifier()); } while (take(","));
                        need("}"); variable = "{" + String.join(",", names) + "}";
                    } else variable = identifier();
                    // A recorder may annotate Page or Locator; no runtime expression is discarded.
                    if (take(":")) { identifier(); while (take(".")) identifier(); }
                    need("=");
                }
                Expr expression = expression();
                if (!take(";") && !is("}") && !is("<eof>") && peek().line() <= tokens.get(Math.max(0, at - 1)).line()) throw failure("语句尾部包含不支持的运算或表达式");
                result.add(new Statement(variable, expression, line));
            } catch (SyntaxFailure failure) {
                issue(failure);
                // Preserve successfully parsed neighbours in the invalid preview.
                while (!is("<eof>") && !is(";") && !(block && is("}")) && (at == start || peek().line() <= line)) next();
                take(";");
            }
            if (at == start) next();
        }
        if (block) need("}");
        return result;
    }
    private Expr expression() {
        if (++depth > 80) throw failure("表达式嵌套超过 80 层");
        try {
            take("await"); boolean async = take("async");
            Expr value;
            if (lambdaAhead()) value = lambda();
            else if (async) throw failure("async 仅用于受支持的录制函数");
            else if (take("(")) { value = expression(); need(")"); }
            else if (take("{")) {
                int row = tokens.get(at - 1).line(); Map<String, Expr> fields = new LinkedHashMap<>();
                if (!take("}")) {
                    do {
                        Token key = next();
                        if (!Set.of("id", "string").contains(key.kind())) throw failure("选项名称必须为静态字符串");
                        need(":");
                        if (fields.putIfAbsent(key.text(), expression()) != null) throw failure("选项中存在重复键");
                    } while (take(",") && !is("}"));
                    need("}");
                }
                value = new ObjectValue(fields, row);
            } else {
                Token token = next();
                value = switch (token.kind()) {
                    case "string", "number" -> new Literal(token.value(), token.line());
                    case "id" -> switch (token.text()) {
                        case "true" -> new Literal(true, token.line());
                        case "false" -> new Literal(false, token.line());
                        case "null" -> new Literal(null, token.line());
                        default -> new Name(token.text(), token.line());
                    };
                    default -> throw new SyntaxFailure(token.line(), "参数需要静态文字或支持的调用；正则、数组、模板插值和动态表达式请手工转换");
                };
            }
            while (true) {
                if (take(".")) {
                    String name = identifier(); int row = value.line();
                    value = is("(") ? new Call(value, name, arguments(), row) : new Member(value, name, row);
                } else if (is("(")) {
                    var arguments = arguments();
                    value = value instanceof Name name ? new Call(null, name.value(), arguments, value.line())
                            : value instanceof Member member ? new Call(member.receiver(), member.name(), arguments, value.line())
                            : new Call(value, "<invoke>", arguments, value.line());
                } else break;
            }
            return value;
        } finally { depth--; }
    }
    private boolean lambdaAhead() {
        if (peek().kind().equals("id") && at + 1 < tokens.size() && tokens.get(at + 1).text().equals("=>")) return true;
        if (!is("(")) return false;
        int nesting = 0;
        for (int index = at; index < tokens.size(); index++) {
            String text = tokens.get(index).text();
            if (text.equals("(")) nesting++;
            if (text.equals(")") && --nesting == 0) return index + 1 < tokens.size() && tokens.get(index + 1).text().equals("=>");
        }
        return false;
    }
    private Expr lambda() {
        int row = peek().line();
        if (take("(")) {
            while (!is(")") && !is("<eof>")) {
                Token parameter = next();
                if (!(parameter.kind().equals("id") || Set.of("{", "}", ",", ":", "?").contains(parameter.text()))) throw failure("录制函数参数不支持默认值或动态表达式");
            }
            need(")");
        } else identifier();
        need("=>"); need("{");
        return new Lambda(statements(true), row);
    }
    private List<Expr> arguments() {
        need("("); List<Expr> result = new ArrayList<>();
        if (take(")")) return result;
        do { result.add(expression()); } while (take(",") && !is(")"));
        need(")"); return result;
    }
    private String identifier() { Token token = next(); if (!token.kind().equals("id")) throw new SyntaxFailure(token.line(), "此处需要标识符"); return token.text(); }
    private Token peek() { return tokens.get(Math.min(at, tokens.size() - 1)); }
    private Token next() { Token token = peek(); if (at < tokens.size() - 1) at++; return token; }
    private boolean is(String text) { return peek().text().equals(text); }
    private boolean take(String text) { if (!is(text)) return false; next(); return true; }
    private void need(String text) { if (!take(text)) throw failure("语法结构不完整或使用了不支持的语法（需要 " + text + "）"); }
    private SyntaxFailure failure(String text) { return new SyntaxFailure(peek().line(), text); }
    private void issue(SyntaxFailure failure) { errors.add(new ExchangeIssue(source, failure.line, "$script", failure.getMessage())); }

    private static List<Token> lex(String text) {
        List<Token> result = new ArrayList<>(); int index = 0, row = 1, nesting = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (Character.isWhitespace(c)) { if (c == '\n') row++; index++; continue; }
            if (c == '/' && index + 1 < text.length() && text.charAt(index + 1) == '/') { while (index < text.length() && text.charAt(index) != '\n') index++; continue; }
            if (c == '/' && index + 1 < text.length() && text.charAt(index + 1) == '*') {
                index += 2; boolean closed = false;
                while (index + 1 < text.length()) { if (text.charAt(index) == '*' && text.charAt(index + 1) == '/') { index += 2; closed = true; break; } if (text.charAt(index++) == '\n') row++; }
                if (!closed) throw new SyntaxFailure(row, "注释没有结束"); continue;
            }
            int start = index, line = row;
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c; index++; StringBuilder value = new StringBuilder(); boolean closed = false;
                while (index < text.length()) {
                    char current = text.charAt(index++);
                    if (current == quote) { closed = true; break; }
                    if (quote == '`' && current == '$' && index < text.length() && text.charAt(index) == '{') throw new SyntaxFailure(line, "不支持模板字符串插值；请改为平台变量或静态文字");
                    if (current == '\n') { row++; if (quote != '`') throw new SyntaxFailure(line, "字符串中存在未转义的换行"); }
                    if (current != '\\') { value.append(current); continue; }
                    if (index >= text.length()) break;
                    char escaped = text.charAt(index++);
                    if (escaped == '\n') { row++; continue; }
                    if (escaped == '\r') { if (index < text.length() && text.charAt(index) == '\n') { index++; row++; } continue; }
                    if (escaped == 'u' || escaped == 'x') {
                        int digits = escaped == 'u' ? 4 : 2;
                        if (index + digits > text.length()) throw new SyntaxFailure(line, "字符串转义不完整");
                        try { value.append((char) Integer.parseInt(text.substring(index, index + digits), 16)); }
                        catch (NumberFormatException invalid) { throw new SyntaxFailure(line, "字符串转义无效"); }
                        index += digits;
                    } else value.append(switch (escaped) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case 'b' -> '\b'; case 'f' -> '\f'; case 'v' -> '\u000b'; case '0' -> '\0'; default -> escaped; });
                }
                if (!closed) throw new SyntaxFailure(line, "字符串没有结束");
                result.add(new Token("string", value.toString(), value.toString(), line));
            } else if (Character.isJavaIdentifierStart(c)) {
                index++; while (index < text.length() && Character.isJavaIdentifierPart(text.charAt(index))) index++;
                result.add(new Token("id", text.substring(start, index), null, line));
            } else if (Character.isDigit(c) || c == '-' && index + 1 < text.length() && Character.isDigit(text.charAt(index + 1))) {
                index++; while (index < text.length() && (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.')) index++;
                String number = text.substring(start, index);
                try { result.add(new Token("number", number, Double.valueOf(number), line)); }
                catch (NumberFormatException invalid) { throw new SyntaxFailure(line, "数字格式无效"); }
            } else {
                if ("({[".indexOf(c) >= 0 && ++nesting > 100) throw new SyntaxFailure(line, "语法嵌套超过 100 层");
                if (")}]".indexOf(c) >= 0) nesting--;
                String symbol = c == '=' && index + 1 < text.length() && text.charAt(index + 1) == '>' ? "=>" : String.valueOf(c);
                result.add(new Token("symbol", symbol, null, line)); index += symbol.length();
            }
            if (result.size() > 200_000) throw new SyntaxFailure(row, "录制语句过多，请拆分文件");
        }
        result.add(new Token("eof", "<eof>", null, row)); return result;
    }
}
