package graph.registry;

import graph.runtime.InvocationContext;

@FunctionalInterface
public interface Invoker {

    Object invoke(String overloadHash, Object[] args, InvocationContext context) throws Exception;
}
