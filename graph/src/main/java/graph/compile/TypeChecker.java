package graph.compile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import graph.format.Diagnostic;
import graph.format.GraphDocument;
import graph.format.GraphEdge;
import graph.format.GraphNode;
import graph.format.PortAddress;
import graph.format.ValidationResult;
import graph.registry.Overload;
import graph.registry.ParamDescriptor;
import graph.types.Assignability;
import graph.types.TypeRef;

public final class TypeChecker {

    public static final String EXEC_BASE = "exec";

    private final GraphDocument document;
    private final LinkedGraph linked;
    private final Map<String, TypeRef> variableTypes = new HashMap<>();
    private final Map<String, TypeRef> portTypes = new HashMap<>();
    private final Map<String, TypeRef> genericBindings = new HashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private TypeChecker(GraphDocument document, LinkedGraph linked) {
        this.document = document;
        this.linked = linked;
        document.variables().forEach(v -> variableTypes.put(v.name(), parseType(v.typeRef())));
    }

    public record CheckResult(ValidationResult result, Map<String, TypeRef> inferredPorts) {

    }

    public static CheckResult check(GraphDocument document, LinkedGraph linked) {
        TypeChecker checker = new TypeChecker(document, linked);
        checker.run();
        return new CheckResult(new ValidationResult(checker.diagnostics), checker.portTypes);
    }

    private void run() {
        seedEventOutputs();
        for (int iteration = 0; iteration < 8; iteration++) {
            int before = resolvedCount();
            propagateOnce();
            if (resolvedCount() == before) {
                break;
            }
        }
        verifyExecEdges();
        verifyCallArguments();
    }

    private void seedEventOutputs() {
        for (GraphNode node : document.nodes()) {
            LinkedGraph.LinkedNode linkedNode = linked.node(node.id());
            if (linkedNode != null && node.type().equals("event") && linkedNode.event() != null) {
                for (ParamDescriptor param : linkedNode.event().payload()) {
                    setPort(node.id(), param.name(), param.type());
                }
            }
            for (Map.Entry<String, TypeRef> entry : staticOutputs(node).entrySet()) {
                if (!entry.getValue().base().equals("T")) {
                    setPort(node.id(), entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void propagateOnce() {
        for (GraphEdge edge : document.edges()) {
            String fromKey = edge.from().print();
            String toKey = edge.to().print();
            TypeRef sourceType = effectiveType(edge.from());
            TypeRef targetType = declaredTargetType(edge.to());

            if (sourceType == null) {
                continue;
            }

            if (Assignability.isExec(sourceType) || (targetType != null && Assignability.isExec(targetType))) {
                setPort(edge.from().nodeId(), edge.from().port(), execType());
                setPort(edge.to().nodeId(), edge.to().port(), execType());
                continue;
            }

            bindGenerics(edge, sourceType);

            TypeRef expected = effectiveType(edge.to());
            if (expected == null) {
                setPort(edge.to().nodeId(), edge.to().port(), sourceType);
                continue;
            }
            if (!Assignability.isAssignable(sourceType, expected)) {
                diagnostics.add(Diagnostic.error("E_TYPE_MISMATCH",
                        "Port '" + fromKey + "' produces " + sourceType.print()
                                + " but '" + toKey + "' expects " + expected.print(),
                        edge.to().nodeId(), toKey));
            }
        }
    }

    private void bindGenerics(GraphEdge edge, TypeRef sourceType) {
        GraphNode targetNode = document.node(edge.to().nodeId());
        if (targetNode == null || !isGenericConsumer(targetNode.type())) {
            return;
        }
        TypeRef slot = genericBindings.get(bindingKey(targetNode.id()));
        if (slot != null && !slot.base().equals("T")) {
            return;
        }
        TypeRef bound = extractElement(sourceType);
        if (bound != null) {
            genericBindings.put(bindingKey(targetNode.id()), bound);
            recomputeGenericOutputs(targetNode.id(), bound);
        }
    }

    private boolean isGenericConsumer(String type) {
        return type.equals("for-each") || type.equals("await");
    }

    private String bindingKey(String nodeId) {
        return nodeId + ":T";
    }

    private TypeRef extractElement(TypeRef container) {
        if (container.isList() || container.isSet()
                || container.isOptional() || container.isFuture()) {
            return container.params().get(0);
        }
        return null;
    }

    private void recomputeGenericOutputs(String nodeId, TypeRef bound) {
        LinkedGraph.LinkedNode node = linked.node(nodeId);
        if (node == null) {
            return;
        }
        if (node.type().equals("for-each")) {
            setPort(nodeId, "item", bound);
        } else if (node.type().equals("await")) {
            setPort(nodeId, "value", bound);
        }
    }

    private Map<String, TypeRef> staticOutputs(GraphNode node) {
        Map<String, TypeRef> outputs = new LinkedHashMap<>();
        LinkedGraph.LinkedNode linkedNode = linked.node(node.id());
        if (linkedNode == null) {
            return outputs;
        }
        switch (node.type()) {
            case "call" -> {
                Overload overload = linkedNode.overload();
                if (overload != null && !overload.returnType().base().equals("Void")) {
                    outputs.put("result", overload.returnType());
                }
                outputs.put("then", execType());
            }
            case "get-property" -> outputs.put("value", linkedNode.property().valueType());
            case "if" -> {
                outputs.put("then", execType());
                outputs.put("else", execType());
            }
            case "sequence" -> outputs.put("step0", execType());
            case "loop" -> {
                outputs.put("body", execType());
                outputs.put("done", execType());
            }
            case "for-each" -> {
                outputs.put("body", execType());
                outputs.put("done", execType());
                TypeRef bound = genericBindings.get(bindingKey(node.id()));
                outputs.put("item", bound != null ? bound : TypeRef.of("T"));
                outputs.put("index", TypeRef.INT);
            }
            case "await" -> {
                TypeRef bound = genericBindings.get(bindingKey(node.id()));
                outputs.put("value", bound != null ? bound : TypeRef.of("T"));
            }
            case "http-get", "http-post", "http-put", "http-delete" -> {
                outputs.put("response", TypeRef.of("HttpResponse"));
                outputs.put("then", execType());
            }
            case "db-query" -> outputs.put("rows",
                    TypeRef.list(TypeRef.map(TypeRef.STRING, TypeRef.of("Object"))));
            case "delay", "schedule", "log", "try-catch-finally", "retry", "timeout",
                    "set-variable", "transaction" -> outputs.put("then", execType());
            default -> {
            }
        }
        return outputs;
    }

    private TypeRef declaredTargetType(PortAddress address) {
        GraphNode node = document.node(address.nodeId());
        if (node == null) {
            return null;
        }
        LinkedGraph.LinkedNode linkedNode = linked.node(node.id());
        if (linkedNode == null) {
            return null;
        }
        switch (node.type()) {
            case "call" -> {
                Overload overload = linkedNode.overload();
                if (overload == null) {
                    return null;
                }
                for (ParamDescriptor param : overload.params()) {
                    if (param.name().equals(address.port())) {
                        return param.type();
                    }
                }
                if (address.port().equals("exec")) {
                    return execType();
                }
                return null;
            }
            case "set-property" -> {
                if (address.port().equals("value")) {
                    return linkedNode.property().valueType();
                }
                if (decapitalize(linkedNode.property().ownerType().base()).equals(address.port())) {
                    return linkedNode.property().ownerType();
                }
                if (address.port().equals("exec")) {
                    return execType();
                }
                return null;
            }
            case "get-property" -> {
                if (decapitalize(linkedNode.property().ownerType().base()).equals(address.port())) {
                    return linkedNode.property().ownerType();
                }
                return null;
            }
            case "set-variable" -> {
                if (address.port().equals("value")) {
                    return variableTypes.getOrDefault(linkedNode.variableName(), null);
                }
                if (address.port().equals("exec")) {
                    return execType();
                }
                return null;
            }
            case "for-each" -> {
                if (address.port().equals("list")) {
                    TypeRef bound = genericBindings.get(bindingKey(node.id()));
                    return TypeRef.list(bound != null ? bound : TypeRef.of("T"));
                }
                if (address.port().equals("exec") || address.port().equals("body")
                        || address.port().equals("done")) {
                    return execType();
                }
                return null;
            }
            case "await" -> {
                if (address.port().equals("future")) {
                    TypeRef bound = genericBindings.get(bindingKey(node.id()));
                    return TypeRef.future(bound != null ? bound : TypeRef.of("T"));
                }
                return null;
            }
            default -> {
                if (address.port().equals("exec") || isFlowOutputPort(node.type(), address.port())) {
                    return execType();
                }
                return knownPortType(address);
            }
        }
    }

    private boolean isFlowOutputPort(String nodeType, String port) {
        return port.equals("then") || port.equals("else") || port.equals("body")
                || port.equals("done") || port.startsWith("step");
    }

    private TypeRef knownPortType(PortAddress address) {
        return portTypes.get(address.print());
    }

    private TypeRef effectiveType(PortAddress address) {
        TypeRef known = portTypes.get(address.print());
        if (known != null) {
            return known;
        }
        return declaredTargetType(address);
    }

    private void verifyExecEdges() {
        for (GraphEdge edge : document.edges()) {
            TypeRef sourceType = portTypes.get(edge.from().print());
            TypeRef targetType = portTypes.get(edge.to().print());
            boolean sourceExec = sourceType != null && Assignability.isExec(sourceType);
            boolean targetExec = targetType != null && Assignability.isExec(targetType);
            if (sourceExec && targetType != null && !targetExec) {
                diagnostics.add(Diagnostic.error("E_EXEC_MISMATCH",
                        "Execution edge '" + edge.from().print() + "' cannot feed data port '"
                                + edge.to().print() + "'",
                        edge.to().nodeId(), edge.to().print()));
            }
            if (targetExec && sourceType != null && !sourceExec) {
                diagnostics.add(Diagnostic.error("E_EXEC_MISMATCH",
                        "Data edge '" + edge.from().print() + "' cannot drive execution port '"
                                + edge.to().print() + "'",
                        edge.from().nodeId(), edge.from().print()));
            }
        }
    }

    private void verifyCallArguments() {
        for (GraphNode node : document.nodes()) {
            if (!node.type().equals("call")) {
                continue;
            }
            LinkedGraph.LinkedNode linkedNode = linked.node(node.id());
            if (linkedNode == null || linkedNode.overload() == null) {
                continue;
            }
            JsonNode args = node.get("args");
            for (ParamDescriptor param : linkedNode.overload().params()) {
                boolean connected = hasIncomingDataEdge(node.id(), param.name());
                JsonNode literal = args == null ? null : args.get(param.name());
                if (!connected && literal == null) {
                    diagnostics.add(Diagnostic.error("E_MISSING_INPUT",
                            "Call node input '" + param.name() + "' has neither a connection"
                                    + " nor a literal value",
                            node.id(), node.id() + "." + param.name()));
                    continue;
                }
                if (literal != null && !connected) {
                    checkLiteral(node.id(), param, literal);
                }
            }
        }
    }

    private boolean hasIncomingDataEdge(String nodeId, String port) {
        for (GraphEdge edge : document.edges()) {
            if (edge.to().nodeId().equals(nodeId) && edge.to().port().equals(port)) {
                TypeRef sourceType = portTypes.get(edge.from().print());
                if (sourceType == null || !Assignability.isExec(sourceType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkLiteral(String nodeId, ParamDescriptor param, JsonNode literal) {
        TypeRef literalType = literalNodeType(literal);
        if (literalType == null) {
            return;
        }
        if (!Assignability.isAssignable(literalType, param.type())) {
            diagnostics.add(Diagnostic.error("E_TYPE_MISMATCH",
                    "Literal value " + literal + " has type " + literalType.print()
                            + ", not assignable to parameter '"
                            + param.name() + "' of type " + param.type().print(),
                    nodeId, nodeId + "." + param.name()));
        }
    }

    private TypeRef literalNodeType(JsonNode literal) {
        JsonNode value = literal;
        if (value.isObject() && value.hasNonNull("kind")
                && "literal".equals(value.get("kind").asText()) && value.has("value")) {
            value = value.get("value");
        } else if (value.isObject()) {
            return null;
        }
        if (value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return TypeRef.STRING;
        }
        if (value.isInt() || value.isLong()) {
            return TypeRef.INT;
        }
        if (value.isBoolean()) {
            return TypeRef.BOOLEAN;
        }
        if (value.isFloatingPointNumber()) {
            return TypeRef.FLOAT;
        }
        return null;
    }

    private void setPort(String nodeId, String port, TypeRef type) {
        portTypes.putIfAbsent(new PortAddress(nodeId, port).print(), type);
    }

    private TypeRef execType() {
        return TypeRef.of(EXEC_BASE);
    }

    private int resolvedCount() {
        return portTypes.size();
    }

    private TypeRef parseType(String text) {
        try {
            return TypeRef.parse(text);
        } catch (IllegalArgumentException e) {
            diagnostics.add(Diagnostic.error("E_INVALID_TYPE", e.getMessage()));
            return TypeRef.of("Unknown");
        }
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
