package com.example;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReplicatedKeyValueStoreTest {
    @Test
    public void persistsBeforeReplayAndRestoresUnicodeValues() throws Exception {
        Path directory = Files.createTempDirectory("jgroups-wal-test");
        Path wal = directory.resolve("Bogota.wal");
        StoreOperation first = new StoreOperation(UUID.randomUUID(), "city", "Bogotá");
        StoreOperation second = new StoreOperation(UUID.randomUUID(), "message", "line 1\nline 2");

        try (ReplicatedKeyValueStore store = new ReplicatedKeyValueStore(wal)) {
            assertTrue(store.apply(first));
            assertTrue(store.apply(second));
        }
        try (ReplicatedKeyValueStore recovered = new ReplicatedKeyValueStore(wal)) {
            assertEquals("Bogotá", recovered.snapshot().get("city"));
            assertEquals("line 1\nline 2", recovered.snapshot().get("message"));
        }
    }

    @Test
    public void ignoresAnExactDuplicateOperation() throws Exception {
        Path wal = Files.createTempDirectory("jgroups-dedup-test").resolve("London.wal");
        StoreOperation operation = StoreOperation.create("language", "English");
        try (ReplicatedKeyValueStore store = new ReplicatedKeyValueStore(wal)) {
            assertTrue(store.apply(operation));
            long sizeAfterFirstWrite = Files.size(wal);
            assertFalse(store.apply(operation));
            assertEquals(sizeAfterFirstWrite, Files.size(wal));
            assertEquals(1, store.operationHistory().size());
        }
    }

    @Test
    public void transferredStateReplacesWalAndSurvivesRestart() throws Exception {
        Path wal = Files.createTempDirectory("jgroups-state-test").resolve("Virginia.wal");
        StoreOperation stale = StoreOperation.create("stale", "local");
        StoreOperation one = StoreOperation.create("one", "1");
        StoreOperation two = StoreOperation.create("two", "2");
        try (ReplicatedKeyValueStore store = new ReplicatedKeyValueStore(wal)) {
            store.apply(stale);
            store.installState(Arrays.asList(one, two));
            assertFalse(store.snapshot().containsKey("stale"));
        }
        try (ReplicatedKeyValueStore recovered = new ReplicatedKeyValueStore(wal)) {
            assertEquals(2, recovered.snapshot().size());
            assertEquals("1", recovered.snapshot().get("one"));
            assertEquals("2", recovered.snapshot().get("two"));
        }
    }

    @Test
    public void repairsAnIncompleteFinalWalRecord() throws Exception {
        Path wal = Files.createTempDirectory("jgroups-tail-test").resolve("node.wal");
        StoreOperation operation = StoreOperation.create("safe", "value");
        try (ReplicatedKeyValueStore store = new ReplicatedKeyValueStore(wal)) {
            store.apply(operation);
        }
        Files.write(wal, "partial-record".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        try (ReplicatedKeyValueStore recovered = new ReplicatedKeyValueStore(wal)) {
            assertEquals("value", recovered.snapshot().get("safe"));
            assertEquals(1, recovered.operationHistory().size());
        }
    }
}
