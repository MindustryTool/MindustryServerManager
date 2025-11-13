package server.types.data;

import java.time.Instant;

public record NodeUsage(double cpu, long ram, Instant createdAt) {
}
