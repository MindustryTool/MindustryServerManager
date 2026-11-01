package plugin.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class LootTable<T> {
    private final List<Entry<T>> entries = new ArrayList<>();
    private double totalWeight = 0.0;

    public static final class Entry<T> {
        private final double weight;
        private final T value;

        public Entry(double weight, T value) {
            this.weight = weight;
            this.value = value;
        }

        public double getWeight() {
            return weight;
        }

        public T getValue() {
            return value;
        }
    }

    public LootTable<T> add(double weight, T value) {
        if (weight <= 0) {
            throw new IllegalArgumentException("Weight must be positive: " + weight);
        }
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        entries.add(new Entry<>(weight, value));
        totalWeight += weight;
        return this;
    }

    public T roll(Random random) {
        if (entries.isEmpty() || totalWeight <= 0) {
            throw new IllegalStateException("Cannot roll on empty loot table");
        }
        double target = random.nextDouble() * totalWeight;
        double current = 0.0;
        for (Entry<T> entry : entries) {
            current += entry.weight;
            if (target <= current) {
                return entry.value;
            }
        }
        return entries.get(entries.size() - 1).value;
    }

    public double getTotalWeight() {
        return totalWeight;
    }

    public List<Entry<T>> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
