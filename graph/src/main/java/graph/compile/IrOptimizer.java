package graph.compile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class IrOptimizer {

    private IrOptimizer() {
    }

    public static Ir.IrGraph optimize(Ir.IrGraph graph, ThreadCheckResult threads) {
        List<Ir.IrEntry> entries = new ArrayList<>();
        for (Ir.IrEntry entry : graph.entries()) {
            List<Ir.IrStmt> body = entry.body();
            body = foldLiterals(body);
            DedupResult dedup = dedupPureCalls(body, threads);
            body = eliminateDeadResults(dedup.statements(), threads);
            if (!dedup.alias().isEmpty()) {
                UnaryOperator<String> alias = nodeId -> {
                    String mapped = nodeId;
                    String step;
                    while ((step = dedup.alias().get(mapped)) != null) {
                        mapped = step;
                    }
                    return mapped;
                };
                body = rewriteNodes(body, alias);
            }
            entries.add(new Ir.IrEntry(entry.eventNodeId(), entry.eventId(),
                    entry.payload(), body));
        }
        return new Ir.IrGraph(graph.graphId(), entries);
    }

    static List<Ir.IrStmt> foldLiterals(List<Ir.IrStmt> body) {
        List<Ir.IrStmt> result = new ArrayList<>();
        for (Ir.IrStmt stmt : body) {
            if (stmt instanceof Ir.IfStmt ifStmt
                    && ifStmt.condition() instanceof Ir.LiteralValue literal) {
                boolean truthy = "true".equals(literal.javaSource());
                result.addAll(foldLiterals(truthy ? ifStmt.thenBranch() : ifStmt.elseBranch()));
            } else {
                result.add(rewriteStmt(stmt,
                        expr -> expr, inner -> foldLiterals(inner)));
            }
        }
        return result;
    }

    record DedupResult(List<Ir.IrStmt> statements, Map<String, String> alias) {
    }

    static DedupResult dedupPureCalls(List<Ir.IrStmt> body, ThreadCheckResult threads) {
        Map<String, Ir.InvokeStmt> seen = new HashMap<>();
        Map<String, String> alias = new HashMap<>();
        List<Ir.IrStmt> kept = new ArrayList<>();
        for (Ir.IrStmt stmt : body) {
            if (!(stmt instanceof Ir.InvokeStmt invoke)
                    || !threads.isPure(invoke.nodeId())
                    || invoke.resultVar() == null
                    || invoke.asyncDispatch()) {
                kept.add(stmt);
                continue;
            }
            String fingerprint = invoke.functionId() + "|" + invoke.args();
            Ir.InvokeStmt previous = seen.get(fingerprint);
            if (previous == null) {
                seen.put(fingerprint, invoke);
                kept.add(invoke);
            } else {
                alias.put(invoke.nodeId(), previous.nodeId());
            }
        }
        return new DedupResult(kept, alias);
    }

    static List<Ir.IrStmt> eliminateDeadResults(List<Ir.IrStmt> body, ThreadCheckResult threads) {
        Set<String> referencedVars = new HashSet<>();
        collectVarRefs(body, referencedVars);
        List<Ir.IrStmt> kept = new ArrayList<>();
        for (Ir.IrStmt stmt : body) {
            if (stmt instanceof Ir.InvokeStmt invoke
                    && invoke.resultVar() != null
                    && threads.isPure(invoke.nodeId())
                    && !invoke.asyncDispatch()
                    && !referencedVars.contains(invoke.resultVar())) {
                continue;
            }
            kept.add(stmt);
        }
        return kept;
    }

    private static void collectVarRefs(List<Ir.IrStmt> body, Set<String> into) {
        forEachExpr(body, expr -> {
            if (expr instanceof Ir.PortRef ref) {
                into.add("v_" + ref.nodeId().replace('-', '_'));
            }
        });
    }

    interface ExprVisitor {
        void visit(Ir.IrExpr expr);
    }

    private static void forEachExpr(List<Ir.IrStmt> body, ExprVisitor visitor) {
        for (Ir.IrStmt stmt : body) {
            forEachExprIn(stmt, visitor);
        }
    }

    private static void forEachExprIn(Ir.IrStmt stmt, ExprVisitor visitor) {
        if (stmt instanceof Ir.InvokeStmt invoke) {
            invoke.args().forEach(visitor::visit);
        } else if (stmt instanceof Ir.LogStmt log) {
            visitor.visit(log.message());
        } else if (stmt instanceof Ir.SetVariableStmt set) {
            visitor.visit(set.value());
        } else if (stmt instanceof Ir.PropertyGetStmt get) {
            visitor.visit(get.owner());
        } else if (stmt instanceof Ir.PropertySetStmt set) {
            visitor.visit(set.owner());
            visitor.visit(set.value());
        } else if (stmt instanceof Ir.IfStmt ifStmt) {
            visitor.visit(ifStmt.condition());
            forEachExpr(ifStmt.thenBranch(), visitor);
            forEachExpr(ifStmt.elseBranch(), visitor);
        } else if (stmt instanceof Ir.ForEachStmt loop) {
            visitor.visit(loop.list());
            forEachExpr(loop.body(), visitor);
        } else if (stmt instanceof Ir.WhileLoopStmt loop) {
            visitor.visit(loop.count());
            forEachExpr(loop.body(), visitor);
        }
    }

    public static List<Ir.IrStmt> rewriteNodes(List<Ir.IrStmt> body,
                                               UnaryOperator<String> nodeIdRewrite) {
        List<Ir.IrStmt> result = new ArrayList<>();
        for (Ir.IrStmt stmt : body) {
            result.add(rewriteStmt(stmt,
                    expr -> expr instanceof Ir.PortRef ref
                            ? new Ir.PortRef(nodeIdRewrite.apply(ref.nodeId()), ref.port(),
                            ref.type())
                            : expr,
                    inner -> rewriteNodes(inner, nodeIdRewrite)));
        }
        return result;
    }

    private static Ir.IrStmt rewriteStmt(Ir.IrStmt stmt,
                                         UnaryOperator<Ir.IrExpr> exprRewrite,
                                         UnaryOperator<List<Ir.IrStmt>> listRewrite) {
        if (stmt instanceof Ir.InvokeStmt invoke) {
            List<Ir.IrExpr> args = new ArrayList<>();
            for (Ir.IrExpr arg : invoke.args()) {
                args.add(exprRewrite.apply(arg));
            }
            return new Ir.InvokeStmt(invoke.nodeId(), invoke.functionId(),
                    invoke.overloadHash(), invoke.ownerClass(), invoke.staticMethod(), args,
                    invoke.resultVar(), invoke.resultType(), invoke.asyncDispatch());
        }
        if (stmt instanceof Ir.LogStmt log) {
            return new Ir.LogStmt(log.nodeId(), exprRewrite.apply(log.message()));
        }
        if (stmt instanceof Ir.SetVariableStmt set) {
            return new Ir.SetVariableStmt(set.nodeId(), set.variable(), set.scope(),
                    exprRewrite.apply(set.value()));
        }
        if (stmt instanceof Ir.PropertyGetStmt get) {
            return new Ir.PropertyGetStmt(get.nodeId(), get.propertyId(),
                    exprRewrite.apply(get.owner()), get.resultVar(), get.resultType());
        }
        if (stmt instanceof Ir.PropertySetStmt set) {
            return new Ir.PropertySetStmt(set.nodeId(), set.propertyId(),
                    exprRewrite.apply(set.owner()), exprRewrite.apply(set.value()));
        }
        if (stmt instanceof Ir.IfStmt ifStmt) {
            return new Ir.IfStmt(ifStmt.nodeId(), exprRewrite.apply(ifStmt.condition()),
                    listRewrite.apply(ifStmt.thenBranch()),
                    listRewrite.apply(ifStmt.elseBranch()));
        }
        if (stmt instanceof Ir.ForEachStmt loop) {
            return new Ir.ForEachStmt(loop.nodeId(), exprRewrite.apply(loop.list()),
                    loop.itemName(), loop.itemType(), loop.withIndex(),
                    listRewrite.apply(loop.body()));
        }
        if (stmt instanceof Ir.WhileLoopStmt loop) {
            return new Ir.WhileLoopStmt(loop.nodeId(), exprRewrite.apply(loop.count()),
                    listRewrite.apply(loop.body()));
        }
        if (stmt instanceof Ir.SequenceStmt seq) {
            List<List<Ir.IrStmt>> steps = new ArrayList<>();
            for (List<Ir.IrStmt> step : seq.steps()) {
                steps.add(listRewrite.apply(step));
            }
            return new Ir.SequenceStmt(seq.nodeId(), steps);
        }
        return stmt;
    }
}
