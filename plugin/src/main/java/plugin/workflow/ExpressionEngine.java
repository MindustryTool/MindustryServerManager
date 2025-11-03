package plugin.workflow;

import java.util.*;
import java.util.stream.Collectors;

import mindustry.Vars;

import java.lang.reflect.*;
import java.time.*;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import java.util.Set;

public class ExpressionEngine {

    static class Lexer {
        private final String input;
        private int pos = 0;
        private int length;
        private int line = 1;
        private int col = 1;

        Lexer(String input) {
            this.input = input == null ? "" : input;
            this.length = this.input.length();
        }

        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (true) {
                skipWhitespace();

                if (eof())
                    break;

                char c = peek();

                if (isDigit(c) || (c == '.' && pos + 1 < length && isDigit(input.charAt(pos + 1)))) {
                    out.add(number());
                    continue;
                }

                if (c == '"' || c == '\'') {
                    out.add(string());
                    continue;
                }

                if (isIdentifierStart(c)) {
                    out.add(identifier());
                    continue;
                }
                // Operators/punctuators
                // Multi-char operators
                String two = pos + 1 < length ? input.substring(pos, pos + 2) : null;
                String three = pos + 2 < length ? input.substring(pos, pos + 3) : null;
                if (three != null && ("===".equals(three) || "!==".equals(three))) {
                    out.add(new Token(TokenType.OP, three, line, col));
                    advance(3);
                    continue;
                }

                if (two != null && ("==".equals(two) || "!=".equals(two) || ">=".equals(two) || "<=".equals(two)
                        || "&&".equals(two) || "||".equals(two) || "<<".equals(two) || ">>".equals(two)
                        || "+=".equals(two) || "-=".equals(two) || "*=".equals(two) || "/=".equals(two)
                        || "%=".equals(two))) {
                    out.add(new Token(TokenType.OP, two, line, col));
                    advance(2);
                    continue;
                }

                switch (c) {
                    case '(':
                    case ')':
                    case '{':
                    case '}':
                    case '[':
                    case ']':
                    case ',':
                    case ':':
                    case ';':
                    case '.':
                    case '?':
                        out.add(new Token(TokenType.PUNC, String.valueOf(c), line, col));
                        advance();
                        continue;
                    case '+':
                    case '-':
                    case '*':
                    case '/':
                    case '%':
                    case '^':
                    case '<':
                    case '>':
                    case '&':
                    case '|':
                    case '!':
                    case '~':
                    case '=':
                        out.add(new Token(TokenType.OP, String.valueOf(c), line, col));
                        advance();
                        continue;
                    default:
                        throw new ParseException("Unrecognized character: '" + c + "'", line, col);
                }
            }
            out.add(new Token(TokenType.EOF, "", line, col));
            return out;
        }

        private Token number() {
            int startLine = line, startCol = col;
            int s = pos;
            boolean seenDot = false;
            if (peek() == '0' && pos + 1 < length && (input.charAt(pos + 1) == 'x' || input.charAt(pos + 1) == 'X')) {
                // hex
                advance(2);
                while (!eof() && isHex(peek()))
                    advance();
                String text = input.substring(s, pos);
                return new Token(TokenType.NUMBER, text, startLine, startCol);
            }

            while (!eof() && (isDigit(peek()) || peek() == '.')) {
                if (peek() == '.') {
                    if (seenDot)
                        break; // second dot stops
                    seenDot = true;
                }
                advance();
            }

            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                advance();
                if (!eof() && (peek() == '+' || peek() == '-'))
                    advance();
                while (!eof() && isDigit(peek()))
                    advance();
            }

            String text = input.substring(s, pos);
            return new Token(TokenType.NUMBER, text, startLine, startCol);
        }

        private Token string() {
            int startLine = line, startCol = col;
            char quote = peek();
            advance();
            StringBuilder sb = new StringBuilder();
            while (!eof()) {
                char c = peek();
                if (c == quote) {
                    advance();
                    break;
                }
                if (c == '\\') {
                    advance();
                    if (eof())
                        throw new ParseException("Unterminated string", startLine, startCol);
                    char esc = peek();
                    switch (esc) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '"':
                            sb.append('"');
                            break;
                        case '\'':
                            sb.append('\'');
                            break;
                        case 'u':
                            advance();
                            String hex = readExact(4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            sb.append(esc);
                    }
                    advance();
                } else {
                    sb.append(c);
                    advance();
                }
            }
            return new Token(TokenType.STRING, sb.toString(), startLine, startCol);
        }

        private Token identifier() {
            int start = pos, sLine = line, sCol = col;
            advance();

            while (!eof() && isIdentifierPart(peek()))
                advance();

            String text = input.substring(start, pos);
            if ("true".equals(text) || "false".equals(text))
                return new Token(TokenType.BOOLEAN, text, sLine, sCol);

            if ("null".equals(text))
                return new Token(TokenType.NULL, text, sLine, sCol);

            return new Token(TokenType.IDENT, text, sLine, sCol);
        }

        private void skipWhitespace() {
            while (!eof()) {
                char c = peek();
                if (c == ' ' || c == '\t' || c == '\r') {
                    advance();
                    continue;
                }

                if (c == '\n') {
                    advance();
                    line++;
                    col = 1;
                    continue;
                }

                if (c == '/' && pos + 1 < length && input.charAt(pos + 1) == '/') {
                    // line comment
                    advance(2);
                    while (!eof() && peek() != '\n')
                        advance();
                    continue;
                }

                if (c == '/' && pos + 1 < length && input.charAt(pos + 1) == '*') {
                    // block comment
                    advance(2);
                    while (!eof()) {
                        if (peek() == '*' && pos + 1 < length && input.charAt(pos + 1) == '/') {
                            advance(2);
                            break;
                        }
                        if (peek() == '\n') {
                            advance();
                            line++;
                            col = 1;
                        } else
                            advance();
                    }
                    continue;
                }
                break;
            }
        }

        private char peek() {
            return input.charAt(pos);
        }

        private boolean eof() {
            return pos >= length;
        }

        private void advance() {
            pos++;
            col++;
        }

        private void advance(int n) {
            for (int i = 0; i < n; i++)
                advance();
        }

        private void advanceTo(int p) {
            while (pos < p)
                advance();
        }

        private boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private boolean isHex(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        private boolean isIdentifierStart(char c) {
            return Character.isLetter(c) || c == '_' || c == '$';
        }

        private boolean isIdentifierPart(char c) {
            return isIdentifierStart(c) || isDigit(c);
        }

        private String readExact(int n) {
            if (pos + n > length)
                throw new ParseException("Unexpected EOF in escape", line, col);
            String s = input.substring(pos, pos + n);
            advance(n);
            return s;
        }
    }

    enum TokenType {
        IDENT, NUMBER, STRING, PUNC, OP, BOOLEAN, NULL, EOF
    }

    static class Token {
        final TokenType type;
        final String text;
        final int line, col;

        Token(TokenType type, String text, int line, int col) {
            this.type = type;
            this.text = text;
            this.line = line;
            this.col = col;
        }

        public String toString() {
            return type + "('" + text + "')@" + line + ":" + col;
        }
    }

    // ----------------------------- Parser ---------------------------------

    static class Parser {
        private final List<Token> tokens;
        private int pos = 0;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Expr parseExpression() {
            Expr e = expression(0);
            expect(TokenType.EOF);
            return e;
        }

        // precedence climbing
        private Expr expression(int minPrec) {
            Expr left = parsePrimary();

            while (true) {
                Token t = peek();

                if (t.type == TokenType.OP
                        || (t.type == TokenType.PUNC && ("?".equals(t.text) || ":".equals(t.text)))) {
                    String op = t.text;
                    OperatorInfo info = Operators.get(op);

                    if (info == null)
                        break;

                    int prec = info.prec;
                    boolean rightAssoc = info.rightAssoc;
                    if (prec < minPrec)
                        break;

                    advance(); // consume operator
                    int nextMin = rightAssoc ? prec : prec + 1;
                    if (op.equals("?")) {
                        // ternary ? :
                        Expr thenExpr = expression(0);
                        expectPunc(":");
                        Expr elseExpr = expression(0);
                        left = new ConditionalExpr(left, thenExpr, elseExpr);
                        continue;
                    }
                    Expr right = expression(nextMin);
                    left = new BinaryExpr(op, left, right);
                } else {
                    break;
                }
            }
            return left;
        }

        private Expr parsePrimary() {
            Token t = peek();
            if (t.type == TokenType.NUMBER) {
                advance();
                return new LiteralExpr(parseNumber(t.text));
            }

            if (t.type == TokenType.STRING) {
                advance();
                return new LiteralExpr(t.text);
            }

            if (t.type == TokenType.BOOLEAN) {
                advance();
                return new LiteralExpr(Boolean.parseBoolean(t.text));
            }

            if (t.type == TokenType.NULL) {
                advance();
                return new LiteralExpr(null);
            }

            if (t.type == TokenType.IDENT) {
                advance();
                Expr node = new IdentifierExpr(t.text);
                node = finishPostfix(node);
                return node;
            }

            if (t.type == TokenType.PUNC && "(".equals(t.text)) {
                advance();
                Expr inner = expression(0);
                expectPunc(")");
                Expr node = finishPostfix(inner);
                return node;
            }

            if (t.type == TokenType.OP
                    && ("+".equals(t.text) || "-".equals(t.text) || "!".equals(t.text) || "~".equals(t.text))) {
                advance();
                Expr operand = expression(Operators.get(t.text).prec);
                return new UnaryExpr(t.text, operand);
            }

            throw new ParseException("Unexpected token: " + t, t.line, t.col);
        }

        private Expr finishPostfix(Expr left) {
            while (true) {
                Token t = peek();
                if (t.type == TokenType.PUNC && "(".equals(t.text)) {
                    advance(); // consume (
                    List<Arg> args = new ArrayList<>();

                    if (!(peek().type == TokenType.PUNC && ")".equals(peek().text))) {
                        while (true) {
                            // simple positional args only for now
                            Expr a = expression(0);
                            args.add(new Arg(null, a));

                            if (peek().type == TokenType.PUNC && ",".equals(peek().text)) {
                                advance();
                                continue;
                            }
                            break;
                        }
                    }
                    expectPunc(")");
                    left = new CallExpr(left, args);
                    continue;
                }

                if (t.type == TokenType.PUNC && ".".equals(t.text)) {
                    advance();
                    Token id = expect(TokenType.IDENT);
                    left = new MemberExpr(left, new LiteralExpr(id.text), false);
                    continue;
                }

                if (t.type == TokenType.PUNC && "[".equals(t.text)) {
                    advance();
                    Expr idx = expression(0);
                    expectPunc("]");
                    left = new IndexExpr(left, idx);
                    continue;
                }
                break;
            }
            return left;
        }

        private Token expect(TokenType type) {
            Token t = peek();
            if (t.type != type)
                throw new ParseException("Expected " + type + " but got " + t, t.line, t.col);
            advance();
            return t;
        }

        private void expectPunc(String txt) {
            Token t = peek();
            if (t.type != TokenType.PUNC || !txt.equals(t.text))
                throw new ParseException("Expected '" + txt + "' but got " + t, t.line, t.col);
            advance();
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token advance() {
            return tokens.get(pos++);
        }

        private double parseNumber(String s) {
            if (s.startsWith("0x") || s.startsWith("0X"))
                return Integer.parseInt(s.substring(2), 16);
            return Double.parseDouble(s);
        }
    }

    // ----------------------------- Operators -------------------------------
    static class OperatorInfo {
        final int prec;
        final boolean rightAssoc;

        OperatorInfo(int prec, boolean rightAssoc) {
            this.prec = prec;
            this.rightAssoc = rightAssoc;
        }
    }

    static class Operators {
        private static final Map<String, OperatorInfo> map = new HashMap<>();
        static {
            map.put("?", new OperatorInfo(1, true));
            map.put("=", new OperatorInfo(2, true));
            map.put("||", new OperatorInfo(3, false));
            map.put("&&", new OperatorInfo(4, false));
            map.put("|", new OperatorInfo(5, false));
            map.put("^", new OperatorInfo(6, false));
            map.put("&", new OperatorInfo(7, false));
            map.put("==", new OperatorInfo(8, false));
            map.put("!=", new OperatorInfo(8, false));
            map.put("<", new OperatorInfo(9, false));
            map.put(">", new OperatorInfo(9, false));
            map.put("<=", new OperatorInfo(9, false));
            map.put(">=", new OperatorInfo(9, false));
            map.put("<<", new OperatorInfo(10, false));
            map.put(">>", new OperatorInfo(10, false));
            map.put("+", new OperatorInfo(11, false));
            map.put("-", new OperatorInfo(11, false));
            map.put("*", new OperatorInfo(12, false));
            map.put("/", new OperatorInfo(12, false));
            map.put("%", new OperatorInfo(12, false));
            map.put("!", new OperatorInfo(13, false));
            map.put("~", new OperatorInfo(13, false));
            map.put("^", new OperatorInfo(14, true));
        }

        static OperatorInfo get(String op) {
            return map.get(op);
        }
    }

    // ----------------------------- AST ------------------------------------
    interface Expr {
        Object eval(EvalContext ctx);
    }

    static class LiteralExpr implements Expr {
        final Object value;

        LiteralExpr(Object value) {
            this.value = value;
        }

        public Object eval(EvalContext ctx) {
            return value;
        }

        public String toString() {
            return String.valueOf(value);
        }
    }

    static class IdentifierExpr implements Expr {
        final String name;

        IdentifierExpr(String name) {
            this.name = name;
        }

        public Object eval(EvalContext ctx) {
            return ctx.resolve(name);
        }

        public String toString() {
            return name;
        }
    }

    static class UnaryExpr implements Expr {
        final String op;
        final Expr expr;

        UnaryExpr(String op, Expr expr) {
            this.op = op;
            this.expr = expr;
        }

        public Object eval(EvalContext ctx) {
            Object v = expr.eval(ctx);
            switch (op) {
                case "-":
                    return asNumber(v) * -1;
                case "+":
                    return asNumber(v);
                case "!":
                    return !asBoolean(v);
                case "~":
                    return ~(asLong(v));
                default:
                    throw new EvalException("Unknown unary: " + op);
            }
        }

        public String toString() {
            return "(" + op + expr + ")";
        }
    }

    static class BinaryExpr implements Expr {
        final String op;
        final Expr left, right;

        BinaryExpr(String op, Expr left, Expr right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        public Object eval(EvalContext ctx) {
            // short-circuit for && ||
            if ("&&".equals(op)) {
                Object lv = left.eval(ctx);
                if (!asBoolean(lv))
                    return false;
                return asBoolean(right.eval(ctx));
            }
            if ("||".equals(op)) {
                Object lv = left.eval(ctx);
                if (asBoolean(lv))
                    return true;
                return asBoolean(right.eval(ctx));
            }
            Object a = left.eval(ctx);
            Object b = right.eval(ctx);
            switch (op) {
                case "+":
                    if (a instanceof String || b instanceof String)
                        return String.valueOf(a) + String.valueOf(b);
                    return asNumber(a) + asNumber(b);
                case "-":
                    return asNumber(a) - asNumber(b);
                case "*":
                    return asNumber(a) * asNumber(b);
                case "/":
                    return asNumber(a) / asNumber(b);
                case "%":
                    return asNumber(a) % asNumber(b);
                case "==":
                    return equalsLoose(a, b);
                case "!=":
                    return !equalsLoose(a, b);
                case "<":
                    return asNumber(a) < asNumber(b);
                case ">":
                    return asNumber(a) > asNumber(b);
                case "<=":
                    return asNumber(a) <= asNumber(b);
                case ">=":
                    return asNumber(a) >= asNumber(b);
                case "&":
                    return asLong(a) & asLong(b);
                case "|":
                    return asLong(a) | asLong(b);
                case "^":
                    return asLong(a) ^ asLong(b);
                case "<<":
                    return asLong(a) << asLong(b);
                case ">>":
                    return asLong(a) >> asLong(b);
                default:
                    throw new EvalException("Unsupported binary operator: " + op);
            }
        }

        public String toString() {
            return "(" + left + " " + op + " " + right + ")";
        }
    }

    static class ConditionalExpr implements Expr {
        final Expr test, thenExpr, elseExpr;

        ConditionalExpr(Expr test, Expr thenExpr, Expr elseExpr) {
            this.test = test;
            this.thenExpr = thenExpr;
            this.elseExpr = elseExpr;
        }

        public Object eval(EvalContext ctx) {
            return asBoolean(test.eval(ctx)) ? thenExpr.eval(ctx) : elseExpr.eval(ctx);
        }

        public String toString() {
            return "(" + test + " ? " + thenExpr + " : " + elseExpr + ")";
        }
    }

    static class CallExpr implements Expr {
        final Expr callee;
        final List<Arg> args;

        CallExpr(Expr callee, List<Arg> args) {
            this.callee = callee;
            this.args = args;
        }

        public Object eval(EvalContext ctx) {
            Object f = callee.eval(ctx);
            List<Object> avals = new ArrayList<>();
            for (Arg a : args)
                avals.add(a.expr.eval(ctx));
            // function value can be ExprFunction or Java Method/Function
            if (f instanceof ExprFunction) {
                return ((ExprFunction) f).call(avals, ctx);
            }
            if (f instanceof MethodBound) {
                try {
                    return ((MethodBound) f).invoke(avals.toArray());
                } catch (Exception ex) {
                    throw new EvalException("Error calling bound method: " + ex.getMessage(), ex);
                }
            }
            if (f instanceof java.util.function.Function) {
                return ((java.util.function.Function) f).apply(avals);
            }
            throw new EvalException("Value is not callable: " + f);
        }

        public String toString() {
            return callee + "(...)";
        }
    }

    static class MemberExpr implements Expr {
        final Expr object;
        final Expr property;
        final boolean computed;

        MemberExpr(Expr object, Expr property, boolean computed) {
            this.object = object;
            this.property = property;
            this.computed = computed;
        }

        public Object eval(EvalContext ctx) {
            Object o = object.eval(ctx);
            Object p = property.eval(ctx);
            return ctx.getProperty(o, p);
        }

        public String toString() {
            return object + "." + property;
        }
    }

    static class IndexExpr implements Expr {
        final Expr object, index;

        IndexExpr(Expr object, Expr index) {
            this.object = object;
            this.index = index;
        }

        public Object eval(EvalContext ctx) {
            Object o = object.eval(ctx);
            Object i = index.eval(ctx);
            return ctx.getProperty(o, i);
        }

        public String toString() {
            return object + "[" + index + "]";
        }
    }

    static class Arg {
        final String name;
        final Expr expr;

        Arg(String name, Expr expr) {
            this.name = name;
            this.expr = expr;
        }
    }

    // ----------------------------- Evaluation helpers --------------------
    static double asNumber(Object v) {
        if (v == null)
            return 0.0;
        if (v instanceof Number)
            return ((Number) v).doubleValue();
        if (v instanceof Boolean)
            return (Boolean) v ? 1.0 : 0.0;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            throw new EvalException("Cannot convert to number: " + v);
        }
    }

    static long asLong(Object v) {
        if (v == null)
            return 0L;
        if (v instanceof Number)
            return ((Number) v).longValue();
        if (v instanceof Boolean)
            return (Boolean) v ? 1L : 0L;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return (long) asNumber(v);
        }
    }

    static boolean asBoolean(Object v) {
        if (v == null)
            return false;
        if (v instanceof Boolean)
            return (Boolean) v;
        if (v instanceof Number)
            return ((Number) v).doubleValue() != 0.0;
        if (v instanceof String)
            return !((String) v).isEmpty();
        return true;
    }

    static boolean equalsLoose(Object a, Object b) {
        if (a == b)
            return true;
        if (a == null || b == null)
            return false;
        if (a instanceof Number || b instanceof Number)
            return asNumber(a) == asNumber(b);
        return a.equals(b);
    }

    // ----------------------------- EvalContext ---------------------------
    public static class EvalContext {
        private final Deque<Map<String, Object>> scopes = new ArrayDeque<>();
        private final Map<String, ExprFunction> functions = new HashMap<>();
        private final Map<String, Object> values = new HashMap<>();

        public EvalContext() {
            pushScope(new HashMap<>());
        }

        public void pushScope(Map<String, Object> m) {
            scopes.push(m);
        }

        public void popScope() {
            if (scopes.size() <= 1)
                throw new IllegalStateException("Cannot pop global scope");
            scopes.pop();
        }

        public void setLocal(String name, Object val) {
            scopes.peek().put(name, val);
        }

        public void registerFunction(String name, ExprFunction fn) {
            functions.put(name, fn);
        }

        public void registerValue(String name, Object val) {
            values.put(name, val);
        }

        public Object resolve(String name) {
            // check locals then values then functions
            for (Map<String, Object> m : scopes) {
                if (m.containsKey(name))
                    return m.get(name);
            }
            if (values.containsKey(name))
                return values.get(name);
            if (functions.containsKey(name))
                return functions.get(name);
            // support dotted names in values
            if (name.contains(".")) {
                String[] parts = name.split("\\.");
                Object cur = resolve(parts[0]);
                for (int i = 1; i < parts.length; i++) {
                    cur = getProperty(cur, parts[i]);
                }
                return cur;
            }
            throw new EvalException("Undefined identifier: " + name);
        }

        public Object getProperty(Object target, Object prop) {
            if (target == null)
                return null;

            // Support static fields/methods when target is a Class reference
            if (target instanceof Class) {
                Class<?> cls = (Class<?>) target;
                String name = String.valueOf(prop);

                // try static field
                try {
                    Field f = cls.getField(name);
                    return f.get(null);
                } catch (Exception e) {
                    // ignore and continue
                }

                // try static method
                try {
                    Method m = findMethod(cls, name);
                    if (m != null)
                        return new MethodBound(null, m);
                } catch (Exception e) {
                    // ignore and continue
                }

                return null;
            }

            if (target instanceof Map)
                return ((Map) target).get(String.valueOf(prop));
            if (target instanceof List) {
                int idx = (int) asLong(prop);
                List l = (List) target;
                return idx >= 0 && idx < l.size() ? l.get(idx) : null;
            }
            if (target.getClass().isArray()) {
                int idx = (int) asLong(prop);
                Object[] arr = (Object[]) target;
                return idx >= 0 && idx < arr.length ? arr[idx] : null;
            }

            // Use reflection: try getter method or field
            String name = String.valueOf(prop);
            try {
                Method m = findMethod(target.getClass(), "get" + capitalize(name));
                if (m != null)
                    return m.invoke(target);
                Method m2 = findMethod(target.getClass(), name);
                if (m2 != null && m2.getParameterCount() == 0)
                    return m2.invoke(target);
            } catch (Exception e) {
            }

            try {
                Field f = target.getClass().getField(name);
                return f.get(target);
            } catch (Exception e) {
            }

            try {
                Method getm = findMethod(target.getClass(), "get");
                if (getm != null && getm.getParameterCount() == 1)
                    return getm.invoke(target, prop);
            } catch (Exception e) {
            }

            // finally, if property matches a function name, return a bound function
            try {
                Method m = findMethod(target.getClass(), name);
                if (m != null)
                    return new MethodBound(target, m);
            } catch (Exception e) {
            }

            return null;
        }

        private Method findMethod(Class<?> cls, String name) {
            for (Method m : cls.getMethods())
                if (m.getName().equals(name))
                    return m;
            return null;
        }

        private String capitalize(String s) {
            if (s == null || s.isEmpty())
                return s;
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    public interface ExprFunction {
        Object call(List<Object> args, EvalContext ctx);
    }

    static class MethodBound {
        final Object target;
        final Method method; // optional
        final Class<?> cls; // declaring class to search
        final String name; // method name

        MethodBound(Object target, Method method) {
            this.target = target;
            this.method = method;
            this.cls = method.getDeclaringClass();
            this.name = method.getName();
        }

        MethodBound(Object target, Class<?> cls, String name) {
            this.target = target;
            this.method = null;
            this.cls = cls;
            this.name = name;
        }

        Object invoke(Object[] args) throws Exception {
            Method m = selectMethod(cls, name, args);
            Object[] coerced = coerceArgs(m.getParameterTypes(), args);
            return m.invoke(target, coerced);
        }

        private static Method selectMethod(Class<?> cls, String name, Object[] args) {
            Method best = null;
            int bestScore = Integer.MAX_VALUE;

            for (Method m : cls.getMethods()) {
                if (!m.getName().equals(name))
                    continue;
                if (m.isVarArgs())
                    continue; // simple handling; add if you need varargs
                Class<?>[] params = m.getParameterTypes();
                if (params.length != args.length)
                    continue;

                int score = matchScore(params, args);
                if (score < bestScore) {
                    bestScore = score;
                    best = m;
                    if (score == 0)
                        break; // exact match
                }
            }

            if (best == null) {
                throw new IllegalArgumentException(
                        "No matching overload for " + name + " with " + args.length + " args");
            }
            return best;
        }

        private static int matchScore(Class<?>[] params, Object[] args) {
            int s = 0;
            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                Object a = args[i];

                if (p.isPrimitive())
                    p = primitiveWrapper(p);

                if (a == null) {
                    s += p.isPrimitive() ? 100 : 10;
                    continue;
                }

                Class<?> ac = a.getClass();
                if (p.equals(ac))
                    continue;
                if (p.isAssignableFrom(ac)) {
                    s += 1;
                    continue;
                }

                if (isNumeric(p) && (a instanceof Number || a instanceof String)) {
                    s += 2;
                    continue;
                }
                if (p == String.class) {
                    s += 3;
                    continue;
                }

                s += 50;
            }
            return s;
        }

        private static Object[] coerceArgs(Class<?>[] params, Object[] args) {
            Object[] out = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                out[i] = convert(args[i], params[i]);
            }
            return out;
        }

        private static Object convert(Object a, Class<?> param) {
            if (param.isPrimitive())
                param = primitiveWrapper(param);
            if (a == null)
                return null;
            if (param.isInstance(a))
                return a;

            if (param == String.class)
                return String.valueOf(a);

            if (Number.class.isAssignableFrom(param)) {
                double d = asNumber(a);
                if (param == Integer.class)
                    return (int) d;
                if (param == Long.class)
                    return (long) d;
                if (param == Double.class)
                    return d;
                if (param == Float.class)
                    return (float) d;
                if (param == Short.class)
                    return (short) d;
                if (param == Byte.class)
                    return (byte) d;
            }

            if (param == Boolean.class)
                return asBoolean(a);
            if (param == Character.class && a instanceof String && ((String) a).length() > 0) {
                return ((String) a).charAt(0);
            }
            return a;
        }

        private static boolean isNumeric(Class<?> c) {
            return c == Integer.class || c == Long.class || c == Double.class ||
                    c == Float.class || c == Short.class || c == Byte.class;
        }

        private static Class<?> primitiveWrapper(Class<?> p) {
            if (p == int.class)
                return Integer.class;
            if (p == long.class)
                return Long.class;
            if (p == double.class)
                return Double.class;
            if (p == float.class)
                return Float.class;
            if (p == short.class)
                return Short.class;
            if (p == byte.class)
                return Byte.class;
            if (p == boolean.class)
                return Boolean.class;
            if (p == char.class)
                return Character.class;
            return p;
        }

        public String toString() {
            return "boundMethod(" + name + ")";
        }
    }

    // ----------------------------- Exceptions -----------------------------
    static class ParseException extends RuntimeException {
        final int line, col;

        ParseException(String msg, int line, int col) {
            super(msg + " at " + line + ":" + col);
            this.line = line;
            this.col = col;
        }
    }

    static class EvalException extends RuntimeException {
        EvalException(String msg) {
            super(msg);
        }

        EvalException(String msg, Throwable t) {
            super(msg, t);
        }
    }

    // ----------------------------- Demo / Main ----------------------------
    public static void main(String[] args) {
        EvalContext ctx = new EvalContext();
        ctx.registerValue("Test", Test.class);
        ctx.registerValue("System", System.class);
        ctx.registerValue("Math", Math.class);

        run(ctx, """
                Math.random()
                """);
    }

    static class Test {
        public static String test = "This is a test print";
    }

    static void run(EvalContext ctx, String src) {
        ArrayList<String> packages = new ArrayList<>();
        packages.add("mindustry.");
        packages.add("arc.");
        for (Package pkg : Package.getPackages()) {
            packages.add(pkg.getName());  
        }
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .forPackages(packages.toArray(new String[0]))
                .setScanners(Scanners.SubTypes));
        Set<Class<?>> allClasses = reflections.getSubTypesOf(Object.class);
        for (Class<?> cls : allClasses) {
            System.out.println(cls);
        }

        // System.out.println("=> " + src);
        // Lexer lex = new Lexer(src);
        // List<Token> toks = lex.tokenize();
        // Parser p = new Parser(toks);
        // Expr ast = p.parseExpression();
        // System.out.println(ast);
        // Object res = ast.eval(ctx);
        // System.out.println(" => " + res + "\n");
    }
}
