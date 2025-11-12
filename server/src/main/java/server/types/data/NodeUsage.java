package server.types.data;

import java.time.Instant;

public record NodeUsage(Float cpu, Float ram, Instant createdAt) {
}
