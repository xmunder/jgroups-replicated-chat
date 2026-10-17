package com.example;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** An immutable and uniquely identifiable update to the replicated map. */
public final class StoreOperation implements Serializable {
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String key;
    private final String value;

    public StoreOperation(UUID id, String key, String value) {
        this.id = Objects.requireNonNull(id, "id");
        this.key = Objects.requireNonNull(key, "key");
        this.value = Objects.requireNonNull(value, "value");
    }

    public static StoreOperation create(String key, String value) {
        return new StoreOperation(UUID.randomUUID(), key, value);
    }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getValue() { return value; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StoreOperation)) return false;
        StoreOperation that = (StoreOperation) other;
        return id.equals(that.id) && key.equals(that.key) && value.equals(that.value);
    }

    @Override
    public int hashCode() { return Objects.hash(id, key, value); }

    @Override
    public String toString() {
        return "StoreOperation{" + id + ", key='" + key + "'}";
    }
}
