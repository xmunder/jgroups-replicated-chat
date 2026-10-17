package com.example;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** A line-oriented WAL with Base64 fields for arbitrary Unicode text. */
public final class WriteAheadLog implements Closeable {
    private static final byte NEWLINE = (byte) '\n';
    private final Path path;
    private FileChannel channel;

    public WriteAheadLog(Path path) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        Path parent = this.path.getParent();
        if (parent != null) Files.createDirectories(parent);
        removeIncompleteTail();
        channel = openForAppend(this.path);
    }

    public Path getPath() { return path; }

    public synchronized List<StoreOperation> readAll() throws IOException {
        byte[] bytes = Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
        List<StoreOperation> operations = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == NEWLINE) {
                if (i > start) {
                    operations.add(decode(new String(bytes, start, i - start, StandardCharsets.UTF_8)));
                }
                start = i + 1;
            }
        }
        return operations;
    }

    /** Appends and forces the complete record before returning. */
    public synchronized void append(StoreOperation operation) throws IOException {
        writeFully(channel, ByteBuffer.wrap(encode(operation)));
        channel.force(true);
    }

    /** Atomically replaces this node's WAL with a transferred cluster history. */
    public synchronized void replaceAll(List<StoreOperation> operations) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        boolean installed = false;
        try {
            try (FileChannel replacement = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (StoreOperation operation : operations) {
                    writeFully(replacement, ByteBuffer.wrap(encode(operation)));
                }
                replacement.force(true);
            }
            channel.close();
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            installed = true;
        } finally {
            channel = openForAppend(path);
            if (!installed) Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (channel != null && channel.isOpen()) channel.close();
    }

    private void removeIncompleteTail() throws IOException {
        if (!Files.exists(path)) return;
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes[bytes.length - 1] == NEWLINE) return;
        int lastNewline = -1;
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == NEWLINE) { lastNewline = i; break; }
        }
        try (FileChannel repair = FileChannel.open(path, StandardOpenOption.WRITE)) {
            repair.truncate(lastNewline + 1L);
            repair.force(true);
        }
    }

    private static FileChannel openForAppend(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    private static void writeFully(FileChannel target, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) target.write(buffer);
    }

    private static byte[] encode(StoreOperation operation) {
        Base64.Encoder encoder = Base64.getEncoder();
        String key = encoder.encodeToString(operation.getKey().getBytes(StandardCharsets.UTF_8));
        String value = encoder.encodeToString(operation.getValue().getBytes(StandardCharsets.UTF_8));
        return (operation.getId() + "\t" + key + "\t" + value + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static StoreOperation decode(String line) throws IOException {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 3) throw new IOException("Malformed WAL record: expected 3 fields");
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            return new StoreOperation(UUID.fromString(fields[0]),
                    new String(decoder.decode(fields[1]), StandardCharsets.UTF_8),
                    new String(decoder.decode(fields[2]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalidRecord) {
            throw new IOException("Malformed WAL record", invalidRecord);
        }
    }
}
