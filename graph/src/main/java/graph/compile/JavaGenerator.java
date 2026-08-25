package graph.compile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import graph.registry.ParamDescriptor;
import graph.types.TypeRef;

public final class JavaGenerator {

    public record UnsupportedFeature(String nodeId, String reason) {
    }

    public record GeneratedSource(String className, String packageName, String qualifiedName,
                                  String source, SourceMap sourceMap,
                                  List<UnsupportedFeature> unsupported) {

        public boolean fullySupported() {
            return unsupported.isEmpty();
        }
    }

    static final String SUSPEND_IN_CONTROL_FLOW =
            "suspension inside control flow is deferred to a later phase";

    private static final String PACKAGE = "graph.generated";

    private final String graphId;
    private final StringBuilder out = new StringBuilder();
    private final SourceMap.Builder map;
    private final List<UnsupportedFeature> unsupported = new ArrayList<>();
    private final Set<String> suspendNodes = new LinkedHashSet<>();

    public JavaGenerator(String graphId) {
        this.graphId = graphId;
        this.map = new SourceMap.Builder(graphId, className(graphId));
    }

    public static String className(String graphId) {
        String sanitized = sanitize(graphId);
        StringBuilder sb = new StringBuilder("Graph_");
        if (!Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            sb.append('_');
        }
        sb.append(sanitized);
        return sb.toString();
    }

    public static String sanitize(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.replace('-', '_').toCharArray()) {
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return sb.toString();
    }

    public GeneratedSource generate(Ir.IrGraph ir) {
        scanSuspends(ir.entries());

        line("package " + PACKAGE + ";");
        blank();
        line("import graph.runtime.*;");
        line("import java.util.Map;");
        line("import java.util.concurrent.Future;");
        blank();
        line("public final class " + className(graphId) + " implements GraphExecutable {");
        blank();
        line("    private InvocationContext ctx;");
        line("    private RuntimeServices svc;");
        for (String nodeId : suspendNodes) {
            line("    private boolean suspended_" + sanitize(nodeId) + ";");
            line("    private Object slot_" + sanitize(nodeId) + ";");
        }
        blank();
        line("    @Override public String graphId() { return \"" + graphId + "\"; }");

        StringBuilder events = new StringBuilder();
        for (int i = 0; i < ir.entries().size(); i++) {
            if (i > 0) {
                events.append(", ");
            }
            events.append('"').append(ir.entries().get(i).eventNodeId()).append('"');
        }
        line("    @Override public java.util.Set<String> eventNodeIds() {");
        line("        return new java.util.HashSet<>(java.util.Arrays.asList(" + events + "));");
        line("    }");
        line("    private void noop() {");
        line("    }");

        for (Ir.IrEntry entry : ir.entries()) {
            emitEntry(entry);
        }

        line("}");
        return new GeneratedSource(className(graphId), PACKAGE,
                PACKAGE + "." + className(graphId), out.toString(), map.build(),
                List.copyOf(unsupported));
    }

    private void scanSuspends(List<Ir.IrEntry> entries) {
        for (Ir.IrEntry entry : entries) {
            scan(entry.body(), false);
        }
    }

    private void scan(List<Ir.IrStmt> body, boolean nested) {
        for (Ir.IrStmt stmt : body) {
            boolean suspend = stmt instanceof Ir.DelayStmt || stmt instanceof Ir.AwaitStmt
                    || stmt instanceof Ir.ScheduleOnceStmt || stmt instanceof Ir.HttpCallStmt
                    || stmt instanceof Ir.DbStmt;
            if (suspend) {
                suspendNodes.add(stmt.nodeId());
                if (nested && !hasFeature(stmt.nodeId())) {
                    unsupported.add(new UnsupportedFeature(stmt.nodeId(),
                            SUSPEND_IN_CONTROL_FLOW));
                }
            }
            if (stmt instanceof Ir.IfStmt s) {
                scan(s.thenBranch(), true);
                scan(s.elseBranch(), true);
            } else if (stmt instanceof Ir.ForEachStmt s) {
                scan(s.body(), true);
            } else if (stmt instanceof Ir.WhileLoopStmt s) {
                scan(s.body(), true);
            } else if (stmt instanceof Ir.SequenceStmt s) {
                for (List<Ir.IrStmt> step : s.steps()) {
                    scan(step, nested);
                }
            }
        }
    }

    private boolean hasFeature(String nodeId) {
        for (UnsupportedFeature feature : unsupported) {
            if (feature.nodeId().equals(nodeId)) {
                return true;
            }
        }
        return false;
    }

    private void emitEntry(Ir.IrEntry entry) {        map.markNode(entry.eventNodeId(), null);
        line("    @Override public void execute(String eventNodeId,"
                + " Map<String,Object> payload,");
        line("            InvocationContext ctxArg, RuntimeServices svcArg) {");
        line("        this.ctx = ctxArg;");
        line("        this.svc = svcArg;");
        line("        if (!\"" + entry.eventNodeId() + "\".equals(eventNodeId)) { return; }");
        for (ParamDescriptor param : entry.payload()) {
            String boxed = javaType(param.type().asNullable());
            line("        final " + boxed + " " + varRef(entry.eventNodeId(), param.name())
                    + " = (" + boxed + ") payload.get(\"" + param.name() + "\");");
        }
        body(entry.body(), 2);
        line("    }");
        blank();
    }

    private void body(List<Ir.IrStmt> statements, int depth) {
        String ind = indent(depth);
        for (Ir.IrStmt stmt : statements) {
            map.markNode(stmt.nodeId(),
                    stmt instanceof Ir.InvokeStmt inv ? inv.functionId() : null);

            if (stmt instanceof Ir.InvokeStmt invoke) {
                invoke(invoke, depth);
            } else if (stmt instanceof Ir.LogStmt log) {
                line(ind + "svc.log(java.lang.String.valueOf(" + expr(log.message()) + "));");
            } else if (stmt instanceof Ir.SetVariableStmt set) {
                line(ind + "svc.variables().set(\"" + set.variable() + "\", "
                        + expr(set.value()) + ");");
            } else if (stmt instanceof Ir.GetVariableStmt get) {
                String t = javaType(get.type().asNullable());
                line(ind + "final " + t + " " + resultVar(get.nodeId())
                        + " = (" + t + ") svc.variables().get(\"" + get.variable() + "\");");
            } else if (stmt instanceof Ir.PropertyGetStmt get) {
                String t = javaType(get.resultType().asNullable());
                line(ind + "final " + t + " " + resultVar(get.nodeId()) + " = (" + t
                        + ") svc.invokeFunction(\"" + get.propertyId()
                        + "\", \"\", new Object[]{" + expr(get.owner()) + "}, ctx);");
            } else if (stmt instanceof Ir.PropertySetStmt set) {
                line(ind + "svc.invokeFunction(\"" + set.propertyId()
                        + "\", \"\", new Object[]{" + expr(set.owner())
                        + ", " + expr(set.value()) + "}, ctx);");
            } else if (stmt instanceof Ir.IfStmt ifStmt) {
                line(ind + "if (java.util.Objects.equals("
                        + boolExpr(ifStmt.condition()) + ", Boolean.TRUE)) {");
                body(ifStmt.thenBranch(), depth + 1);
                line(ind + "} else {");
                body(ifStmt.elseBranch(), depth + 1);
                line(ind + "}");
            } else if (stmt instanceof Ir.SequenceStmt seq) {
                for (List<Ir.IrStmt> step : seq.steps()) {
                    body(step, depth);
                }
            } else if (stmt instanceof Ir.ForEachStmt loop) {
                forEach(loop, depth);
            } else if (stmt instanceof Ir.WhileLoopStmt loop) {
                whileLoop(loop, depth);
            } else if (stmt instanceof Ir.DelayStmt delay) {
                delay(delay, depth);
            } else if (stmt instanceof Ir.AwaitStmt await) {
                await(await, depth);
            } else if (stmt instanceof Ir.HttpCallStmt http) {
                httpCall(http, depth);
            } else if (stmt instanceof Ir.DbStmt db) {
                dbCall(db, depth);
            } else if (stmt instanceof Ir.ThrowStmt throwStmt) {
                line(ind + "throw new IllegalStateException(java.lang.String.valueOf("
                        + expr(throwStmt.message()) + "));");
            } else {
                line(ind + "// no-op: " + stmt.nodeId());
            }
        }
    }

    private void invoke(Ir.InvokeStmt invoke, int depth) {
        String ind = indent(depth);
        String safe = sanitize(invoke.nodeId());
        String type = javaType(invoke.resultType().asNullable());
        if (invoke.asyncDispatch()) {
            line(ind + "{");
            line(ind + "    if (!suspended_" + safe + ") {");
            line(ind + "        suspended_" + safe + " = true;");
            line(ind + "        slot_" + safe + " = svc.awaitFuture(svc.dispatchAsync(\""
                    + invoke.functionId() + "\", \"" + invoke.overloadHash()
                    + "\", new Object[]{" + args(invoke)
                    + "}, ctx), 0, () -> svc.postToMain(this::noop));");
            line(ind + "        return;");
            line(ind + "    }");
            line(ind + "    suspended_" + safe + " = false;");
            line(ind + "    final " + type + " " + resultVar(invoke.nodeId())
                    + " = (" + type + ") slot_" + safe + ";");
            line(ind + "}");
            return;
        }
        if (invoke.ownerClass() != null && invoke.staticMethod() != null) {
            String call = invoke.ownerClass() + "." + invoke.staticMethod()
                    + "(" + args(invoke) + ")";
            if (invoke.resultVar() == null) {
                line(ind + call + ";");
            } else {
                line(ind + "final " + type + " " + resultVar(invoke.nodeId())
                        + " = " + call + ";");
            }
            return;
        }
        String call = "svc.invokeFunction(\"" + invoke.functionId()
                + "\", \"" + invoke.overloadHash()
                + "\", new Object[]{" + args(invoke) + "}, ctx)";
        if (invoke.resultVar() == null) {
            line(ind + call + ";");
        } else {
            line(ind + "final " + type + " " + resultVar(invoke.nodeId())
                    + " = (" + type + ") " + call + ";");
        }
    }

    private String args(Ir.InvokeStmt invoke) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < invoke.args().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(expr(invoke.args().get(i)));
        }
        return sb.toString();
    }

    private void forEach(Ir.ForEachStmt loop, int depth) {
        String ind = indent(depth);
        String safe = sanitize(loop.nodeId());
        line(ind + "{");
        line(ind + "    final java.lang.Iterable<?> list_" + safe
                + " = (java.lang.Iterable<?>) " + expr(loop.list()) + ";");
        line(ind + "    long index_" + safe + " = 0;");
        line(ind + "    for (java.lang.Object item_" + safe + " : list_" + safe + ") {");
        line(ind + "        ctx.spend(1);");
        body(loop.body(), depth + 2);
        line(ind + "        index_" + safe + "++;");
        line(ind + "    }");
        line(ind + "}");
    }

    private void whileLoop(Ir.WhileLoopStmt loop, int depth) {
        String ind = indent(depth);
        String safe = sanitize(loop.nodeId());
        line(ind + "{");
        line(ind + "    int remaining_" + safe + " = (int) " + numeric(loop.count()) + ";");
        line(ind + "    while (remaining_" + safe + " != 0) {");
        line(ind + "        ctx.spend(1);");
        body(loop.body(), depth + 2);
        line(ind + "        remaining_" + safe + " += remaining_" + safe
                + " > 0 ? -1 : 1;");
        line(ind + "    }");
        line(ind + "}");
    }

    private void dbCall(Ir.DbStmt db, int depth) {
        String ind = indent(depth);
        String safe = sanitize(db.nodeId());
        String type = javaType(db.resultType().asNullable());
        line(ind + "{");
        line(ind + "    if (!suspended_" + safe + ") {");
        line(ind + "        suspended_" + safe + " = true;");
        String call = db.kind().equals("db-query")
                ? "svc.dbQueryAsync(" + expr(db.sqlOrTable()) + ", (Map<String, Object>) "
                + expr(db.paramsOrRow()) + ", ctx)"
                : "svc.dbUpdateAsync(\"" + db.kind() + "\", " + expr(db.sqlOrTable())
                + ", (Map<String, Object>) " + expr(db.paramsOrRow()) + ", ctx)";
        line(ind + "        slot_" + safe + " = svc.awaitFuture(" + call
                + ", 0, () -> svc.postToMain(this::noop));");
        line(ind + "        return;");
        line(ind + "    }");
        line(ind + "    suspended_" + safe + " = false;");
        line(ind + "    final " + type + " " + resultVar(db.nodeId())
                + " = (" + type + ") slot_" + safe + ";");
        line(ind + "}");
    }

    private String nullableString(Ir.IrExpr expression) {
        return expr(expression);
    }

    private void delay(Ir.DelayStmt delay, int depth) {
        String ind = indent(depth);
        String safe = sanitize(delay.nodeId());
        line(ind + "if (!suspended_" + safe + ") {");
        line(ind + "    suspended_" + safe + " = true;");
        line(ind + "    svc.scheduleResume((double) (" + numeric(delay.seconds())
                + "), () -> svc.postToMain(this::noop));");
        line(ind + "    return;");
        line(ind + "}");
        line(ind + "suspended_" + safe + " = false;");
    }

    private void await(Ir.AwaitStmt await, int depth) {
        String ind = indent(depth);
        String safe = sanitize(await.nodeId());
        String type = javaType(await.resultType().asNullable());
        line(ind + "{");
        line(ind + "    final Future<?> future_" + safe + " = (Future<?>) "
                + expr(await.future()) + ";");
        line(ind + "    if (!suspended_" + safe + ") {");
        line(ind + "        suspended_" + safe + " = true;");
        line(ind + "        slot_" + safe + " = svc.awaitFuture(future_" + safe + ", "
                + await.resumeSlot() + ", () -> svc.postToMain(this::noop));");
        line(ind + "        return;");
        line(ind + "    }");
        line(ind + "    suspended_" + safe + " = false;");
        line(ind + "    final " + type + " " + resultVar(await.nodeId())
                + " = (" + type + ") slot_" + safe + ";");
        line(ind + "}");
    }

    private void httpCall(Ir.HttpCallStmt http, int depth) {
        String ind = indent(depth);
        String safe = sanitize(http.nodeId());
        line(ind + "{");
        line(ind + "    if (!suspended_" + safe + ") {");
        line(ind + "        suspended_" + safe + " = true;");
        line(ind + "        slot_" + safe + " = svc.awaitFuture(svc.httpAsync(\""
                + http.method() + "\", " + expr(http.url()) + ", "
                + stringMap(http.headers()) + ", " + stringMap(http.query()) + ", "
                + nullableString(http.body())
                + ", ctx), 0, () -> svc.postToMain(this::noop));");
        line(ind + "        return;");
        line(ind + "    }");
        line(ind + "    suspended_" + safe + " = false;");
        line(ind + "    final RuntimeServices.HttpResult " + resultVar(http.nodeId())
                + " = (RuntimeServices.HttpResult) slot_" + safe + ";");
        line(ind + "}");
    }

    private String stringMap(Ir.IrExpr expression) {
        String source = expr(expression);
        return "(Map<String, String>) (" + source + ")";
    }

    private String expr(Ir.IrExpr expression) {
        if (expression instanceof Ir.PortRef ref) {
            if (ref.port().equals("result")) {
                return resultVar(ref.nodeId());
            }
            return varRef(ref.nodeId(), ref.port());
        }
        if (expression instanceof Ir.LiteralValue literal) {
            return literal.javaSource();
        }
        return "null";
    }

    private String boolExpr(Ir.IrExpr expression) {
        if (expression instanceof Ir.LiteralValue literal) {
            return literal.javaSource();
        }
        if (expression instanceof Ir.PortRef ref) {
            return varRef(ref.nodeId(), ref.port());
        }
        return "Boolean.FALSE";
    }

    private String numeric(Ir.IrExpr expression) {
        String raw = expr(expression);
        if (raw.endsWith("f") || raw.endsWith("F")) {
            return raw.substring(0, raw.length() - 1) + "d";
        }
        return raw;
    }

    static String resultVar(String nodeId) {
        return "v_" + sanitize(nodeId);
    }

    static String varRef(String nodeId, String port) {
        return "p_" + sanitize(nodeId) + "_" + sanitize(port);
    }

    static String javaType(TypeRef type) {
        return switch (type.base()) {
            case "String" -> "java.lang.String";
            case "Int" -> type.isNullable() ? "java.lang.Integer" : "int";
            case "Long" -> type.isNullable() ? "java.lang.Long" : "long";
            case "Float" -> type.isNullable() ? "java.lang.Float" : "float";
            case "Double" -> type.isNullable() ? "java.lang.Double" : "double";
            case "Boolean" -> type.isNullable() ? "java.lang.Boolean" : "boolean";
            case "Byte" -> type.isNullable() ? "java.lang.Byte" : "byte";
            default -> type.base();
        };
    }

    private void line(String text) {
        out.append(text).append('\n');
        map.newline();
    }

    private void blank() {
        out.append('\n');
        map.newline();
    }

    private static String indent(int depth) {
        return "    ".repeat(Math.max(depth, 0));
    }
}


