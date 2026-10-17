package com.example;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Local materialized map plus its ordered, deduplicated operation history. */
public final class ReplicatedKeyValueStore implements Closeable {
    private final WriteAheadLog wal;
    private final HashMap<String, String> values = new HashMap<>();
    private final Map<UUID, StoreOperation> operationsById = new LinkedHashMap<>();

    public ReplicatedKeyValueStore(Path walPath) throws IOException {
        wal = new WriteAheadLog(walPath);
        for (StoreOperation operation : wal.readAll()) replay(operation);
    }

    /** Persists before applying; returns false for an exact duplicate. */
    public synchronized boolean apply(StoreOperation operation) throws IOException {
        StoreOperation previous = operationsById.get(operation.getId());
        if (previous != null) {
            if (!previous.equals(operation)) {
                throw new IOException("Operation ID collision for " + operation.getId());
            }
            return false;
        }
        wal.append(operation);
        replay(operation);
        return true;
    }

    public synchronized Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    public synchronized List<StoreOperation> operationHistory() {
        return new ArrayList<>(operationsById.values());
    }

    /** Installs cluster state only after its complete history is durable. */
    public synchronized void installState(List<StoreOperation> operations) throws IOException {
        Map<UUID, StoreOperation> validated = new LinkedHashMap<>();
        Map<String, String> rebuiltValues = new LinkedHashMap<>();
        for (StoreOperation operation : operations) {
            StoreOperation previous = validated.putIfAbsent(operation.getId(), operation);
            if (previous != null && !previous.equals(operation)) {
                throw new IOException("Operation ID collision in transferred state for "
                        + operation.getId());
            }
            if (previous == null) rebuiltValues.put(operation.getKey(), operation.getValue());
        }

        List<StoreOperation> normalized = new ArrayList<>(validated.values());
        wal.replaceAll(normalized);
        operationsById.clear();
        operationsById.putAll(validated);
        values.clear();
        values.putAll(rebuiltValues);
    }

    public Path walPath() { return wal.getPath(); }

    @Override
    public synchronized void close() throws IOException { wal.close(); }

    private void replay(StoreOperation operation) throws IOException {
        StoreOperation previous = operationsById.putIfAbsent(operation.getId(), operation);
        if (previous != null) {
            if (!previous.equals(operation)) {
                throw new IOException("Operation ID collision in WAL for " + operation.getId());
            }
            return;
        }
        values.put(operation.getKey(), operation.getValue());
    }
}
