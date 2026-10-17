package com.example;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Chat and store histories transferred to a joining member. */
final class ClusterState implements Serializable {
    private static final long serialVersionUID = 1L;
    private final List<String> chatHistory;
    private final List<StoreOperation> storeHistory;

    ClusterState(List<String> chatHistory, List<StoreOperation> storeHistory) {
        this.chatHistory = new ArrayList<>(chatHistory);
        this.storeHistory = new ArrayList<>(storeHistory);
    }

    List<String> getChatHistory() { return Collections.unmodifiableList(chatHistory); }
    List<StoreOperation> getStoreHistory() { return Collections.unmodifiableList(storeHistory); }
}
