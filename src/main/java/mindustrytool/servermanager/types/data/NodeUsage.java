package mindustrytool.servermanager.types.data;

import java.time.Instant;

public record NodeUsage(float cpu, float ram, Instant timestamp) {
}
