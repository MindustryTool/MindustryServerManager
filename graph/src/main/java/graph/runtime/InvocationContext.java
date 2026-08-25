package graph.runtime;

import java.util.concurrent.atomic.AtomicLong;

public final class InvocationContext {

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final long executionId;
    private final CancellationToken cancellationToken;
    private final Budget budget;

    public InvocationContext(long executionId, CancellationToken cancellationToken, Budget budget) {
        this.executionId = executionId;
        this.cancellationToken = cancellationToken;
        this.budget = budget;
    }

    public static InvocationContext root() {
        return new InvocationContext(NEXT_ID.getAndIncrement(),
                new CancellationToken(), new Budget(Long.MAX_VALUE));
    }

    public long executionId() {
        return executionId;
    }

    public CancellationToken cancellation() {
        return cancellationToken;
    }

    public Budget budget() {
        return budget;
    }

    public void checkCancelled() {
        cancellationToken.throwIfCancelled();
    }

    public void spend(int operations) {
        budget.spend(operations);
        cancellationToken.throwIfCancelled();
    }

    public static final class Budget {
        private final long maxOperations;
        private long spent;

        public Budget(long maxOperations) {
            this.maxOperations = maxOperations;
        }

        public void spend(int operations) {
            spent += operations;
            if (spent > maxOperations) {
                throw new GraphBudgetExceeded(spent, maxOperations);
            }
        }

        public long spent() {
            return spent;
        }

        public long max() {
            return maxOperations;
        }
    }
}
