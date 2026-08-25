package graph.compile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import graph.format.Diagnostic;
import graph.format.GraphDocument;
import graph.format.GraphEdge;
import graph.format.GraphNode;
import graph.registry.Overload;
import graph.registry.ParamDescriptor;
import graph.types.TypeRef;

public final class Lowerer {

    private final GraphDocument document;
    private final LinkedGraph linked;
    private final ThreadCheckResult threads;
    private final Map<String, TypeRef> inferredPorts;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, String> execSuccessor = new HashMap<>();
    private final Set<String> lowered = new HashSet<>();
    private int resumeSlots;

    public record LowerResult(Ir.IrGraph ir, int resumeSlotCount, List<Diagnostic> diagnostics) {

        public boolean ok() {
            return diagnostics.stream().noneMatch(Diagnostic::isError);
        }
    }

    public Lowerer(GraphDocument document, LinkedGraph linked, ThreadCheckResult threads,
                   Map<String, TypeRef> inferredPorts) {
        this.document = document;
        this.linked = linked;
        this.threads = threads;
        this.inferredPorts = inferredPorts;
        indexExecEdges();
    }

    private static final Set<String> CHAIN_TERMINATORS =
            Set.of("if", "sequence", "loop", "for-each");

    public LowerResult lower() {
        List<Ir.IrEntry> entries = new ArrayList<>();
        for (GraphNode node : document.nodes()) {
            if (!node.type().equals("event")) {
                continue;
            }
            LinkedGraph.LinkedNode linkedNode = linked.node(node.id());
            if (linkedNode == null || linkedNode.event() == null) {
                continue;
            }
            String first = execSuccessor.get(execOutKey(node.id(), "then"));
            List<Ir.IrStmt> body = first == null
                    ? List.of() : List.copyOf(lowerChainFrom(first));
            entries.add(new Ir.IrEntry(node.id(), linkedNode.event().id(),
                    linkedNode.event().payload(), body));
        }
        return new LowerResult(new Ir.IrGraph(document.id(), entries), resumeSlots,
                List.copyOf(diagnostics));
    }

    private void indexExecEdges() {
        for (GraphEdge edge : document.edges()) {
            if (!isExecSource(edge.from()) && !isExecTarget(edge.to())) {
                continue;
            }
            if (isExecSource(edge.from())) {
                execSuccessor.putIfAbsent(execOutKey(edge.from().nodeId(), edge.from().port()),
                        edge.to().nodeId());
            }
        }
    }

    private static boolean isExecSource(graph.format.PortAddress address) {
        return address.port().equals("then") || address.port().equals("else")
                || address.port().equals("body") || address.port().startsWith("step");
    }

    private static boolean isExecTarget(graph.format.PortAddress address) {
        return address.port().equals("exec") || address.port().equals("body");
    }

    private String execOutKey(String nodeId, String port) {
        return nodeId + ">" + port;
    }

    private List<Ir.IrStmt> lowerChain(String startNodeId) {
        List<Ir.IrStmt> statements = new ArrayList<>();
        String current = startNodeId;
        HashSet<String> guard = new HashSet<>();
        while (current != null && guard.add(current)) {
            Ir.IrStmt statement = lowerNode(current);
            if (statement != null) {
                statements.add(statement);
            }
            GraphNode node = document.node(current);
            if (node != null && CHAIN_TERMINATORS.contains(node.type())) {
                break;
            }
            current = execSuccessor.get(execOutKey(current, "then"));
        }
        return statements;
    }

    private Ir.IrStmt lowerNode(String nodeId) {
        if (!lowered.add(nodeId)) {
            return new Ir.Nop(nodeId);
        }
        GraphNode node = document.node(nodeId);
        LinkedGraph.LinkedNode linkedNode = linked.node(nodeId);
        if (node == null || linkedNode == null) {
            return null;
        }
        return switch (node.type()) {
            case "call" -> lowerCall(node, linkedNode);
            case "event" -> new Ir.Nop(nodeId);
            case "if" -> lowerIf(node);
            case "sequence" -> lowerSequence(node);
            case "for-each" -> lowerForEach(node);
            case "loop" -> lowerLoop(node);
            case "get-variable" -> lowerGetVariable(node, linkedNode);
            case "set-variable" -> lowerSetVariable(node, linkedNode);
            case "get-property" -> lowerPropertyGet(node, linkedNode);
            case "set-property" -> lowerPropertySet(node, linkedNode);
            case "delay" -> new Ir.DelayStmt(nodeId,
                    numericArgOr(node, "seconds", 1.0), resumeSlots++);
            case "schedule" -> lowerSchedule(node);
            case "parallel" -> {
                List<Ir.IrStmt> branch = chainFrom(nodeId, "body");
                yield new Ir.ScheduleStmt(nodeId, "next-tick",
                        new Ir.LiteralValue(TypeRef.FLOAT, "0.0f"), branch, null);
            }
            case "cancel-schedule" -> {
                String source = findDataSourcePort(nodeId, null);
                yield new Ir.CancelScheduleStmt(nodeId,
                        source != null
                                ? portRef(source, TypeRef.of("Object"))
                                : new Ir.LiteralValue(TypeRef.of("Object"), "null"));
            }
            case "await" -> lowerAwait(node);
            case "http-get", "http-post", "http-put", "http-delete" -> lowerHttp(node);
            case "db-query", "db-insert", "db-update", "db-delete" -> lowerDb(node);
            case "log" -> new Ir.LogStmt(nodeId,
                    textArgOrDefault(node, "message", "\"[graph] log\""));
            case "code" -> new Ir.CodeFragmentStmt(nodeId);
            case "throw" -> new Ir.ThrowStmt(nodeId,
                    textArgOrDefault(node, "message", "\"graph error\""));
            case "return" -> new Ir.ReturnStmt(nodeId);
            default -> new Ir.Nop(nodeId);
        };
    }

    private Ir.IrStmt lowerCall(GraphNode node, LinkedGraph.LinkedNode linkedNode) {
        Overload overload = linkedNode.overload();
        if (overload == null) {
            return new Ir.Nop(node.id());
        }
        JsonNode args = node.get("args");
        List<Ir.IrExpr> arguments = new ArrayList<>();
        for (ParamDescriptor param : overload.params()) {
            Ir.IrExpr expression = resolveArgument(node.id(), param.name(), param.type(), args);
            arguments.add(expression);
        }
        boolean async = threads.requiresAsyncDispatch(node.id());
        String resultVar = overload.returnType().base().equals("Void")
                ? null : resultVarName(node.id());
        return new Ir.InvokeStmt(node.id(), linkedNode.function().id(),
                overload.hash(), null, null, arguments, resultVar,
                overload.returnType(), async);
    }

    private Ir.IrExpr resolveArgument(String nodeId, String paramName, TypeRef declared,
                                      JsonNode args) {
        for (GraphEdge edge : document.edges()) {
            if (edge.to().nodeId().equals(nodeId) && edge.to().port().equals(paramName)
                    && !isExecTarget(edge.to())) {
                return portRef(edge.from().nodeId() + "." + edge.from().port(), declared);
            }
        }
        JsonNode literal = args == null ? null : args.get(paramName);
        if (literal != null) {
            return literalExpr(literal, declared);
        }
        return defaultValueExpr(declared);
    }

    private Ir.IrExpr portRef(String nodeDotPort, TypeRef fallback) {
        int dot = nodeDotPort.lastIndexOf('.');
        String nodeId = nodeDotPort.substring(0, dot);
        String port = nodeDotPort.substring(dot + 1);
        TypeRef type = inferredPorts.get(nodeDotPort);
        return new Ir.PortRef(nodeId, port, type != null ? type : fallback);
    }

    private Ir.IrExpr literalExpr(JsonNode literal, TypeRef declared) {
        JsonNode value = literal.has("value") && literal.has("kind") ? literal.get("value") : literal;
        if (value.isNull()) {
            return new Ir.LiteralValue(declared.asNullable(), "null");
        }
        if (value.isTextual()) {
            return new Ir.LiteralValue(TypeRef.STRING, quoteJava(value.asText()));
        }
        if (value.isBoolean()) {
            return new Ir.LiteralValue(TypeRef.BOOLEAN, value.asBoolean() ? "true" : "false");
        }
        if (value.isFloatingPointNumber()) {
            return new Ir.LiteralValue(TypeRef.FLOAT, value.asDouble() + "f");
        }
        if (value.isNumber()) {
            return new Ir.LiteralValue(TypeRef.INT, String.valueOf(value.longValue()));
        }
        return new Ir.LiteralValue(declared.asNullable(), "null");
    }

    private static String quoteJava(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private Ir.IrExpr defaultValueExpr(TypeRef declared) {
        if (declared.isNullable() || declared.base().equals("String")) {
            return new Ir.LiteralValue(declared.asNullable(), "null");
        }
        if (declared.base().equals("Boolean")) {
            return new Ir.LiteralValue(declared, "false");
        }
        if (declared.base().equals("Float")) {
            return new Ir.LiteralValue(declared, "0f");
        }
        if (declared.base().equals("Double")) {
            return new Ir.LiteralValue(declared, "0d");
        }
        if (declared.isPrimitive()) {
            return new Ir.LiteralValue(declared, "0");
        }
        return new Ir.LiteralValue(declared.asNullable(), "null");
    }

    private Ir.IrStmt lowerIf(GraphNode node) {
        Ir.IrExpr condition = singleDataInput(node.id(), TypeRef.BOOLEAN);
        List<Ir.IrStmt> thenBranch = lowerBranch(node.id(), "then");
        List<Ir.IrStmt> elseBranch = lowerBranch(node.id(), "else");
        return new Ir.IfStmt(node.id(), condition, thenBranch, elseBranch);
    }

    private List<Ir.IrStmt> lowerBranch(String nodeId, String branchPort) {
        String target = execSuccessor.get(execOutKey(nodeId, branchPort));
        if (target == null) {
            return List.of();
        }
        return List.copyOf(lowerChainFrom(target));
    }

    private List<Ir.IrStmt> lowerChainFrom(String startNodeId) {
        return lowerChain(startNodeId);
    }

    private Ir.IrStmt lowerSequence(GraphNode node) {
        List<List<Ir.IrStmt>> steps = new ArrayList<>();
        int index = 0;
        while (true) {
            String target = execSuccessor.get(execOutKey(node.id(), "step" + index));
            if (target == null) {
                break;
            }
            steps.add(List.copyOf(lowerChainFrom(target)));
            index++;
        }
        return new Ir.SequenceStmt(node.id(), steps);
    }

    private Ir.IrStmt lowerForEach(GraphNode node) {
        TypeRef itemType = inferredPorts.getOrDefault(node.id() + ".item", TypeRef.of("Object"));
        Ir.IrExpr listExpr = singleDataInput(node.id(), TypeRef.list(itemType));
        List<Ir.IrStmt> body = lowerBranch(node.id(), "body");
        boolean withIndex = hasDataInputNamed(node.id(), "index");
        return new Ir.ForEachStmt(node.id(), listExpr, "item_" + node.id(), itemType,
                withIndex, body);
    }

    private Ir.IrStmt lowerLoop(GraphNode node) {
        Ir.IrExpr count = numericArgOr(node, "count", -1);
        List<Ir.IrStmt> body = lowerBranch(node.id(), "body");
        return new Ir.WhileLoopStmt(node.id(), count, body);
    }

    private Ir.IrStmt lowerGetVariable(GraphNode node, LinkedGraph.LinkedNode linkedNode) {
        TypeRef type = inferredPorts.getOrDefault(node.id() + ".value", TypeRef.of("Object"));
        return new Ir.GetVariableStmt(node.id(), linkedNode.variableName(),
                scopeOf(node), resultVarName(node.id()), type);
    }

    private Ir.IrStmt lowerSetVariable(GraphNode node, LinkedGraph.LinkedNode linkedNode) {
        Ir.IrExpr value = singleDataInput(node.id(),
                variableType(linkedNode.variableName()));
        return new Ir.SetVariableStmt(node.id(), linkedNode.variableName(),
                scopeOf(node), value);
    }

    private String scopeOf(GraphNode node) {
        JsonNode scope = node.get("scope");
        return scope == null || !scope.isTextual()
                ? graph.format.VariableScope.GRAPH : scope.asText();
    }

    private TypeRef variableType(String name) {
        for (var decl : document.variables()) {
            if (decl.name().equals(name)) {
                try {
                    return TypeRef.parse(decl.typeRef());
                } catch (IllegalArgumentException ignored) {
                    return TypeRef.of("Object");
                }
            }
        }
        return TypeRef.of("Object");
    }

    private Ir.IrStmt lowerPropertyGet(GraphNode node, LinkedGraph.LinkedNode linkedNode) {
        String ownerPort = decapitalize(linkedNode.property().ownerType().base());
        TypeRef ownerType = inferredPorts.getOrDefault(node.id() + "." + ownerPort,
                linkedNode.property().ownerType());
        return new Ir.PropertyGetStmt(node.id(), linkedNode.property().id(),
                new Ir.PortRef(findExecOrDataSourceOwner(node.id(), ownerPort),
                        ownerPort, ownerType),
                resultVarName(node.id()), linkedNode.property().valueType());
    }

    private Ir.IrStmt lowerPropertySet(GraphNode node, LinkedGraph.LinkedNode linkedNode) {
        String ownerPort = decapitalize(linkedNode.property().ownerType().base());
        TypeRef ownerType = linkedNode.property().ownerType();
        Ir.IrExpr owner = new Ir.PortRef(
                findExecOrDataSourceOwner(node.id(), ownerPort), ownerPort, ownerType);
        Ir.IrExpr value = singleDataInput(node.id(), linkedNode.property().valueType());
        return new Ir.PropertySetStmt(node.id(), linkedNode.property().id(), owner, value);
    }

    private String findExecOrDataSourceOwner(String nodeId, String port) {
        for (GraphEdge edge : document.edges()) {
            if (edge.to().nodeId().equals(nodeId) && edge.to().port().equals(port)) {
                return edge.from().nodeId();
            }
        }
        return "unknown_src";
    }

    private Ir.IrStmt lowerAwait(GraphNode node) {
        TypeRef valueType = inferredPorts.getOrDefault(node.id() + ".value",
                TypeRef.of("Object"));
        Ir.IrExpr future = singleDataInput(node.id(), TypeRef.future(valueType));
        JsonNode to = node.get("timeoutSeconds");
        if (to == null || to.isNull()) {
            JsonNode props = node.get("properties");
            to = props == null ? null : props.get("timeoutSeconds");
        }
        Double timeout = to != null && !to.isNull() ? to.asDouble() : null;
        return new Ir.AwaitStmt(node.id(), future, resultVarName(node.id()), valueType,
                timeout, resumeSlots++);
    }

    private Ir.IrStmt lowerSchedule(GraphNode node) {
        JsonNode props = node.get("properties");
        JsonNode modeNode = props == null ? null : props.get("mode");
        String mode = modeNode != null && modeNode.isTextual() ? modeNode.asText() : "after";
        JsonNode seconds = node.get("seconds");
        if (seconds == null || seconds.isNull()) {
            seconds = props == null ? null : props.get("seconds");
        }
        double value = seconds != null && !seconds.isNull() ? seconds.asDouble() : 1.0;
        List<Ir.IrStmt> onFire = chainFrom(node.id(), "body");
        return new Ir.ScheduleStmt(node.id(), mode,
                new Ir.LiteralValue(TypeRef.FLOAT, value + "f"), onFire,
                resultVarName(node.id()));
    }

    private List<Ir.IrStmt> chainFrom(String nodeId, String execPort) {
        String next = execSuccessor.get(execOutKey(nodeId, execPort));
        return next == null ? List.of() : lowerChain(next);
    }

    private Ir.IrStmt lowerHttp(GraphNode node) {
        String url = findDataSourcePort(node.id(), "url");
        Ir.IrExpr urlExpr;
        if (url != null) {
            urlExpr = portRef(url, TypeRef.STRING);
        } else {
            JsonNode props = node.get("properties");
            JsonNode configured = props == null ? null : props.get("url");
            String value = configured != null && configured.isTextual()
                    ? configured.asText() : "";
            urlExpr = new Ir.LiteralValue(TypeRef.STRING,
                    '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"');
        }
        return new Ir.HttpCallStmt(node.id(), httpMethod(node.type()),
                urlExpr,
                mapArgOrEmpty(node.id(), "headers"),
                mapArgOrEmpty(node.id(), "query"),
                textArgOrNull(node, "body"),
                resultVarName(node.id()), resumeSlots++);
    }

    private static String httpMethod(String nodeType) {
        return switch (nodeType) {
            case "http-post" -> "POST";
            case "http-put" -> "PUT";
            case "http-delete" -> "DELETE";
            default -> "GET";
        };
    }

    private Ir.IrExpr mapArgOrEmpty(String nodeId, String port) {
        String source = findDataSourcePort(nodeId, port);
        return portOrLiteral(source, TypeRef.map(TypeRef.STRING, TypeRef.STRING),
                "java.util.Map.of()");
    }

    private Ir.IrExpr portOrLiteral(String sourcePort, TypeRef type, String literalJava) {
        if (sourcePort != null) {
            return portRef(sourcePort, type);
        }
        return new Ir.LiteralValue(type, literalJava);
    }

    private Ir.IrStmt lowerDb(GraphNode node) {
        boolean isQuery = node.type().equals("db-query");
        Ir.IrExpr first = singleDataInput(node.id(), TypeRef.STRING);
        Ir.IrExpr second = isQuery
                ? mapArgOrEmpty(node.id(), "params")
                : mapArgOrEmpty(node.id(), "row");
        TypeRef resultType = isQuery
                ? TypeRef.list(TypeRef.map(TypeRef.STRING, TypeRef.of("Object")))
                : TypeRef.INT;
        return new Ir.DbStmt(node.id(), node.type(), first, second,
                resultVarName(node.id()), resultType, resumeSlots++);
    }

    private Ir.IrExpr singleDataInput(String nodeId, TypeRef fallback) {
        String source = findDataSourcePort(nodeId, null);
        if (source != null) {
            return portRef(source, fallback);
        }
        GraphNode node = document.node(nodeId);
        JsonNode args = node == null ? null : node.get("args");
        JsonNode literal = args == null ? null : args.get("condition");
        if (literal != null) {
            return literalExpr(literal, fallback);
        }
        return new Ir.LiteralValue(fallback.asNullable(), "null");
    }

    private String findDataSourcePort(String nodeId, String preferredPort) {
        for (GraphEdge edge : document.edges()) {
            if (!edge.to().nodeId().equals(nodeId)) {
                continue;
            }
            if (preferredPort != null && !edge.to().port().equals(preferredPort)) {
                continue;
            }
            if (isExecTarget(edge.to()) || isExecSource(edge.to())) {
                continue;
            }
            return edge.from().nodeId() + "." + edge.from().port();
        }
        return null;
    }

    private boolean hasDataInputNamed(String nodeId, String port) {
        return findDataSourcePort(nodeId, port) != null;
    }

    private Ir.IrExpr numericArgOr(GraphNode node, String field, double fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            JsonNode props = node.get("properties");
            value = props == null ? null : props.get(field);
        }
        if (value == null || value.isNull()) {
            return new Ir.LiteralValue(TypeRef.FLOAT, fallback + "f");
        }
        return new Ir.LiteralValue(TypeRef.FLOAT, value.asDouble() + "f");
    }

    private Ir.IrExpr textArgOrDefault(GraphNode node, String field, String defaultJava) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            JsonNode props = node.get("properties");
            value = props == null ? null : props.get(field);
        }
        if (value == null || !value.isTextual()) {
            return new Ir.LiteralValue(TypeRef.STRING, defaultJava);
        }
        return new Ir.LiteralValue(TypeRef.STRING, quoteJava(value.asText()));
    }

    private Ir.IrExpr textArgOrNull(GraphNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            JsonNode props = node.get("properties");
            value = props == null ? null : props.get(field);
        }
        if (value == null || value.isNull()) {
            return new Ir.LiteralValue(TypeRef.STRING.asNullable(), "null");
        }
        return new Ir.LiteralValue(TypeRef.STRING, quoteJava(value.asText()));
    }

    private static String resultVarName(String nodeId) {
        return "v_" + nodeId.replace('-', '_');
    }

    private static String decapitalize(String base) {
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toLowerCase(base.charAt(0)));
        for (int i = 1; i < base.length(); i++) {
            char c = base.charAt(i);
            if (!Character.isUpperCase(c)) {
                sb.append(base.substring(i));
                break;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
