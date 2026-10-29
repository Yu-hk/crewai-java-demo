package io.example.crew.core;

import java.util.List;
import java.util.Optional;

/**
 * 运行快照的存取抽象，对应 CrewAI 的 {@code SqliteProvider} 一行。
 *
 * <p>只定义四个动作，是因为断点续跑真正需要的就这些。做成接口而不是直接读文件，
 * 是为了让生产环境能换成 JDBC：存储介质的选择与编排逻辑无关。
 */
public interface RunStore {

    /** 读取快照，不存在返回空。 */
    Optional<RunState> load(String runId);

    /** 覆盖写入。实现须保证原子性——写一半断电不能留下不可读的存档。 */
    void save(RunState state);

    /** 已有快照的 runId 列表，按最近更新倒序。 */
    List<String> list();

    /** 删除快照。 */
    void delete(String runId);
}
