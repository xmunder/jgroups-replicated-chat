package com.example;

/** Application entry point. */
public final class App {
    private App() {
    }

    public static void main(String[] args) {
        int exitCode = ReplicatedChat.run(args, System.in, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
