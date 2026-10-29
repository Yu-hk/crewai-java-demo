package io.example.crew.mem;

import java.util.List;
import java.util.Optional;

/**
 * 记忆存储。
 *
 * <p>刻意不定义"向量检索"方法。检索需要遍历全部向量算余弦，这个动作对内存实现是免费的，
 * 对数据库实现则是要下推到 SQL 的。把它留在 {@link Memory} 里统一做，接口只是数据的容器，
 * 换存储时只需保证"能全量取出来"。
 *
 * <p>这是对当前规模的正确取舍：几千条记忆全在内存里，一次检索扫 1024 维 × 1000 条
 * 大约 2 毫秒，上向量数据库的收益要等到十万条量级才出现。
 * CrewAI 用 LanceDB/Chroma 是因为 Python 侧没有别的选择，不是因为小规模下这样做更好。
 * 真到需要时，把 {@link #byScope} 换成"带过滤条件的 ANN 查询"即可，上层不动。
 */
public interface MemoryStore {

    void put(MemoryItem item);

    Optional<MemoryItem> get(String id);

    /** 全部记忆，顺序不保证。 */
    List<MemoryItem> all();

    List<MemoryItem> byScope(MemoryScope scope);

    int size();

    boolean remove(String id);

    void clear();

    /** 把待写数据落到持久层。内存实现为空操作。 */
    default void flush() {
    }
}
