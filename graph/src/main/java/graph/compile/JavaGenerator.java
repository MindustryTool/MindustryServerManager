package graph.compile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import graph.registry.ParamDescriptor;

public final class JavaGenerator {

    public record UnsupportedFeature(String nodeId, String reason) {
    }

    public record GeneratedSource(String className, String packageName, String qualifiedName,
                                  String source, SourceMap sourceMap,
                                  List<UnsupportedFeature> unsupported,
                                  Set<String> suspendNodeIds) {

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
    private final Set<String> objectFields = new LinkedHashSet<>();

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
        collectFields(ir);

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
        line("    private int phase;");
        line("    private final java.util.Map<String, Object> codeInputs = new java.util.LinkedHashMap<>();");
        line("    private final java.util.Map<String, Object> codeOutputs = new java.util.LinkedHashMap<>();");
        for (String nodeId : suspendNodes) {
            line("    private boolean suspended_" + sanitize(nodeId) + ";");
            line("    private Object slot_" + sanitize(nodeId) + ";");
        }
        for (String field : objectFields) {
            line("    private Object " + field + ";");
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
        line("    @SuppressWarnings(\"unchecked\")");
        line("    private static Map<String, String> stringMap(Map<?, ?> raw) {");
        line("        return raw == null ? java.util.Collections.emptyMap() : (Map<String, String>) raw;");
        line("    }");
        line("    @SuppressWarnings(\"unchecked\")");
        line("    private static Map<String, Object> objectMap(Map<?, ?> raw) {");
        line("        return raw == null ? java.util.Collections.emptyMap() : (Map<String, Object>) raw;");
        line("    }");

        for (Ir.IrEntry entry : ir.entries()) {
            emitEntry(entry);
        }

        for (Ir.ScheduleStmt sched : collectSchedules(ir)) {
            line("    private void onFire_" + sanitize(sched.nodeId()) + "() {");
            line("        try {");
            body(sched.onFire(), 2);
            line("        } catch (java.lang.Throwable t_) {");
            line("            svc.log(\"[graph] schedule error: \" + t_);");
            line("        }");
            line("    }");
        }

        java.util.List<Ir.CodeFragmentStmt> codeFragments = collectCodeFragments(ir);
        if (!codeFragments.isEmpty()) {
            line("    @SuppressWarnings(\"unchecked\")");
            line("    private <T> T input(String name) {");
            line("        Object v = codeInputs.get(name);");
            line("        if (v == null && !codeInputs.containsKey(name)) {");
            line("            throw new java.lang.IllegalArgumentException(\"unknown input: \" + name);");
            line("        }");
            line("        return (T) v;");
            line("    }");
            line("    private void output(String name, Object value) {");
            line("        codeOutputs.put(name, value);");
            line("    }");
        }
        for (Ir.CodeFragmentStmt code : codeFragments) {
            line("    private void code_" + sanitize(code.nodeId()) + "() throws Exception {");
            line("        ctx.spend(1);");
            line("        codeOutputs.clear();");
            if (!code.body().isBlank()) {
                for (String bodyLine : code.body().split("\\n")) {
                    line("        " + bodyLine);
                }
            }
            line("    }");
        }

        line("}");
        return new GeneratedSource(className(graphId), PACKAGE,
                PACKAGE + "." + className(graphId), out.toString(), map.build(),
                List.copyOf(unsupported), Set.copyOf(suspendNodes));
    }

    private java.util.List<Ir.ScheduleStmt> collectSchedules(Ir.IrGraph ir) {
        java.util.List<Ir.ScheduleStmt> found = new java.util.ArrayList<>();
        for (Ir.IrEntry entry : ir.entries()) {
            collectSchedules(entry.body(), found);
        }
        return found;
    }

    private void collectSchedules(List<Ir.IrStmt> bodyList, java.util.List<Ir.ScheduleStmt> into) {
        for (Ir.IrStmt stmt : bodyList) {
            if (stmt instanceof Ir.ScheduleStmt sched) {
                into.add(sched);
            } else if (stmt instanceof Ir.IfStmt ifStmt) {
                collectSchedules(ifStmt.thenBranch(), into);
                collectSchedules(ifStmt.elseBranch(), into);
            } else if (stmt instanceof Ir.ForEachStmt loop) {
                collectSchedules(loop.body(), into);
            } else if (stmt instanceof Ir.WhileLoopStmt loop) {
                collectSchedules(loop.body(), into);
            } else if (stmt instanceof Ir.SequenceStmt seq) {
                for (List<Ir.IrStmt> step : seq.steps()) {
                    collectSchedules(step, into);
                }
            }
        }
    }

    private java.util.List<Ir.CodeFragmentStmt> collectCodeFragments(Ir.IrGraph ir) {
        java.util.List<Ir.CodeFragmentStmt> found = new java.util.ArrayList<>();
        for (Ir.IrEntry entry : ir.entries()) {
            collectCodeFragments(entry.body(), found);
        }
        return found;
    }

    private void collectCodeFragments(List<Ir.IrStmt> bodyList,
            java.util.List<Ir.CodeFragmentStmt> into) {
        for (Ir.IrStmt stmt : bodyList) {
            if (stmt instanceof Ir.CodeFragmentStmt code) {
                into.add(code);
            } else if (stmt instanceof Ir.IfStmt ifStmt) {
                collectCodeFragments(ifStmt.thenBranch(), into);
                collectCodeFragments(ifStmt.elseBranch(), into);
            } else if (stmt instanceof Ir.ForEachStmt loop) {
                collectCodeFragments(loop.body(), into);
            } else if (stmt instanceof Ir.WhileLoopStmt loop) {
                collectCodeFragments(loop.body(), into);
            } else if (stmt instanceof Ir.SequenceStmt seq) {
                for (List<Ir.IrStmt> step : seq.steps()) {
                    collectCodeFragments(step, into);
                }
            }
        }
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
                    || stmt instanceof Ir.DbStmt || isAsyncInvoke(stmt);
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

    private static boolean isAsyncInvoke(Ir.IrStmt stmt) {
        return stmt instanceof Ir.InvokeStmt invoke && invoke.asyncDispatch();
    }

    private boolean hasFeature(String nodeId) {
        for (UnsupportedFeature feature : unsupported) {
            if (feature.nodeId().equals(nodeId)) {
                return true;
            }
        }
        return false;
    }

    private void collectFields(Ir.IrGraph ir) {
        for (Ir.IrEntry entry : ir.entries()) {
            for (ParamDescriptor param : entry.payload()) {
                objectFields.add(varRef(entry.eventNodeId(), param.name()));
            }
            collectResultFields(entry.body());
        }
    }

    private void collectResultFields(List<Ir.IrStmt> body) {
        for (Ir.IrStmt stmt : body) {
            if (stmt instanceof Ir.InvokeStmt invoke && invoke.resultVar() != null) {
                objectFields.add(invoke.resultVar());
            } else if (stmt instanceof Ir.GetVariableStmt get) {
                objectFields.add(resultVar(get.nodeId()));
            } else if (stmt instanceof Ir.PropertyGetStmt get) {
                objectFields.add(resultVar(get.nodeId()));
            } else if (stmt instanceof Ir.AwaitStmt await) {
                objectFields.add(resultVar(await.nodeId()));
            } else if (stmt instanceof Ir.HttpCallStmt http) {
                objectFields.add(resultVar(http.nodeId()));
            } else if (stmt instanceof Ir.ScheduleStmt sched) {
                if (sched.resultVar() != null) {
                    objectFields.add(resultVar(sched.nodeId()));
                }
                collectResultFields(sched.onFire());
            } else if (stmt instanceof Ir.CodeFragmentStmt code) {
                objectFields.add(resultVar(code.nodeId()));
            } else if (stmt instanceof Ir.DbStmt db) {
                objectFields.add(resultVar(db.nodeId()));
            } else if (stmt instanceof Ir.IfStmt ifStmt) {
                collectResultFields(ifStmt.thenBranch());
                collectResultFields(ifStmt.elseBranch());
            } else if (stmt instanceof Ir.ForEachStmt loop) {
                collectResultFields(loop.body());
            } else if (stmt instanceof Ir.WhileLoopStmt loop) {
                collectResultFields(loop.body());
            } else if (stmt instanceof Ir.SequenceStmt seq) {
                seq.steps().forEach(this::collectResultFields);
            }
        }
    }

    private void emitEntry(Ir.IrEntry entry) {
        map.markNode(entry.eventNodeId(), null);
        line("    @Override public void execute(String eventNodeId,"
                + " Map<String,Object> payload,");
        line("            InvocationContext ctxArg, RuntimeServices svcArg) throws Exception {");
        line("        if (!\"" + entry.eventNodeId() + "\".equals(eventNodeId)) { return; }");
        line("        this.ctx = ctxArg;");
        line("        this.svc = svcArg;");
        line("        this.phase = 0;");
        for (ParamDescriptor param : entry.payload()) {
            line("        " + varRef(entry.eventNodeId(), param.name())
                    + " = payload.get(\"" + param.name() + "\");");
        }
        line("        runSegmentsSafe();");
        line("    }");
        blank();
        line("    private void runSegmentsSafe() {");
        line("        try {");
        line("            runSegments();");
        line("        } catch (RuntimeException e) {");
        line("            throw e;");
        line("        } catch (Exception e) {");
        line("            throw new RuntimeException(e);");
        line("        }");
        line("    }");
        blank();
        line("    private void runSegments() throws Exception {");
        List<List<Ir.IrStmt>> segments = segment(entry.body());
        for (int i = 0; i < segments.size(); i++) {
            List<Ir.IrStmt> segmentStatements = segments.get(i);
            boolean exits = !segmentStatements.isEmpty()
                    && unconditionalExit(segmentStatements.get(segmentStatements.size() - 1));
            line("        if (phase == " + i + ") {");
            body(segmentStatements, 3);
            if (!exits) {
                line("            phase = " + (i + 1) + ";");
            }
            line("        }");
        }
        line("    }");
        blank();
    }

    private static boolean unconditionalExit(Ir.IrStmt stmt) {
        return stmt instanceof Ir.ThrowStmt || stmt instanceof Ir.ReturnStmt;
    }

    private List<List<Ir.IrStmt>> segment(List<Ir.IrStmt> statements) {
        List<List<Ir.IrStmt>> segments = new ArrayList<>();
        List<Ir.IrStmt> current = new ArrayList<>();
        for (Ir.IrStmt stmt : statements) {
            current.add(stmt);
            if (isSuspend(stmt)) {
                segments.add(current);
                current = new ArrayList<>();
            }
        }
        segments.add(current);
        return segments;
    }

    private static boolean isSuspend(Ir.IrStmt stmt) {
        return stmt instanceof Ir.DelayStmt || stmt instanceof Ir.AwaitStmt
                || stmt instanceof Ir.ScheduleOnceStmt || stmt instanceof Ir.HttpCallStmt
                || stmt instanceof Ir.DbStmt || isAsyncInvoke(stmt);
    }

    private void body(List<Ir.IrStmt> statements, int depth) {
        String ind = indent(depth);
        for (Ir.IrStmt stmt : statements) {
            line(ind + "svc.debugNode(\"" + stmt.nodeId() + "\");");
            map.markNode(stmt.nodeId(),
                    stmt instanceof Ir.InvokeStmt inv ? inv.functionId() : null);

            if (stmt instanceof Ir.InvokeStmt invoke) {
                invoke(invoke, depth);
            } else if (stmt instanceof Ir.LogStmt log) {
                line(ind + "svc.log(java.lang.String.valueOf(" + expr(log.message()) + "));");
            } else if (stmt instanceof Ir.SetVariableStmt set) {
                line(ind + "svc.setVariable(\"" + set.scope() + "\", \""
                        + set.variable() + "\", " + expr(set.value()) + ");");
            } else if (stmt instanceof Ir.GetVariableStmt get) {
                line(ind + resultVar(get.nodeId()) + " = svc.getVariable(\""
                        + get.scope() + "\", \"" + get.variable() + "\");");
            } else if (stmt instanceof Ir.PropertyGetStmt get) {
                line(ind + resultVar(get.nodeId()) + " = svc.invokeFunction(\""
                        + get.propertyId() + "\", \"\", new Object[]{" + expr(get.owner())
                        + "}, ctx);");
            } else if (stmt instanceof Ir.PropertySetStmt set) {
                line(ind + "svc.invokeFunction(\"" + set.propertyId()
                        + "\", \"\", new Object[]{" + expr(set.owner()) + ", "
                        + expr(set.value()) + "}, ctx);");
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
                String safe = sanitize(delay.nodeId());
                line(ind + "{");
                line(ind + "    if (suspended_" + safe + ") {");
                line(ind + "        suspended_" + safe + " = false;");
                line(ind + "    } else {");
                line(ind + "        suspended_" + safe + " = true;");
                line(ind + "        svc.scheduleResume((double) ((Number) ("
                        + numeric(delay.seconds()) + ")).doubleValue(), "
                        + "() -> svc.postToMain(this::runSegmentsSafe));");
                line(ind + "        return;");
                line(ind + "    }");
                line(ind + "}");
            } else if (stmt instanceof Ir.AwaitStmt await) {
                String safe = sanitize(await.nodeId());
                String callback = "(v, t) -> { slot_" + safe
                        + " = (t != null) ? t : v; svc.postToMain(this::runSegmentsSafe); }";
                line(ind + "{");
                line(ind + "    if (suspended_" + safe + ") {");
                line(ind + "        suspended_" + safe + " = false;");
                line(ind + "        Object r_" + safe + " = slot_" + safe + ";");
                line(ind + "        if (r_" + safe + " instanceof java.lang.Throwable t_) { if (t_ instanceof java.lang.Exception e_) throw e_; throw new java.lang.RuntimeException(t_); }");
                line(ind + "        " + resultVar(await.nodeId()) + " = r_" + safe + ";");
                line(ind + "    } else {");
                line(ind + "        suspended_" + safe + " = true;");
                if (await.timeoutSeconds() != null) {
                    line(ind + "        svc.awaitWithTimeout((java.util.concurrent.CompletableFuture<?>) "
                            + expr(await.future()) + ", " + await.timeoutSeconds() + ", "
                            + callback + ");");
                } else {
                    line(ind + "        svc.awaitFuture((java.util.concurrent.CompletableFuture<?>) "
                            + expr(await.future()) + ", " + callback + ");");
                }
                line(ind + "        return;");
                line(ind + "    }");
                line(ind + "}");
            } else if (stmt instanceof Ir.HttpCallStmt http) {
                String safe = sanitize(http.nodeId());
                line(ind + "{");
                line(ind + "    if (suspended_" + safe + ") {");
                line(ind + "        suspended_" + safe + " = false;");
                line(ind + "        Object r_" + safe + " = slot_" + safe + ";");
                line(ind + "        if (r_" + safe + " instanceof java.lang.Throwable t_) { if (t_ instanceof java.lang.Exception e_) throw e_; throw new java.lang.RuntimeException(t_); }");
                line(ind + "        " + resultVar(http.nodeId()) + " = r_" + safe + ";");
                line(ind + "    } else {");
                line(ind + "        suspended_" + safe + " = true;");
                line(ind + "        final java.util.concurrent.CompletableFuture<?> http_"
                        + safe + " = svc.httpAsync(\"" + http.method() + "\", "
                        + expr(http.url()) + ", stringMap(" + expr(http.headers())
                        + "), stringMap(" + expr(http.query())
                        + "), (java.lang.String) " + nullableString(http.body()) + ", ctx);");
                line(ind + "        svc.awaitFuture(http_" + safe + ", (v, t) -> {");
                line(ind + "            slot_" + safe + " = (t != null) ? t : v;");
                line(ind + "            svc.postToMain(this::runSegmentsSafe);");
                line(ind + "        });");
                line(ind + "        return;");
                line(ind + "    }");
                line(ind + "}");
            } else if (stmt instanceof Ir.DbStmt db) {
                dbCall(db, depth);
            } else if (stmt instanceof Ir.ScheduleStmt sched) {
                if (sched.resultVar() != null) {
                    line(ind + resultVar(sched.nodeId()) + " = svc.startSchedule(\""
                            + sched.mode() + "\", " + numeric(sched.seconds())
                            + ", this::onFire_" + sanitize(sched.nodeId()) + ", ctx);");
                } else {
                    line(ind + "svc.startSchedule(\"" + sched.mode() + "\", "
                            + numeric(sched.seconds())
                            + ", this::onFire_" + sanitize(sched.nodeId()) + ", ctx);");
                }
            } else if (stmt instanceof Ir.CancelScheduleStmt cancel) {
                line(ind + "svc.cancelSchedule(" + expr(cancel.handle()) + ");");
            } else if (stmt instanceof Ir.ReturnStmt returnStmt) {
                line(ind + "throw new graph.runtime.GraphReturnSignal("
                        + expr(returnStmt.value()) + ");");
            } else if (stmt instanceof Ir.CodeFragmentStmt code) {
                line(ind + "codeInputs.clear();");
                for (java.util.Map.Entry<String, Ir.IrExpr> binding :
                        code.inputs().entrySet()) {
                    line(ind + "codeInputs.put(\"" + binding.getKey() + "\", "
                            + expr(binding.getValue()) + ");");
                }
                line(ind + "code_" + sanitize(code.nodeId()) + "();");
                if (code.resultVar() != null) {
                    line(ind + resultVar(code.nodeId())
                            + " = java.util.Map.copyOf(codeOutputs);");
                }
            } else if (stmt instanceof Ir.ThrowStmt throwStmt) {
                line(ind + "throw new IllegalStateException(java.lang.String.valueOf("
                        + expr(throwStmt.message()) + "));");
            } else {
                line(ind + "// no-op: " + stmt.nodeId());
            }
        }
    }

    private String stringMap(String expression) {
        return "(Map<String, String>) " + expression;
    }

    private String nullableString(Ir.IrExpr expression) {
        return expr(expression);
    }

    private void dbCall(Ir.DbStmt db, int depth) {
        String ind = indent(depth);
        String safe = sanitize(db.nodeId());
        line(ind + "{");
        line(ind + "    if (suspended_" + safe + ") {");
        line(ind + "        suspended_" + safe + " = false;");
        line(ind + "    } else {");
        line(ind + "        suspended_" + safe + " = true;");
        String call = db.kind().equals("db-query")
                ? "svc.dbQueryAsync((java.lang.String) " + expr(db.sqlOrTable())
                + ", objectMap(" + expr(db.paramsOrRow()) + "), ctx)"
                : "svc.dbUpdateAsync(\"" + db.kind() + "\", (java.lang.String) "
                + expr(db.sqlOrTable()) + ", objectMap(" + expr(db.paramsOrRow())
                + "), ctx)";
        line(ind + "        final java.util.concurrent.CompletableFuture<?> db_" + safe + " = " + call + ";");
        line(ind + "        svc.awaitFuture(db_" + safe + ", (v, t) -> {");
        line(ind + "            slot_" + safe + " = (t != null) ? t : v;");
        line(ind + "            svc.postToMain(this::runSegmentsSafe);");
        line(ind + "        });");
        line(ind + "        return;");
        line(ind + "    }");
        line(ind + "}");
        line(ind + "Object r_" + safe + " = slot_" + safe + ";");
        line(ind + "if (r_" + safe + " instanceof java.lang.Throwable t_) { if (t_ instanceof java.lang.Exception e_) throw e_; throw new java.lang.RuntimeException(t_); }");
        line(ind + resultVar(db.nodeId()) + " = r_" + safe + ";");
    }

    private void invoke(Ir.InvokeStmt invoke, int depth) {
        String ind = indent(depth);
        String safe = sanitize(invoke.nodeId());
        if (invoke.asyncDispatch()) {
            line(ind + "{");
            line(ind + "    if (suspended_" + safe + ") {");
            line(ind + "        suspended_" + safe + " = false;");
            line(ind + "    } else {");
            line(ind + "        suspended_" + safe + " = true;");
            line(ind + "        final java.util.concurrent.CompletableFuture<?> dispatch_" + safe + " = svc.dispatchAsync(\""
                    + invoke.functionId() + "\", \"" + invoke.overloadHash()
                    + "\", new Object[]{" + args(invoke) + "}, ctx);");
            line(ind + "        svc.awaitFuture(dispatch_" + safe + ", (v, t) -> {");
            line(ind + "            slot_" + safe + " = (t != null) ? t : v;");
            line(ind + "            svc.postToMain(this::runSegmentsSafe);");
            line(ind + "        });");
            line(ind + "        return;");
            line(ind + "    }");
            line(ind + "}");
            if (invoke.resultVar() != null) {
                line(ind + "Object r_" + safe + " = slot_" + safe + ";");
                line(ind + "if (r_" + safe + " instanceof java.lang.Throwable t_) { if (t_ instanceof java.lang.Exception e_) throw e_; throw new java.lang.RuntimeException(t_); }");
                line(ind + resultVar(invoke.nodeId()) + " = r_" + safe + ";");
            }
            return;
        }
        String call = "svc.invokeFunction(\"" + invoke.functionId()
                + "\", \"" + invoke.overloadHash()
                + "\", new Object[]{" + args(invoke) + "}, ctx)";
        String timedCall = "{ final long __t = java.lang.System.nanoTime(); try { "
                + call
                + "; } finally { svc.recordNodeTiming(\"" + invoke.nodeId()
                + "\", java.lang.System.nanoTime() - __t); } }";
        if (invoke.resultVar() == null) {
            line(ind + timedCall);
        } else {
            line(ind + "{ final long __t = java.lang.System.nanoTime(); "
                    + "final Object __rv; try { __rv = " + call
                    + "; } finally { svc.recordNodeTiming(\"" + invoke.nodeId()
                    + "\", java.lang.System.nanoTime() - __t); } "
                    + resultVar(invoke.nodeId()) + " = __rv; }");
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
        map.markNode(loop.nodeId(), null);
        line(ind + "        ctx.spend(1);");
        body(loop.body(), depth + 2);
        map.markNode(loop.nodeId(), null);
        line(ind + "        index_" + safe + "++;");
        line(ind + "    }");
        line(ind + "}");
    }

    private void whileLoop(Ir.WhileLoopStmt loop, int depth) {
        String ind = indent(depth);
        String safe = sanitize(loop.nodeId());
        line(ind + "{");
        line(ind + "    int remaining_" + safe + " = (int) " + numeric(loop.count()) + ";");
        line(ind + "    if (remaining_" + safe + " <= 0) {");
        line(ind + "        remaining_" + safe + " = Integer.MAX_VALUE;");
        line(ind + "    }");
        line(ind + "    while (remaining_" + safe + "-- > 0) {");
        map.markNode(loop.nodeId(), null);
        line(ind + "        ctx.spend(1);");
        body(loop.body(), depth + 2);
        map.markNode(loop.nodeId(), null);
        line(ind + "    }");
        line(ind + "}");
    }

    private String expr(Ir.IrExpr expression) {
        if (expression instanceof Ir.PortRef ref) {
            if (ref.port().equals("result") || ref.port().equals("value")
                    || ref.port().equals("rows") || ref.port().equals("count")) {
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
        return expr(expression);
    }

    static String resultVar(String nodeId) {
        return "v_" + sanitize(nodeId);
    }

    static String varRef(String nodeId, String port) {
        return "p_" + sanitize(nodeId) + "_" + sanitize(port);
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



