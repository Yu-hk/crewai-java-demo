package io.example.crew.mem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 进程内存储。默认实现，也是所有断言的基准。 */
public final class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, MemoryItem> items = new LinkedHashMap<>();

    @Override
    public synchronized void put(MemoryItem item) {
        items.put(item.id(), item);
    }

    @Override
    public synchronized Optional<MemoryItem> get(String id) {
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public synchronized List<MemoryItem> all() {
        return new ArrayList<>(items.values());
    }

    @Override
    public synchronized List<MemoryItem> byScope(MemoryScope scope) {
        List<MemoryItem> out = new ArrayList<>();
        for (MemoryItem item : items.values()) {
            if (item.scope() == scope) {
                out.add(item);
            }
        }
        return out;
    }

    @Override
    public synchronized int size() {
        return items.size();
    }

    @Override
    public synchronized boolean remove(String id) {
        return items.remove(id) != null;
    }

    @Override
    public synchronized void clear() {
        items.clear();
    }
}
