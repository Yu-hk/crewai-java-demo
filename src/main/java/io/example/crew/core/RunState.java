package io.example.crew.core;

import io.example.crew.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次运行的持久化快照，是断点续跑的载体。
 *
 * <p>设计上的关键取舍：<b>只存原始文本，不存结构化对象</b>。
 * 结构化产出在恢复时用 {@code outputType} 重新绑定一遍即可，代价是一次本地解析，
 * 换来的是不必为任意 POJO 写序列化器。存序列化后的对象反而更脆弱——
 * 改了字段类型，旧存档就再也读不回来。
 *
 * <p>任务用<b>序号</b>对齐而不是标签：标签允许重复、允许中途改名，
 * 而序号与 {@code Crew.tasks} 的顺序一一对应，恢复时不会错位。
 */
public final class RunState {

    /** 运行状态。 */
    public enum Status {
        RUNNING, DONE, FAILED
    }

    /**
     * 单个已完成任务的存档。
     *
     * @param label     任务标签，仅用于恢复时做一致性校验
     * @param agentRole 执行者角色
     * @param raw       模型原始产出
     * @param millis    该任务耗时
     * @param attempts  执行次数
     * @param notes     降级与损耗记录
     */
    public record Record(
            String label,
            String agentRole,
            String raw,
            long millis,
            int attempts,
            List<String> notes) {

        public Record {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        static Record of(TaskOutput output) {
            return new Record(
                    output.task().label(),
                    output.agentRole(),
                    output.raw(),
                    output.millis(),
                    output.attempts(),
                    output.notes());
        }
    }

    private final String runId;
    private final String crewName;
    private final Map<String, String> inputs;
    private final List<Record> completed;
    private final long createdAt;
    private Status status;
    private long updatedAt;

    private RunState(String runId, String crewName, Map<String, String> inputs,
                     List<Record> completed, Status status, long createdAt, long updatedAt) {
        this.runId = runId;
        this.crewName = crewName;
        this.inputs = new LinkedHashMap<>(inputs == null ? Map.of() : inputs);
        this.completed = new ArrayList<>(completed == null ? List.of() : completed);
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static RunState newRun(String runId, String crewName, Map<String, String> inputs) {
        long now = System.currentTimeMillis();
        return new RunState(runId, crewName, inputs, List.of(), Status.RUNNING, now, now);
    }

    public String runId() {
        return runId;
    }

    public String crewName() {
        return crewName;
    }

    public Map<String, String> inputs() {
        return Map.copyOf(inputs);
    }

    public List<Record> completed() {
        return List.copyOf(completed);
    }

    public Status status() {
        return status;
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    /** 追加一条已完成任务并置为脏，调用方随后应 {@link RunStore#save}。 */
    void append(TaskOutput output) {
        completed.add(Record.of(output));
        updatedAt = System.currentTimeMillis();
    }

    void status(Status value) {
        this.status = value;
        this.updatedAt = System.currentTimeMillis();
    }

    /** 序列化为可交给 {@link Json#write} 的结构。 */
    public Map<String, Object> toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("runId", runId);
        root.put("crewName", crewName);
        root.put("status", status.name());
        root.put("createdAt", createdAt);
        root.put("updatedAt", updatedAt);
        root.put("inputs", new LinkedHashMap<String, Object>(inputs));

        List<Object> list = new ArrayList<>(completed.size());
        for (Record r : completed) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", r.label());
            item.put("agentRole", r.agentRole());
            item.put("raw", r.raw());
            item.put("millis", r.millis());
            item.put("attempts", r.attempts());
            item.put("notes", r.notes());
            list.add(item);
        }
        root.put("completed", list);
        return root;
    }

    /** 从解析后的 JSON 对象还原。字段缺失时按空值处理，不抛异常——存档损坏不应阻断主流程。 */
    public static RunState fromJson(Map<String, Object> root) {
        Map<String, String> inputs = new LinkedHashMap<>();
        if (root.get("inputs") instanceof Map<?, ?> raw) {
            raw.forEach((k, v) -> inputs.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }

        List<Record> records = new ArrayList<>();
        if (root.get("completed") instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> mm = cast(m);
                List<String> notes = new ArrayList<>();
                if (mm.get("notes") instanceof List<?> ns) {
                    ns.forEach(n -> notes.add(String.valueOf(n)));
                }
                records.add(new Record(
                        str(mm, "label"),
                        str(mm, "agentRole"),
                        str(mm, "raw"),
                        Json.num(mm, "millis"),
                        (int) Json.num(mm, "attempts"),
                        notes));
            }
        }

        String statusText = str(root, "status");
        Status status;
        try {
            status = Status.valueOf(statusText == null ? "RUNNING" : statusText);
        } catch (IllegalArgumentException e) {
            status = Status.RUNNING;
        }

        return new RunState(
                str(root, "runId"),
                str(root, "crewName"),
                inputs,
                records,
                status,
                Json.num(root, "createdAt"),
                Json.num(root, "updatedAt"));
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
