package com.example;

import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ObjectMessage;
import org.jgroups.Receiver;
import org.jgroups.util.Util;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class JGroupsClusterIntegrationTest {
    @Test
    public void executesTheFourWorkshopExperimentsWithThreeNodes() throws Exception {
        Path walDirectory = Files.createTempDirectory("jgroups-cluster-test");
        String cluster = "test-" + UUID.randomUUID();
        Node virginia = new Node(walDirectory.resolve("Virginia.wal"));
        Node london = new Node(walDirectory.resolve("London.wal"));
        Node bogota = new Node(walDirectory.resolve("Bogota.wal"));

        try (JChannel first = new JChannel("sequencer.xml").setName("Virginia").setReceiver(virginia);
             JChannel second = new JChannel("sequencer.xml").setName("London").setReceiver(london);
             JChannel third = new JChannel("sequencer.xml").setName("Bogota").setReceiver(bogota)) {
            first.connect(cluster);
            second.connect(cluster);

            first.send(new ObjectMessage(null, "Hola desde Virginia"));
            awaitChat(virginia, "Virginia: Hola desde Virginia");
            awaitChat(london, "Virginia: Hola desde Virginia");
            System.out.println("[EXPERIMENTO 1] Chat entregado a Virginia y London");

            StoreOperation beforeJoin = StoreOperation.create("framework", "JGroups");
            first.send(new ObjectMessage(null, beforeJoin));
            awaitValue(virginia, "framework", "JGroups");
            awaitValue(london, "framework", "JGroups");
            System.out.println("[EXPERIMENTO 2] /store replicado: " + virginia.store.snapshot()
                    + " == " + london.store.snapshot());

            third.connect(cluster);
            third.getState(null, 10_000L);
            assertEquals("JGroups", bogota.store.snapshot().get("framework"));
            assertEquals(1, bogota.store.operationHistory().size());
            assertEquals(Collections.singletonList("Virginia: Hola desde Virginia"),
                    bogota.chatHistorySnapshot());
            System.out.println("[EXPERIMENTO 3] Bogota recibió state transfer: "
                    + bogota.store.snapshot());

            StoreOperation afterJoin = StoreOperation.create("status", "replicated");
            third.send(new ObjectMessage(null, afterJoin));
            awaitValue(virginia, "status", "replicated");
            awaitValue(london, "status", "replicated");
            awaitValue(bogota, "status", "replicated");

            // A replay is delivered but does not get appended or applied twice.
            first.send(new ObjectMessage(null, afterJoin));
            Thread.sleep(200L);
            assertEquals(2, virginia.store.operationHistory().size());
            assertEquals(2, london.store.operationHistory().size());
            assertEquals(2, bogota.store.operationHistory().size());
        } finally {
            virginia.close();
            london.close();
            bogota.close();
        }

        try (ReplicatedKeyValueStore recovered =
                     new ReplicatedKeyValueStore(walDirectory.resolve("Bogota.wal"))) {
            assertEquals("JGroups", recovered.snapshot().get("framework"));
            assertEquals("replicated", recovered.snapshot().get("status"));
            assertEquals(2, recovered.operationHistory().size());
            System.out.println("[EXPERIMENTO 4] Bogota recuperó su WAL: "
                    + recovered.snapshot() + "; replay duplicado ignorado");
        }
    }

    private static void awaitChat(Node node, String expected) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!node.chatHistorySnapshot().contains(expected)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for chat message: " + expected);
            }
            Thread.sleep(20L);
        }
    }

    private static void awaitValue(Node node, String key, String expected) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!expected.equals(node.store.snapshot().get(key))) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for " + key + "=" + expected);
            }
            Thread.sleep(20L);
        }
    }

    private static final class Node implements Receiver, AutoCloseable {
        private final ReplicatedKeyValueStore store;
        private final List<String> chatHistory = new ArrayList<>();

        private Node(Path wal) throws Exception {
            store = new ReplicatedKeyValueStore(wal);
        }

        @Override
        public void receive(Message message) {
            try {
                Object payload = message.getObject();
                if (payload instanceof StoreOperation) {
                    store.apply((StoreOperation) payload);
                } else if (payload instanceof String) {
                    synchronized (chatHistory) {
                        chatHistory.add(message.getSrc() + ": " + payload);
                    }
                }
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        }

        @Override
        public void getState(OutputStream output) throws Exception {
            synchronized (store) {
                Util.objectToStream(new ClusterState(chatHistorySnapshot(),
                        store.operationHistory()), new DataOutputStream(output));
            }
        }

        @Override
        public void setState(InputStream input) throws Exception {
            ClusterState state = (ClusterState) Util.objectFromStream(new DataInputStream(input));
            store.installState(state.getStoreHistory());
            synchronized (chatHistory) {
                chatHistory.clear();
                chatHistory.addAll(state.getChatHistory());
            }
        }

        private List<String> chatHistorySnapshot() {
            synchronized (chatHistory) {
                return new ArrayList<>(chatHistory);
            }
        }

        @Override
        public void close() throws Exception {
            store.close();
        }
    }
}
