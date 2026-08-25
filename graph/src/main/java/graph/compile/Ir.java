package graph.compile;

import java.util.List;

import graph.registry.ParamDescriptor;
import graph.types.TypeRef;

public final class Ir {

    private Ir() {
    }

    public record IrGraph(String graphId, List<IrEntry> entries) {
    }

    public record IrEntry(String eventNodeId, String eventId, List<ParamDescriptor> payload,
                          List<IrStmt> body) {
    }

    public sealed interface IrExpr {
        TypeRef type();
    }

    public record PortRef(String nodeId, String port, TypeRef type) implements IrExpr {
    }

    public record LiteralValue(TypeRef type, String javaSource) implements IrExpr {
    }

    public sealed interface IrStmt {
        String nodeId();
    }

    public record InvokeStmt(String nodeId, String functionId, String overloadHash,
                             String ownerClass,
                             String staticMethod, List<IrExpr> args, String resultVar,
                             TypeRef resultType, boolean asyncDispatch) implements IrStmt {
    }

    public record IfStmt(String nodeId, IrExpr condition, List<IrStmt> thenBranch,
                         List<IrStmt> elseBranch) implements IrStmt {
    }

    public record SequenceStmt(String nodeId, List<List<IrStmt>> steps) implements IrStmt {
    }

    public record ForEachStmt(String nodeId, IrExpr list, String itemName, TypeRef itemType,
                              boolean withIndex, List<IrStmt> body) implements IrStmt {
    }

    public record WhileLoopStmt(String nodeId, IrExpr count, List<IrStmt> body) implements IrStmt {
    }

    public record ScheduleStmt(String nodeId, String mode, IrExpr seconds,
                               List<IrStmt> onFire, String resultVar) implements IrStmt {
    }

    public record CancelScheduleStmt(String nodeId, IrExpr handle) implements IrStmt {
    }

    public record SetVariableStmt(String nodeId, String variable, String scope,
                                  IrExpr value) implements IrStmt {
    }

    public record GetVariableStmt(String nodeId, String variable, String scope,
                                  String resultVar, TypeRef type) implements IrStmt {
    }

    public record PropertyGetStmt(String nodeId, String propertyId, IrExpr owner,
                                  String resultVar, TypeRef resultType) implements IrStmt {
    }

    public record PropertySetStmt(String nodeId, String propertyId, IrExpr owner,
                                  IrExpr value) implements IrStmt {
    }

    public record DelayStmt(String nodeId, IrExpr seconds, int resumeSlot) implements IrStmt {
    }

    public record ScheduleOnceStmt(String nodeId, IrExpr seconds, int resumeSlot) implements IrStmt {
    }

    public record AwaitStmt(String nodeId, IrExpr future, String resultVar, TypeRef resultType,
                            Double timeoutSeconds, int resumeSlot) implements IrStmt {
    }

    public record HttpCallStmt(String nodeId, String method, IrExpr url, IrExpr headers,
                               IrExpr query, IrExpr body, String responseVar,
                               int resumeSlot) implements IrStmt {
    }

    public record DbStmt(String nodeId, String kind, IrExpr sqlOrTable, IrExpr paramsOrRow,
                         String resultVar, TypeRef resultType, int resumeSlot) implements IrStmt {
    }

    public record LogStmt(String nodeId, IrExpr message) implements IrStmt {
    }

    public record CodeFragmentStmt(String nodeId) implements IrStmt {
    }

    public record ThrowStmt(String nodeId, IrExpr message) implements IrStmt {
    }

    public record ReturnStmt(String nodeId) implements IrStmt {
    }

    public record Nop(String nodeId) implements IrStmt {
    }
}
