package com.example;

import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ObjectMessage;
import org.jgroups.Receiver;
import org.jgroups.View;
import org.jgroups.util.Util;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Console chat extended with a WAL-backed replicated key-value map. */
public final class ReplicatedChat implements Receiver, AutoCloseable {
    private static final String DEFAULT_CLUSTER = "JGroupsReplicatedChat";
    private static final String DEFAULT_CONFIG = "jgroups.xml";
    private static final long STATE_TIMEOUT_MILLIS = 10_000L;

    private final Object stateLock = new Object();
    private final String nodeName;
    private final String clusterName;
    private final String config;
    private final InputStream input;
    private final PrintStream out;
    private final PrintStream err;
    private final ReplicatedKeyValueStore store;
    private final List<String> chatHistory = new ArrayList<>();
    private final Map<UUID, CompletableFuture<Void>> pendingStores = new ConcurrentHashMap<>();
    private JChannel channel;

    ReplicatedChat(Options options, InputStream input, PrintStream out, PrintStream err)
            throws IOException {
        nodeName = options.nodeName;
        clusterName = options.clusterName;
        config = options.config;
        this.input = Objects.requireNonNull(input, "input");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        Path walPath = options.walDirectory.resolve(safeFileName(nodeName) + ".wal");
        store = new ReplicatedKeyValueStore(walPath);
    }

    public static int run(String[] args, InputStream input, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException invalidArguments) {
            err.println(invalidArguments.getMessage());
            printUsage(err);
            return 2;
        }
        try (ReplicatedChat application = new ReplicatedChat(options, input, out, err)) {
            application.start();
            return 0;
        } catch (Exception failure) {
            err.println("Fatal error: " + failure.getMessage());
            failure.printStackTrace(err);
            return 1;
        }
    }

    public void start() throws Exception {
        channel = new JChannel(config);
        channel.setName(nodeName);
        channel.setReceiver(this);
        channel.connect(clusterName);
        channel.getState(null, STATE_TIMEOUT_MILLIS);

        out.printf("Node %s joined cluster %s. WAL: %s%n", nodeName, clusterName, store.walPath());
        out.println("Commands: /store <key> <value>, /state, /quit");
        eventLoop();
    }

    @Override
    public void viewAccepted(View view) { out.println("** view: " + view); }

    @Override
    public void receive(Message message) {
        Object payload = message.getObject();
        synchronized (stateLock) {
            if (payload instanceof StoreOperation) {
                StoreOperation operation = (StoreOperation) payload;
                try {
                    if (store.apply(operation)) {
                        out.printf("[store] %s=%s (id=%s, from=%s)%n", operation.getKey(),
                                operation.getValue(), operation.getId(), message.getSrc());
                    }
                    CompletableFuture<Void> pending = pendingStores.remove(operation.getId());
                    if (pending != null) pending.complete(null);
                } catch (IOException persistenceFailure) {
                    err.printf("[store] NOT applied; WAL persistence failed for %s: %s%n",
                            operation.getId(), persistenceFailure.getMessage());
                    CompletableFuture<Void> pending = pendingStores.remove(operation.getId());
                    if (pending != null) pending.completeExceptionally(persistenceFailure);
                }
                return;
            }
            if (payload instanceof String) {
                String line = message.getSrc() + ": " + payload;
                chatHistory.add(line);
                out.println(line);
            } else {
                err.println("Ignored unsupported message type from " + message.getSrc());
            }
        }
    }

    @Override
    public void getState(OutputStream output) throws Exception {
        synchronized (stateLock) {
            Util.objectToStream(new ClusterState(chatHistory, store.operationHistory()),
                    new DataOutputStream(output));
        }
    }

    @Override
    public void setState(InputStream inputStream) throws Exception {
        ClusterState state = (ClusterState) Util.objectFromStream(new DataInputStream(inputStream));
        synchronized (stateLock) {
            store.installState(state.getStoreHistory());
            chatHistory.clear();
            chatHistory.addAll(state.getChatHistory());
            if (!chatHistory.isEmpty()) {
                out.println("--- transferred chat history ---");
                for (String line : chatHistory) out.println(line);
                out.println("--- end history ---");
            }
            out.println("Transferred map state: " + store.snapshot());
        }
    }

    Map<String, String> localState() {
        synchronized (stateLock) { return store.snapshot(); }
    }

    @Override
    public void close() throws Exception {
        Exception first = null;
        if (channel != null) {
            try { channel.close(); } catch (Exception failure) { first = failure; }
        }
        try { store.close(); } catch (Exception failure) {
            if (first == null) first = failure; else first.addSuppressed(failure);
        }
        if (first != null) throw first;
    }

    private void eventLoop() throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        while (true) {
            out.print("> ");
            out.flush();
            String line = reader.readLine();
            if (line == null || "/quit".equals(line.trim())) return;
            if (line.trim().isEmpty()) continue;
            if ("/state".equals(line.trim())) {
                out.println(localState());
            } else if (line.startsWith("/store")) {
                StoreCommand command = StoreCommand.parse(line);
                if (command == null) out.println("Usage: /store <key> <value>");
                else broadcastAndAwait(command);
            } else if (line.startsWith("/")) {
                out.println("Unknown command. Use /store, /state or /quit.");
            } else {
                channel.send(new ObjectMessage(null, line));
            }
        }
    }

    /** Do not accept the next command until this node has durably applied its own multicast. */
    private void broadcastAndAwait(StoreCommand command) throws Exception {
        StoreOperation operation = StoreOperation.create(command.key, command.value);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        pendingStores.put(operation.getId(), completion);
        try {
            channel.send(new ObjectMessage(null, operation));
            completion.get(STATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } finally {
            pendingStores.remove(operation.getId());
        }
    }

    private static String safeFileName(String nodeName) {
        if (!nodeName.matches("[A-Za-z0-9._-]+")
                || ".".equals(nodeName) || "..".equals(nodeName)) {
            throw new IllegalArgumentException(
                    "Node name may contain only letters, digits, '.', '_' and '-'");
        }
        return nodeName;
    }

    private static void printUsage(PrintStream stream) {
        stream.println("Usage: App <node-name> [--wal-dir <directory>] "
                + "[--cluster <name>] [--config <JGroups XML>]");
    }

    private static final class StoreCommand {
        private final String key;
        private final String value;
        private StoreCommand(String key, String value) { this.key = key; this.value = value; }

        private static StoreCommand parse(String line) {
            if (line.length() <= 6 || !Character.isWhitespace(line.charAt(6))) return null;
            String arguments = line.substring(7).trim();
            int separator = firstWhitespace(arguments);
            if (separator <= 0) return null;
            String value = arguments.substring(separator).trim();
            return value.isEmpty() ? null
                    : new StoreCommand(arguments.substring(0, separator), value);
        }

        private static int firstWhitespace(String text) {
            for (int i = 0; i < text.length(); i++) {
                if (Character.isWhitespace(text.charAt(i))) return i;
            }
            return -1;
        }
    }

    static final class Options {
        private final String nodeName;
        private final Path walDirectory;
        private final String clusterName;
        private final String config;

        private Options(String nodeName, Path walDirectory, String clusterName, String config) {
            this.nodeName = nodeName;
            this.walDirectory = walDirectory;
            this.clusterName = clusterName;
            this.config = config;
        }

        private static Options parse(String[] args) {
            if (args.length == 0 || args[0].startsWith("--")) {
                throw new IllegalArgumentException("A unique node name is required.");
            }
            String nodeName = args[0].trim();
            if (nodeName.isEmpty()) throw new IllegalArgumentException("Node name cannot be empty.");
            Path walDirectory = Paths.get("wal");
            String clusterName = DEFAULT_CLUSTER;
            // The project stack includes SEQUENCER so all members use the same
            // delivery order even when different nodes update one key concurrently.
            String config = DEFAULT_CONFIG;
            for (int i = 1; i < args.length; i++) {
                String option = args[i];
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + option);
                String value = args[++i];
                switch (option) {
                    case "--wal-dir": walDirectory = Paths.get(value); break;
                    case "--cluster": clusterName = value; break;
                    case "--config": config = value; break;
                    default: throw new IllegalArgumentException("Unknown option: " + option);
                }
            }
            return new Options(nodeName, walDirectory, clusterName, config);
        }
    }
}
