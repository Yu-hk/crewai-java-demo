# CrewAI 风格的 Java 多智能体内核（P0 + P1 + MCP + Memory 已补齐）

用 **纯 JDK 17（零第三方依赖）** 实现的 CrewAI 核心抽象，模型走**本地 Ollama**。
目的不是做一个框架，而是把 CrewAI 真正起作用的那几层拆开看清楚：
角色提示词怎么拼、上下文怎么控、工具怎么调、产出怎么验、失败怎么退、断了怎么续、
远端工具怎么接、跨运行的经验怎么留。

演进分四步：

- **P0**（契约与运行时）：结构化输出、原生 function calling、上下文预算、Guardrails、重试限流。
  补齐后能接真实业务逻辑。
- **P1**（工程化）：状态持久化与断点续跑、事件钩子、DAG 桥接。
  补齐后能进生产链路——中途挂了不用全量重跑，可观测性不用改内核，流程控制权留在代码里。
- **P2**（协议与生态）：MCP 客户端（stdio + JSON-RPC 2.0）。
  补齐后不用为每个外部能力写一遍适配代码，工具由服务端下发。
- **P3**（经验与知识）：语义记忆（四类作用域 + 复合打分 + 去重 + 持久化）与知识库（分块 + 检索）。
  补齐后跨运行的经验能沉淀下来，外部文档能按需检索注入，而不是全量塞进提示词。

## 一、能力对照（CrewAI 1.15 → 本项目）

| CrewAI (Python) | 本项目 (Java) | 状态 |
|---|---|---|
| `Agent(role, goal, backstory)` | `core/Agent` | ✅ 四段系统提示词 |
| `Agent(tools=[...])` + 原生 function calling | `tool/Tool` + `ToolRegistry.specs()` | ✅ |
| `Agent(max_iter, max_rpm)` | `Agent.maxIter` + `RateLimiter` | ✅ || `Task(description, expected_output, agent)` | `core/Task` | ✅ |
| `Task(output_pydantic=Model)` | `Task.outputType(POJO)` + `schema/Structure` | ✅ |
| `Task(guardrail=..., max_retries=)` | `Task.guardrails(...)` + `core/Guardrails` | ✅ |
| `Task(context=[...])` | `Task.context(...)` | ✅ |
| —（CrewAI 无此参数，工具只能挂 Agent） | `tool/ToolSet` + `Task.toolset(...)` | ✅ 扩展：工具按任务给 |
| `respect_context_window` | `core/ContextBudget`（token 预算 + 摘要压缩） | ✅ |
| `Crew(agents, tasks, process)` | `core/Crew` | ✅ |
| `Process.sequential` | `Process.SEQUENTIAL` | ✅ |
| `Process.hierarchical` | `Process.HIERARCHICAL` | ⚠️ 简化版：主管只做验收/打回 |
| 重试与退避 | `ResilientLlmClient` + `RetryPolicy` | ✅ |
| `CrewOutput` / `TaskOutput` | 同名 record，含用量、耗时、备注 | ✅ |
| `SqliteProvider` + checkpoint 续跑 | `core/RunStore` + `FileRunStore` + `RunState` | ✅ P1 |
| `@on` 拦截点 / `CrewFailedEvent` | `core/CrewListener`（15 个事件 + 组合器） | ✅ P1 |
| Flows（`@start/@listen/@router`） | `flow/CrewNode` + `SimpleDag` | ⚠️ P1：只做桥接，不自建 Flow |
| `Memory`（短期/长期/实体/上下文） | `mem/Memory` + 四类 `MemoryScope` | ✅ P3 |
| `Knowledge`（文档 → 分块 → 检索） | `mem/Knowledge` + `mem/Chunker` | ✅ P3 |
| `memory=True` 自动读写 | `Crew.Builder.memory(...)` | ✅ P3 |
| 并行任务（`async_execution`） | — | ❌ 未实现 |
| MCP 客户端（stdio） | `mcp/McpClient` + `McpTool` + `McpServer` | ✅ P2 |
| MCP 服务端 / A2A / 声明式配置 | — | ❌ 未实现 |

两处与 CrewAI 的**有意分歧**（都基于实测，详见第八节）：

| 项 | CrewAI | 本项目 | 原因 |
|---|---|---|---|
| 记忆重要性打分 | 再调一次 LLM 给 0~10 分 | 启发式 + 调用方覆盖 | 每写一条多一次调用；7B 上同一段文本两三次给 4/7/5 分，等于给检索注入随机噪声 |
| 检索阈值 | 依赖向量库的 score 阈值 | 相对截断为主，绝对阈值为辅 | bge-m3 上无关文本相似度最高到 0.538，与真相关的 0.56 区间重叠，绝对阈值必然误杀 |


## 二、目录结构

```
crewai-java-demo/
├── pom.xml                     # 无任何 <dependency>
├── run.sh / run.bat            # 免 Maven，javac 直接跑
└── src/main/java/io/example/crew/
    ├── Main.java               # 示例：研究 → 分析 → 成文（含持久化与 DAG 两种跑法）
    ├── core/
    │   ├── Agent.java          # 角色提示词 + 原生/文本双工具循环
    │   ├── Task.java           # 描述 / 期望输出 / 上下文 / 输出契约 / 校验器
    │   ├── Crew.java           # 编排 + 三道校验闸 + 重试 + 续跑
    │   ├── Process.java        # SEQUENTIAL / HIERARCHICAL
    │   ├── ContextBudget.java  # token 预算与摘要压缩
    │   ├── Guardrail.java      # 校验器接口
    │   ├── Guardrails.java     # 函数式 / 片段 / 字数 / LLM 四类工厂
    │   ├── TaskOutput.java     # 单任务产出（含 parsed 与 notes）
    │   ├── CrewOutput.java     # 整次运行产出（含 reusedTasks）
    │   ├── CrewException.java  # 中断异常，带"已完成几步"
    │   ├── RunState.java       # 运行快照（只存原始文本）
    │   ├── RunStore.java       # 存档抽象
    │   ├── FileRunStore.java   # 文件实现（原子写 + runId 白名单）
    │   ├── CrewListener.java   # 流程事件钩子 + 组合器
    │   ├── ConsoleListener.java# 控制台渲染（原内核打印集中于此）
    │   ├── MetricsListener.java# 工具调用/校验失败/驳回/复用统计
    │   └── AgentEvents.java    # Agent 级回调（工具调用、诊断）
    ├── flow/
    │   ├── DagNode.java        # 图节点：接收上下文、返回产出
    │   ├── CrewNode.java       # 把 Crew 包成节点（P1 核心，约 100 行）
    │   ├── SimpleDag.java      # 拓扑排序 / 祖先注入 / 环检测
    │   ├── DagResult.java      # 各节点产出
    │   └── DagException.java   # 带出错节点名
    ├── schema/
    │   ├── Field.java          # 字段契约注解
    │   └── Structure.java      # 反射生成 Schema + 绑定 + 校验
    ├── llm/
    │   ├── LlmClient.java      # 模型抽象（含 tools 参数）
    │   ├── OllamaClient.java   # POST /api/chat，只发一次请求
    │   ├── ResilientLlmClient.java # 限流 + 重试装饰器
    │   ├── RetryPolicy.java    # 指数退避 + 抖动
    │   ├── RateLimiter.java    # 匀速令牌桶
    │   ├── ChatMessage.java    # 支持 tool_calls / tool 角色
    │   ├── ChatReply.java      # 正文 + 工具调用请求
    │   └── NativeToolCall.java / ToolSpec.java / LlmException.java
    ├── tool/
    │   ├── Tool.java           # 工具抽象（parametersType 反射 / parametersJson 透传 / aliases 别名）
    │   ├── FatalToolFailure.java # 标记：这类失败不该回灌给模型
    │   ├── ToolRegistry.java   # 清单渲染 + Schema 生成 + 参数校验 + 分发 + 按名取子集
    │   ├── ToolSet.java        # 命名工具集：不可变、可按名裁剪/合并，绑到 Task
    │   └── CalculatorTool.java # 递归下降求值
    ├── mcp/                    # P2：MCP 客户端
    │   ├── McpTransport.java   # 传输抽象（一条消息进、一条消息出）
    │   ├── StdioTransport.java # 子进程 + UTF-8 逐行分帧 + stderr 排空
    │   ├── McpClient.java      # 握手 / id 配平 / 通知跳过 / 反向请求拒绝
    │   ├── McpServer.java      # 连接生命周期 + 批量包装成 Tool
    │   ├── McpTool.java        # Tool 适配器（名称合法化 + schema 透传）
    │   ├── McpServerConfig.java# 启动命令 / env / 前缀 / 工具数上限
    │   ├── McpToolInfo.java / McpCallResult.java / McpException.java
    │   └── DemoMcpServer.java  # 自带的服务端实现，无 Node 也能验收
    ├── mem/                    # P3：记忆与知识库
    │   ├── Embedder.java       # 嵌入抽象（多一个 baseline()，阈值靠它标定）
    │   ├── OllamaEmbedder.java # POST /api/embed，批量 + LRU 缓存 + 旧接口降级
    │   ├── HashingEmbedder.java# 离线兜底：字符 bigram + 带符号散列
    │   ├── Vectors.java        # 余弦 / 归一化 / float32 base64 无损编解码
    │   ├── Memory.java         # 门面：写入去重 / 检索 / 常驻块 / 渲染
    │   ├── MemoryQuery.java    # 权重 / 相对截断 / 噪声余量 / 重要性豁免
    │   ├── MemoryItem.java     # 文本 + 向量 + 元数据 + 重要性 + 时间
    │   ├── MemoryScope.java    # 四类作用域，各自不同的时间半衰期
    │   ├── MemoryStore.java    # 存储抽象（InMemory / File 两种实现）
    │   ├── FileMemoryStore.java# JSONL 持久化 + 原子替换 + 坏行容错
    │   ├── Importance.java     # 重要性启发式（不做 LLM 打分，理由见类注释）
    │   ├── Chunker.java        # 分块：标点断点 + 代码围栏保护 + Markdown 分节
    │   ├── Knowledge.java      # 文档索引与检索（复用 Memory 的存储与打分）
    │   └── Hit.java            # 检索结果 + 打分明细 + 归因
    ├── SelfTest.java           # 140 项离线断言，不依赖 Ollama
    └── util/
        ├── Json.java           # 解析 + 序列化 + 平衡括号抠 JSON
        ├── Tokens.java         # 中英分别计权的 token 估算
        └── Console.java        # 控制台渲染
```

## 三、运行

前置：JDK 17+、Ollama 已启动、已 pull 模型。

```bash
./run.sh                                 # 顺序流程（原生工具调用 + 结构化输出）
CREW_PROCESS=hierarchical ./run.sh       # 叠加主管评审
CREW_NATIVE_TOOLS=0 ./run.sh             # 强制文本协议，用于对比
CREW_CONTEXT_TOKENS=160 ./run.sh         # 压小预算，观察上下文压缩
CREW_DEBUG=1 ./run.sh                    # 打印系统提示词与每个任务的实际 prompt

# P1：断点续跑（三步演示）
CREW_RUN_ID=demo CREW_ABORT_AT=2 ./run.sh   # 第 2 步前中断，成果落盘
CREW_RUN_ID=demo ./run.sh                   # 续跑：复用第 1 步，不再花 token
CREW_RUN_ID=demo ./run.sh                   # 再跑：全部复用，0 调用 0 秒

# P1：把 Crew 嵌进编排图
CREW_DAG=1 ./run.sh

# P2：挂载 MCP 服务端的工具（demo 为自带服务端，无需 Node）
CREW_MCP=demo ./run.sh
CREW_MCP=everything ./run.sh                 # 官方测试服务端，需要 Node
CREW_MCP="npx -y @modelcontextprotocol/server-filesystem ." ./run.sh   # 任意服务端
CREW_MCP=demo CREW_NATIVE_TOOLS=0 ./run.sh   # 文本协议下同样可用

# P2：工具按任务给，而不是整包挂给 Agent
CREW_MCP=demo CREW_TOOLSET=task ./run.sh     # 分析任务只看到 calculator，成文任务只看到 word_count
CREW_MCP=demo CREW_TOOLSET=agent ./run.sh    # 对照：服务端 4 个工具全挂在 writer 上

# P3：记忆与知识库（需要 bge-m3，ollama pull bge-m3）
CREW_MEMORY=1 CREW_SEED=1 ./run.sh           # 预置 2 条常驻约定 + 1 条长期教训，跑一轮
CREW_MEMORY=1 ./run.sh                       # 再跑：跨进程召回上一轮的全部产出
CREW_MEMORY=1 CREW_KNOWLEDGE=1 ./run.sh      # 顺带把 README 索引进知识库（约 66 块）
CREW_MEMORY=1 CREW_MEMORY_EMBED=hash ./run.sh   # 不装嵌入模型也能跑（语义能力下降）

# 离线断言：140 项，不连模型、不需要 Ollama，0.3 秒跑完
CREW_SELFTEST=1 ./run.sh

./run.sh gpt-oss:20b                     # 换模型
mvn -q package && java -Dfile.encoding=UTF-8 -jar target/crewai-java-demo.jar
```

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `OLLAMA_HOST` | `http://127.0.0.1:11434` | Ollama 地址 |
| `CREW_MODEL` | `qwen2.5:7b` | 模型名（第一个命令行参数可覆盖） |
| `CREW_PROCESS` | `sequential` | `sequential` / `hierarchical` |
| `CREW_TEMPERATURE` | `0.4` | 生成温度 |
| `CREW_NATIVE_TOOLS` | `1` | `0` 强制走文本协议 |
| `CREW_MAX_RPM` | `60` | 每分钟调用上限，`0` 不限流 |
| `CREW_CONTEXT_TOKENS` | `2500` | 前序上下文预算 |
| `CREW_DEBUG` | `0` | `1` 打印提示词 |
| `CREW_RUN_ID` | 空 | 设置后启用持久化，存档写入 `runs/<runId>.json` |
| `CREW_ABORT_AT` | `0` | 在第 N 步开始前抛异常，用于演示中断（0 = 关闭） |
| `CREW_DAG` | `0` | `1` 走编排图模式：`prepare → Crew → compose` |
| `CREW_MCP` | 空 | 挂载 MCP 服务端：`demo` / `everything` / 任意命令行 |
| `CREW_TOOLSET` | `agent` | `agent` 工具挂 Agent（全任务共享）；`task` 工具绑到具体任务 |
| `CREW_MEMORY` | `0` | `1` 开启记忆：每任务前召回、任务后写入 |
| `CREW_SEED` | `0` | `1` 预置 3 条演示记忆（2 条常驻 + 1 条长期教训） |
| `CREW_KNOWLEDGE` | `0` | `1` 把 `README.md` 索引进知识库并做一次检索自检 |
| `CREW_MEMORY_EMBED` | `bge-m3` | 嵌入模型；`hash` 走离线哈希实现 |
| `CREW_MEMORY_FILE` | `memories.jsonl` | 记忆存档路径 |
| `CREW_SELFTEST` | `0` | `1` 只跑离线断言并退出 |

## 四、P0 的五项能力

### 1. 结构化输出契约（对齐 `output_pydantic`）

`Task.outputType(TrendAnalysis.class)` 声明契约后，框架做三件事：

1. **反射生成 JSON Schema**，与逐字段中文说明一起注入提示词；
2. 模型产出后**抠出 JSON 并绑定成对象**（容忍 ```json 围栏与前言）；
3. 校验失败时把**具体错误**回灌重试。

```java
public static final class TrendAnalysis {
    @Field(desc = "两条可验证的趋势判断，每条不超过 50 字")
    public List<String> judgments;

    @Field(desc = "调用次数相比单次大模型调用增长的百分比，只填数字，例如 500")
    public double increasePercent;

    @Field(desc = "一句话结论", maxChars = 40, optional = true)
    public String summary;
}
```

错误信息是给人读的，因为它最终是给模型读的：

```
字段 increasePercent 缺失（必填）
字段 judgments 应为数组，实际是 "判断A"
字段 summary 超长 60 字（上限 40 字）
JSON 语法错误：JSON 解析失败(期望 ',' 或 ']') 位置 109
```

**宽容与严格的边界**：`"500"` → 500 允许，`500` → `"500"` 允许（小模型的类型漂移是常态，
卡在这里只会无限重试）；缺字段、结构错、枚举值非法，一律严格。

### 2. 原生 function calling（+ 粘性降级）

`Tool.parametersType()` 声明参数类，`ToolRegistry` 在**注册时**就生成 Schema——
参数类写错应该在启动阶段炸，而不是跑了几分钟后在某个任务里炸。

```java
public static final class Params {
    @Field(desc = "算术表达式，仅支持 + - * / 与括号，例如 (3*2-1)/1*100")
    String expression;
}
```

请求体带上 `tools[]`，响应解析 `message.tool_calls[]`，工具结果以 `role=tool` 回灌。
工具调用请求必须**原样带回历史**，否则模型不知道自己上一轮要过工具，会重复发同一个调用。

降级是**粘性**的：一旦某次原生调用被 400 拒绝，`Agent` 标记 `nativeToolsDisabled`，
后续一律走文本协议 `[TOOL] {...}`。只在 400 时降级——连接失败和超时直接抛，
否则一次故障会被拖成两次超时。

文本协议的两条通路都实测可用（见 9.4）。

### 3. 上下文预算（对齐 `respect_context_window`）

按**字符**设预算是错的：同样 1500 个字符，中文约 1000–1500 token，英文只有 350–400。
`Tokens` 按 CJK / 非 CJK 分别计权（1 字 ≈ 1 tok vs 3.5 字符 ≈ 1 tok）。

`ContextBudget` 三级策略：

1. 总预算内 → 全部原文；
2. 超预算 → **保留最近**几条原文（预算一半，约 2 条），更早的用一次低成本调用压成要点；
3. 压缩失败 → 逐条硬截断并标注"已压缩"，让下游知道信息有损。

"保留最近的"这个方向很关键：顺序流程里越靠后的产出越接近当前任务的输入，
反过来丢最近的内容，模型会直接失去上下文衔接。

本类**不打印任何东西**，处理说明通过 `Result.note()` 返回给调用方——库代码写死
`System.out` 就没法在 web 服务里用。

### 4. Guardrails（对齐 `guardrail=`）

`Guardrails` 提供四类工厂，按成本从低到高：

| 工厂 | 用途 | 成本 |
|---|---|---|
| `require(name, msg, predicate)` | 任意断言，能拿到 `parsed` | 0 |
| `contains(name, msg, fragments...)` | 关键片段必须出现（治"漏项"） | 0 |
| `minChars` / `maxChars` / `structured` | 篇幅与结构底线 | 0 |
| `llm(llm, name, criteria)` | 语义验收 | 1 次调用 |

设计原则：**能用代码判断的绝不用模型判断**。`llm()` 在调用异常时选择放行并告警——
校验器自己挂掉不应该让业务停摆。

Guardrail 与主管评审的区别必须说清，否则会做重复：
前者挂在任务上、每次必跑、标准写死在代码里（质量下限）；
后者挂在流程上、语义级整体判断（一致性）。两者都开等于双倍成本。

### 5. 重试与限流

`OllamaClient` 刻意做笨：**只发一次请求**。重试与限流放在 `ResilientLlmClient` 装饰器里，
因为这两件事与 provider 无关，换成云端 API 时策略应当原样保留。

- `LlmException.retryable()` 由具体客户端给出，装饰器不猜——只有它知道 429 和 401 的区别；
- `RetryPolicy` 指数退避 + ±25% 抖动。抖动不是讲究：固定退避会让并发失败请求同时重撞；
- `RateLimiter` 取"匀速放行"而非"整分钟放行"，避免突发把本地显卡打满。

## 五、三道校验闸的协作

```
任务 → [结构化校验] → [Guardrails] → [主管评审] → 产出
          ↓错误回灌      ↓失败回灌      ↓REWORK
       重试(2次)       重试(2次)      重做(1次)
          ↓耗尽          ↓耗尽          ↓耗尽
        放行+记notes    放行+记notes    放行
```

`TaskOutput.notes()` 是这套机制的可观测出口，示例输出：

```
  ← 完成 · 第 3/3 步 · 22.3s · 110 字
     ⚠ 上下文压缩至约 223 tok
  ├ 趋势分析 · 数据分析师 · 83.9s · 重试 3 次
  ├   ↳ 结构化结果：判断 3 条 · 增幅 500%
```

## 六、P1 的三项能力

### 6.1 状态持久化与断点续跑

**要解决的问题**：一条 130 秒的链路在第 3 步失败，当时只能全量重跑。更糟的是重跑的结果
不一定和上次一致——前两步的产出变了，第 3 步面对的输入也就变了。

设计上做了四个决定：

**① 只存原始文本，不存结构化对象。** 恢复时用 `outputType` 重新绑定一遍即可，
代价是一次本地解析；换来的是不必为任意 POJO 写序列化器。存序列化后的对象更脆弱——
改了字段类型，旧存档就再也读不回来。

**② 按序号对齐，不按标签。** 标签允许重复、允许中途改名，序号与 `Crew.tasks` 一一对应。

**③ `kickoff` 是幂等的。** 调用方不需要区分"首次运行"和"恢复运行"，也就不会出现
两套代码路径各自有 bug 的情况：

```java
CrewOutput out = crew.kickoff(inputs, listener, store, "run-001");
// 第一次：全新执行，每完成一步就落盘
// 第二次：跳过已完成的任务，直接拿到完整结果
```

**④ 续跑前做三道一致性校验**，任何一项不满足就显式失败：

| 校验 | 不通过时的后果（若静默兼容） |
|---|---|
| 输入变量未变 | 模板占位符换了值，旧产出与新任务要求不符 |
| 已完成步数 ≤ 当前任务数 | 任务被删减，存档里的第 4、5 步无处安放 |
| 每步标签逐一相符 | 任务顺序被调整，第 2 步的产出被当成第 1 步的结果 |

第三种是最危险的：**它不会报错，只会产出一份看起来正常、实际错位的内容**。
所以宁可抛异常要求换 `runId`，也不静默兼容。

存储实现的两个细节：

- **原子写**：先写 `.tmp` 再 `ATOMIC_MOVE`。直接覆写时若进程被杀会留下半截 JSON，
  下次启动读不出来——这比没有存档更糟，因为它看起来"有"。
- **runId 白名单** `[A-Za-z0-9_-]{1,64}`：否则 `../` 就能读出任意文件。

`CrewException` 带 `completedSteps` / `totalSteps`：中断时最需要知道的不是"失败了"，
而是"失败在哪一步、前面几步的成果还在不在"。

### 6.2 事件钩子

**要解决的问题**：可观测性、成本核算、失败归因、UI 进度这些能力，之前全是硬编码在
`Crew` 里的 `System.out.println`。想接一个 web 进度条就得动核心类，那是设计缺陷。

`CrewListener` 提供 15 个事件，全部有默认空实现：

```java
onCrewStart / onResumed
onTaskStart / onTaskReused / onTaskEnd
onToolsBound
onToolCall / onCheckFailed / onGaveUp / onManagerReview
onMemoryRecall / onMemoryWrite
onNote / onError / onCrewEnd
```

`onMemoryRecall` 单独成一个事件（而不是混进 `onNote`），因为"检索过但没命中"与"没开记忆"
是两件不同的事：前者说明库太小或阈值太严，值得排查；后者不用管。

两个现成实现 + 一个组合器：

- **`ConsoleListener`**：承接原来散在内核里的全部打印，格式不变。`showPreview=false`
  可关掉产出预览（批处理场景）。
- **`MetricsListener`**：只统计不改输出——工具调用次数、结构/业务校验失败次数、
  评审驳回率、**降级放行次数**、存档复用数、按任务粒度的重试分布。
- **`CrewListener.plus(a, b)`**：按注册顺序广播。渲染和统计是两个关注点，各写一个类。

`onGaveUp` 单独成一个事件，是因为"产出有瑕疵但已交付"比"重试了一次"严重得多，
必须能被告警抓走，而不是混在普通日志里。

**一个刻意的设计**：监听器抛出的异常会中断流程。这让"人工介入"（某个任务前等待确认）
和"外部取消"可以只用一个监听器实现，不必在内核里另开一套 interrupt 机制。
`Main` 里的 `CREW_ABORT_AT` 就是这么做的——它同时也验证了中断路径能正确落盘 `FAILED`。

`Agent` 侧另有一套更小的回调 `AgentEvents`（工具调用 + 诊断信息）。为什么不合并进
`CrewListener`：Agent 是**可复用**的，同一个 Agent 会服务多个 Task，也会被主管评审复用。
回调绑在 Agent 上会导致事件归属错乱——任务 3 的工具调用被记到任务 1 的账上。
所以只能由调用方每次传入，由 `Crew` 负责把"任务序号"和"Agent 回调"对齐。

### 6.3 DAG 桥接（对齐 Flows，但不重写）

CrewAI 官方建议生产应用从 Flows 起步，只在需要"创造性协作"的那一步嵌入 Crew。
这个项目的判断一致：**Flow 的职责不该由 Crew 承担**，但也不该在这里重写一套 Flow。

`CrewNode` 就是这个桥，不到 100 行：

```java
SimpleDag dag = SimpleDag.builder()
        .node("prepare", ctx -> Map.of("topic", TOPIC))          // 代码节点
        .node(CrewNode.of("research", crew))                     // Crew 作为节点
        .node("compose", Main::composeNode)                      // 代码节点：格式兜底
        .edge("prepare", "research")
        .edge("research", "compose")
        .build();
```

`SimpleDag` 只做四件事：拓扑排序（Kahn）、按祖先注入输入、定位失败节点、检测环。
**刻意不做的**：并行分支、条件边、循环、检查点、重试——那些是流程引擎的职责，
在这里实现只会造出半成品。已经有 DAG 引擎的团队应该删掉这个类，只留 `CrewNode`。

一个映射细节：编排图上下文是 `Map<String,Object>`，Crew 的变量表是 `Map<String,String>`，
这里做无损降级。因为变量表只用于替换模板里的 `{placeholder}`——它最终一定要变成文本；
反过来把变量表提升成 `Object` 没有收益，只让模板替换多一层类型判断。

`compose` 节点演示了另一种确定性：**格式兜底交给代码，而不是在提示词里反复强调**。
它检查产出是否以 `## ` 开头，不是就补一个标题并把 `titleFixedByDag=true` 放进产出供告警。
不抛异常，因为缺标题属于可修复偏差，让整条链路失败反而更糟。

## 七、MCP 客户端（P2）

**要解决的问题**：每接一个外部能力（读文件、查数据库、调内部 API）都要在 Java 里重写一遍
适配代码、重写一遍 Schema、重新编译发布。MCP 把这件事变成配置——服务端声明工具，
客户端发现并透传，业务代码一行不改。

只实现 **stdio 传输**。理由不是省事：SSE 与 streamable HTTP 要处理会话 ID、断线重连、
OAuth 回调，这些与"多智能体协作"没有关系；而 stdio 是唯一能做到"一条消息进、一条消息出"
的传输，最适合把协议本身看清楚。传输层是接口，接远程时新增一个实现即可，`McpClient` 不用改。

### 7.1 链路

```
McpServerConfig ──启动──> StdioTransport ──> McpClient ──列表──> McpTool ──注册──> ToolRegistry
   (command)              (子进程/逐行)      (JSON-RPC)         (Tool 实现)        (Agent tools)
```

握手三步：`initialize`（协商协议版本 + capabilities）→ `notifications/initialized`（通知，无回执）
→ `tools/list`（自动跟随 `nextCursor` 翻页）。之后每次工具调用就是一次 `tools/call`。

```java
try (McpServer fs = McpServer.open(
        McpServerConfig.builder("fs")
            .run("npx", "-y", "@modelcontextprotocol/server-filesystem", ".")
            .toolPrefix("fs__")     // 多服务端并存时必须给前缀
            .maxTools(8)            // 工具太多会挤掉任务描述本身
            .build())) {

    ToolRegistry tools = ToolRegistry.of(new CalculatorTool()).registerAll(fs.tools());
    Agent writer = Agent.builder().role("技术编辑").tools(tools)/* ... */.build();
}
```

### 7.2 四个协议细节决定了实现的形状

| 细节 | 不做会怎样 | 本项目做法 |
|---|---|---|
| 响应**不按顺序**返回 | 发一条读一条，在"中间夹了通知"时错位 | 读线程统一分发，按 `id` 配平到 future |
| 服务端会发**通知** | 把 `notifications/progress` 当成响应解析，直接崩 | 无 `id` 的消息跳过 |
| 服务端会发**反向请求** | 客户端丢弃 → 服务端永久等待 → 表现为"工具调用超时"，且责任方无日志 | 显式回 `-32601 未实现` |
| `id` 可能是任意 JSON 数字 | 用 `==` 比较对象，永远配不上 | 按 `Number.intValue()` 比较 |

第三条最常见也最隐蔽。实测（自带 demo 服务端的 `probe_client` 工具主动发 `roots/list`）：
客户端 **12ms** 内回了 `-32601`，服务端立即继续。若客户端不回复，这里会一直挂到超时。

### 7.3 工具适配：三个必须做的转换

| 转换 | 原因 |
|---|---|
| 工具名合法化（`fs.read_file` → `fs_read_file`） | function calling 的 `name` 要求 `^[a-zA-Z0-9_-]{1,64}$`，带点会被判非法请求。MCP 规范对名字没有这个约束 |
| 描述压平成单行 + 截断 320 字 | 服务端描述常是多行 Markdown，拼进系统提示词会破坏工具清单的列表结构 |
| **Schema 原样透传** | 契约的拥有者是服务端。客户端反射"反推"一遍既做不到（没有对应 Java 类），也会引入偏差 |

为此给 `Tool` 加了 `parametersJson()`，优先级高于 `parametersType()`：

```java
default String parametersJson() {   // 默认 null：仍走反射生成
    return null;
}
```

### 7.4 两类失败必须分开处理

这是接入 MCP 时最容易做错的地方。同样是"工具调用失败"，有三条不同的路径：

| 失败类型 | 表现 | 正确处理 | 错误处理的后果 |
|---|---|---|---|
| **业务失败** | `isError: true`（参数不合法、文件不存在） | 转 `ToolException` → `ERROR: ...` 回灌模型 | 冒泡会中断整个流程，而模型其实一次就能改对 |
| **请求被拒** | JSON-RPC `-32602` | 同上，回灌模型 | 同上 |
| **连接故障** | 超时、进程退出、管道关闭 | 抛 `McpException` 冒泡 | 回灌会让模型反复重试一个它无法修复的问题，烧光迭代预算 |

机械上靠 `tool/FatalToolFailure` 这个标记接口：`McpException` 实现它，
`ToolRegistry.invoke` 见到它就重新抛出，不做 `ERROR:` 包装。判断依据属于故障的制造者，
不属于分发者。

### 7.5 自带的 DemoMcpServer

为了在**没有 Node / 没有网络**的环境里也能验收，仓库自带一个纯 Java 的 MCP 服务端
（`DemoMcpServer`），暴露 `word_count` / `now` / `echo.debug` / `probe_client` 四个工具。

它同时是三件事：

- **协议对端**：验证握手、翻页、通知、反向请求，不依赖外部环境；
- **服务端示范**：说明服务端一侧的三条纪律（stdout 只许输出协议消息、日志走 stderr、通知不回复）；
- **故意的不合规样本**：`echo.debug` 名字带点，用来验证客户端的名称合法化确实生效。

```bash
java -cp out io.example.crew.mcp.DemoMcpServer   # 可手工粘贴 JSON 请求试协议
```

### 7.6 工具集：把工具从 Agent 挪到 Task

CrewAI 里工具只能挂在 `Agent` 上，`Task` 没有 `tools` 参数。这在"一个 Agent 只做一件事"时
没问题，但一旦出现下面三种情况就难受：

1. **工具清单挤掉任务本身**。`server-everything` 有 13 个工具，全挂上去光是描述就占掉上千 token，
   而 9.8 的实测结论是模型一个都不会用——它们与任务无关，只是噪音。
2. **同一 Agent 承担多个任务时，各阶段需要的工具不同**。写作 Agent 起草时不需要字数工具，
   校对时才需要。为了工具差异拆成两个 Agent，人设就要维护两份，而人设恰恰最不该分裂。
3. **"工具来源"是运维概念，"工具可见性"是设计概念**。前者由服务端决定（连上什么就有什么），
   后者应由任务决定（这一步该看到什么）。两者混在一个 `Agent.tools` 字段里，任何一方变化都要改另一边。

所以引入了 `tool/ToolSet`——一个**不可变**的命名工具集合：

```java
ToolSet all = mcp.toolSet("demo 服务端");                    // 来源标记 mcp:demo-tools
ToolSet math = ToolSet.of("趋势分析·计算", new CalculatorTool());
ToolSet word = all.only("word_count").named("成文·字数核对"); // 按原始名挑，与前缀无关

Task.builder().label("趋势分析").agent(analyst).toolset(math).build();
Task.builder().label("成文").agent(writer).toolset(word).build();
```

`only` / `exclude` / `merge` / `with` 都返回新实例。工具集通常在启动阶段构造一次、被多个任务共享，
可变的集合迟早会出现"某个任务把它改小了，另一个任务跟着遭殃"。

**三条关键语义**：

| 情况 | 含义 | 实现 |
|---|---|---|
| `toolset` 未设置 | 继承 Agent 自带的工具 | `Task.effectiveTools()` 返回 `agent.registry()` |
| `toolset(ToolSet.empty(...))` | **明确不给工具** | 返回空注册表，不回退 |
| `toolset` 名字写错 | 启动阶段抛异常 | `only()` 找不到就失败，不静默跳过 |

第二种是刻意区分的。若用"空集合"顺带实现"未绑定"，模型就永远没法被强制断掉工具——
有些任务（纯改写、纯格式转换）调工具反而会走偏，需要能显式关掉。

**最容易被做错的地方：工具集必须五处同源。** 一次执行里，工具出现在五个地方：

```
系统提示词的工具清单 / tools[] 函数声明 / 调用分发 / "先调工具再填 JSON"的措辞 / 绑定日志
```

只改一处就会产生"模型照提示词去调一个没有声明的工具"，然后收到"未知工具"，再换个名字重试——
白白烧掉两轮。所以 `Agent.execute(prompt, events, ToolRegistry)` 把工具集做成**运行期参数**，
`systemPrompt(ToolRegistry)` 与 `useNativeTools(ToolRegistry)` 一并接受它，而不是读写 `Agent` 字段。
写回字段的后果很具体：同一个 Agent 被多个任务共用时，上一个任务收窄过的工具集会漏给下一个任务。

工具集是参数而非状态，还有第二个好处：**不需要为工具差异复制 Agent**。
9.7 里 analyst 和 writer 的人设各自只有一份，工具全由任务指定。

**别名机制**。MCP 工具名被加了前缀（`fs__read_file`）以避免多服务端冲突，但配置里写字面量时
人更愿意写 `read_file`。`Tool.aliases()` 让 `only()` 能按原始名匹配，同时不会让模型看到两个名字——
注册名始终只有一个。命名匹配的失败必须抛异常：工具名写错属于配置错误，静默跳过会表现为
"某个任务莫名其妙少了个工具"，排查方向完全跑偏。

**实测代价与收益**（`CREW_MCP=demo`，详见 9.12）：

| 绑定方式 | writer 看到的工具 | 工具清单占用 | 整轮输入 |
|---|---|---|---|
| 按 Agent（4 个全挂） | word_count, now, echo_debug, probe_client | 185 tok | 5,179 tok / 6 次调用 |
| 按任务（只挂 1 个） | word_count | 64 tok | 3,674 tok / 5 次调用 |

工具清单省了 121 tok（65%），但它只占一次请求输入的 3% 左右——**省 token 不是这件事的主要收益**。
真正的收益是**选择空间从 4 收敛到 1**，以及"这个任务的工具就是它当下要用的那个"这种咬合度。
用 tok 数字去论证工具集的价值会得出错误结论，9.8 里 13 个工具一个都没被调用才是正确的论据。

## 八、记忆与知识库（P3）

### 8.1 先分清它和 ContextBudget 的分工

两者都在"处理放不下的历史"，但方向相反，而且**顺序不能反**：

```
全量历史 ──[Memory：语义检索]──> 相关的若干条 ──[ContextBudget：压缩]──> 装进窗口
             选择（丢弃无关）                    压缩（保留要点）
```

先压缩再检索，等于先把可能要用的信息压掉了再去捞；而且压缩要花一次模型调用，
对本来就不该进来的内容花这次调用是纯浪费。

一句话区分：**ContextBudget 解决"窗口内塞不下"，Memory 解决"这条信息根本不该在这个任务里出现"。**

### 8.2 四类作用域，三种行为

CrewAI 用四个存储类（ShortTerm / LongTerm / Entity / Contextual）表达区分，代价是四套检索路径。
这里用"一个存储 + 一个作用域枚举 + 按作用域变化的参数"，检索逻辑只有一条：

| 作用域 | recency 半衰期 | 典型内容 | 写入时机 |
|---|---|---|---|
| `SHORT_TERM` | 24 小时 | 本次/近期任务产出 | 任务完成时自动 |
| `LONG_TERM` | 720 小时（30 天） | 稳定结论、用户偏好、踩过的坑 | 人工写入 / 跨运行沉淀 |
| `ENTITY` | 168 小时（7 天） | 某个项目/术语/人的累积认知 | 同实体更新而非新增 |
| `KNOWLEDGE` | **不衰减** | 文档分块 | 建库时一次写入 |

`KNOWLEDGE` 不衰减这一条是刻意的。三个月前导入的规范和今天导入的等价，
施加时间衰减会让检索系统性偏向新文档——这是纯向量检索不会犯、但复合打分很容易犯的错。

实测衰减曲线（`exp(-ln2 · hours / halfLife)`）：

```
经过      短期(24h)   长期(720h)   知识(不衰减)
0h        1.000      1.000       1.000
24h       0.500      0.977       1.000
48h       0.250      0.955       1.000
168h      0.008      0.851       1.000
720h      0.000      0.500       1.000
```

### 8.3 复合打分：三项各自的职责

```
score = w_rel · relevance + w_rec · recency + w_imp · importance
默认  0.70            0.20            0.10
```

三项权重先归一化，且**知识块会把 recency 那份权重让给 relevance**，保证权重和恒为 1。
不这么做的话，混检"知识块 + 短期记忆"时两类条目分数不在同一量纲上：
知识块永远拿满 recency 分（等于恒加 0.2），排在前面的会全是新导入的文档。

`importance` 的作用边界可以精确算出来。`w_imp / w_rel = 0.10 / 0.70`，
所以 0.5 的重要性差最多抵消 7.1% 的相关性差：

```
importance 差 0.50  →  可抵消的相关性差 = 0.10 × 0.50 / 0.70 = 0.071
```

实测验证：两条记忆相关性 0.464 / 0.448（差 0.016），重要性 0.90 / 0.40，
高分的那条成功翻到第一。**importance 不是用来跨语义鸿沟的，它是在语义接近时做裁决的。**
指望它把一条 0.3 相关的记忆顶到 0.9 相关的前面，说明阈值设错了，不是权重的错。

`importance` 从哪来？不做 LLM 打分——每写一条多一次调用，7B 上同一段文本两三次
给出 4/7/5 分，等于给检索注入随机噪声。改用启发式：识别"必须/不要/禁止"（+0.25）、
"偏好/约定/规范"（+0.20）、"失败/踩坑/超时"（+0.20），基线 0.4。需要精确控制时显式传入。

### 8.4 阈值：为什么绝对阈值不可用

这是 P3 里最重要的一条实测结论。用 45 对互不相关的中文短句两两算余弦：

| 嵌入器 | 均值 | 最小 | **最大** |
|---|---|---|---|
| `bge-m3`（1024 维） | 0.3915 | 0.2520 | **0.5382** |
| `HashingEmbedder`（512 维） | 0.0060 | −0.0659 | 0.0877 |

bge-m3 上**完全无关的两句话相似度最高能到 0.538**，而一句真正弱相关的话只有 0.56。
两个区间重叠，任何绝对阈值都必然误杀或漏放。这是嵌入模型的各向异性，不是 bug。

所以本项目的检索层以**相对截断**为主：

- 噪声地板 = `baseline() + noiseMargin`（默认 0.39 + 0.15 = 0.54），只用来判断"整批候选是不是全是噪声"
- 相对截断 = 复合分不低于最高分的 72%
- topK 兜底

`baseline()` 由 `Embedder` 实现自己声明，因为**阈值不能跨模型复用**：
同一套 `0.5 算相关` 的规则，用在 bge-m3 上一个都召不回，用在哈希实现上全是噪声。

### 8.5 两类不该走检索的记忆

加了阈值之后，实跑发现 3 条高价值记忆（相关性 0.326 / 0.371 / 0.443）全部被 0.54 的地板挡住。
这不是阈值调得不对，而是**有整整两类信息不适合用"检索"这个机制**：

**① 全局约定**——"标题只用二级""不要写套话""结论先行"。
这类文本与<b>任何</b>任务描述都不相关（实测 0.33，比无关基线还低），但对<b>所有</b>输出都有约束力。
任何检索阈值都会筛掉它们。

解法是 `Memory.rememberPinned(...)`：标记为常驻，不走检索，每次直接注入。
提示词里用「**必须遵守的约定**」而不是「可能相关」，因为模型对这两类指令的遵守率差别很大——
把约束混在"可能相关"的段落里，等于告诉模型它可以不遵守。

**② 高价值低相关的教训**——"7B 同时要求 JSON 和调工具会编数字"。
与当前任务相关度中等（0.44），但漏掉它代价很大。

解法是**重要性豁免**：`importance ≥ 0.7` 且 `relevance ≥ baseline` 的条目可以越过相对截断，
最多占 topK 的一半（防止一批高重要性条目把任务本身的信息挤出去）。

实跑验证（`CREW_MEMORY=1 CREW_SEED=1`）：

```
→ 资深行业研究员 开始执行 · 第 1/3 步 · 事实收集
  · 常驻约定 2 条已注入
  ◈ 记忆召回 1 条
     · [长期 0.61] 踩坑：给 7B 模型同时要求「输出 JSON」和「调用工具」时…
```

两条常驻约定每次都注入；那条教训的相关性只有 0.44（低于 0.54 地板），靠重要性 0.85 被豁免召回。

### 8.6 记忆污染与去重

**跨运行污染**：同一次运行里，第 2 个任务写入的产出对第 3 个任务本来就已经在前序上下文里了，
再从记忆里捞一遍是纯粹的 token 浪费，而且会让模型把同一段内容当成两个独立证据。
解法是 `excludeRunId`——每条记忆带 `runId` 元数据，检索时排除本次运行写入的。

注意即使调用方没传 `runId`，`Crew` 也会生成一个 `adhoc-<时间戳>-<序号>` 临时标识。
没有它就没有隔离，也就没有"本次运行"这个概念。

**去重**：同一条"用户要求中文输出"每次运行结束都可能被再写一次，跑十轮就积十条近似记忆，
检索时 topK 全被它们占满。解法是写入前先找同作用域最相似的一条，相似度 ≥ 0.95 就合并
（保留原 id 与 createdAt，importance 取大，`hits` 加一）。

**自我强化**：检索是只读的，命中**不**刷新 `lastUsedAt`。
一旦命中就更新，被召回过的记忆永远显得新鲜，下一轮又更容易被召回——
冷门的永远沉底，热门的越来越热。这个正反馈在记忆系统里是 bug，不是"使用频次加权"。

### 8.7 知识库与分块

`Knowledge` 复用 `Memory` 的存储与打分，只改两个参数：作用域是 `KNOWLEDGE`、不打时间分也不打重要性分。
理由很简单——文档块和记忆条目在数据结构上完全同构，为这点差别另建一套存储会得到两份要同步维护的相似代码。

分块是检索质量的第一决定因素，比嵌入模型的选择影响更大。三件事同时要成立：

- **块自成一体**：只有半句话的块，向量指向不明确
- **块不能太大**：一块塞三四个论点，向量是这几件事的平均值，对哪件事都不像
- **边界不切在语义中间**：优先按空行分，其次按句末标点，实在不行才硬切

实测本仓库 40 KB 的 README，`Chunker` 做了三级处理：

| 分块方式 | 块数 | 代码围栏被切断的块 |
|---|---|---|
| 通用（标点断点） | 76 | 27 |
| + 代码围栏保护 | 76 | 15 |
| + Markdown 按标题分节 | 66 | 15 |

代码围栏保护的必要性：被拦腰截断的代码块两半都是无法阅读的碎片，
**但它们仍然会被正常嵌入、正常被检索命中**——检索看不出问题，只有把 prompt 打印出来才看得见。

Markdown 分节的价值：标题是作者已经标注好的语义边界，用它切分等于免费拿到了文档结构。
短节相邻合并（避免一个标题下只有一句话就成为一块），长节二次切分（避免某一节独占预算）。

仍未解决的部分：单个代码块如果超过块大小上限，还是会被切开。能在不切割的前提下塞进 400 字的解法不存在。

### 8.8 接入 Crew 的三个位置

```java
Crew.builder()
    .memory(memory)          // 传入即开启，对应 CrewAI 的 memory=True
    .memoryTopK(4)           // 每任务最多注入几条
    .memoryMaxChars(600)     // 检索块字数上限
    .memoryPinnedMaxChars(300) // 常驻约定块字数上限
    .build();
```

`Crew` 里只加了三处：

1. **任务开始前**召回 + 拼接常驻块，把 token 数传给 `ContextBudget.assemble(outputs, llm, reservedTokens)`
2. **任务成功后**写入短期记忆，文本是 `任务描述 + "\n产出：" + 前 400 字`
3. **任务失败后**写入教训，重要性固定 0.7

第 2 处最初写错了，见下一节的坑。

## 九、实跑结果（qwen2.5:7b，本机实测）

### 9.1 `sequential`（原生工具调用 + 结构化输出）

```
  → 资深行业研究员 开始执行 · 第 1/3 步 · 事实收集
  ← 完成 · 第 1/3 步 · 8.0s · 103 字
  → 数据分析师 开始执行 · 第 2/3 步 · 趋势分析
  ⚙ 工具调用 calculator{expression=(3*2-1)/1*100} → 500
  ← 完成 · 第 2/3 步 · 27.2s · 150 字 · 结构化 ✓
     判断 2 条 · 增幅 500%
  → 技术编辑 开始执行 · 第 3/3 步 · 成文
  ← 完成 · 第 3/3 步 · 10.8s · 105 字

统计  总耗时 46.0s | LLM 调用 4 次 | 输入 2,537 tok | 输出 288 tok
```

### 9.2 `CREW_CONTEXT_TOKENS=160`（上下文压缩）

```
! 校验未通过，重试 1/2：[计算结果] increasePercent 必须是调用 calculator 工具得到的值…
  ⚙ 工具调用 calculator → 500
  · 上下文超预算（约 272 tok > 160 tok）：已压缩 1 条早期产出，保留 1 条原文
  ← 完成 · 第 3/3 步 · 22.3s · 110 字

统计  总耗时 70.0s | LLM 调用 6 次（含 1 次压缩调用、1 次校验重试）
```

### 9.3 `CREW_PROCESS=hierarchical`（叠加主管评审）

3 个任务全部被主管驳回一次，13 次调用、130.4s、8,466 tok——约是 sequential 的 3.2 倍。
评审意见能指出"缺什么"（"添加关于性能影响的描述"、"添加二级标题"），
但代价是**驳回可能破坏已经通过的校验**：分析师第二次尝试把已经算对的 500 换成了别的值，
被 guardrail 重新拦下。

### 9.4 `CREW_NATIVE_TOOLS=0`（文本协议降级）

```
  ⚙ 工具调用 calculator → 500
  ← 完成 · 第 2/3 步 · 30.5s · 111 字 · 结构化 ✓
统计  总耗时 45.6s | LLM 调用 3 次
```

文本协议在 7B 上依然可用，甚至更省调用次数（原生模式多一轮 tool 往返）。

### 9.5 中断 → 续跑 → 重放（P1）

三步演示的实测输出：

```
# ① CREW_RUN_ID=demo CREW_ABORT_AT=2
  → 资深行业研究员 开始执行 · 第 1/3 步 · 事实收集
  ← 完成 · 第 1/3 步 · 7.7s · 96 字
  → 数据分析师 开始执行 · 第 2/3 步 · 趋势分析
× 任务「趋势分析」失败：模拟中断：CREW_ABORT_AT=2，在第 2 步开始前强制退出

▌ 流程中断
  ├ 任务「趋势分析」执行失败（已完成 1/3 步，runId=demo，可用同一 runId 续跑）
  ├ 已保留 1/3 步成果
  └ 续跑命令：CREW_RUN_ID=demo ./run.sh

# ② CREW_RUN_ID=demo（续跑）
  ↺ 事实收集 复用存档 · 第 1/3 步 · 7.7s · 0 tok
  → 数据分析师 开始执行 · 第 2/3 步 · 趋势分析
  ⚙ 工具调用 calculator{expression=(3*2-1)/1*100} → 500
  ← 完成 · 第 2/3 步 · 28.9s · 151 字 · 结构化 ✓
  ← 完成 · 第 3/3 步 · 11.5s · 104 字
▌ 运行统计
  ├ 总耗时 40.4s（另有 1 步复用存档，不计入本次耗时）
  ├ LLM 调用 3 次 | 输入 2,283 tok | 输出 224 tok
▌ 过程质量
  ├ 工具调用 1 次
  ├ 存档复用 1 个任务（未消耗 token）
  └ 重试分布：无

# ③ CREW_RUN_ID=demo（再跑，幂等）
  ↺ 事实收集 复用存档 · 第 1/3 步 · 0 tok
  ↺ 趋势分析 复用存档 · 第 2/3 步 · 0 tok
  ↺ 成文 复用存档 · 第 3/3 步 · 0 tok
  ├ 总耗时 0.0s（另有 3 步复用存档，不计入本次耗时）
```

中断时存档状态是 `FAILED`、`completed` 有 1 条；续跑后变 `DONE`、`completed` 有 3 条。
存档文件长这样（`raw` 完整保留，结构化对象没落盘）：

```json
{"runId":"demo","crewName":"技术栈引入多智能体的可行性简报","status":"DONE",
 "inputs":{"topic":"把多智能体框架引入 Java 技术栈的收益与代价"},
 "completed":[{"label":"事实收集","agentRole":"资深行业研究员","raw":"1. ...",
               "millis":7682,"attempts":1,"notes":[]}, ...]}
```

### 9.6 `CREW_DAG=1`（Crew 作为图节点）

```
▌ 编排图执行
  ├ 执行顺序：prepare → research → compose
  ├ prepare → topic = 把多智能体框架引入 Java 技术栈的收益与代价
  ├ research → 产出 139 字 · 61.3s
  ├ compose  → 正文 139 字 · 标题由模型自己给出
  └ Crew 只是图里的一个节点，前后步骤都是确定性代码

▌ 过程质量
  ├ 工具调用 1 次
  ├ 校验未通过 1 次（结构 1 / 业务 0）
  └ 重试分布：趋势分析×1

统计  LLM 调用 5 次 | 输入 3,589 tok | 输出 369 tok
```

这一轮 7B 第一次给出的 JSON 不合法，结构化校验拦下并重试后通过——正好演示了
`onCheckFailed` 事件被统计到"过程质量"里。

**未覆盖的分支**：本次模型自己写了 `## ` 标题，所以 `compose` 的"标题兜底"路径没有被触发
（输出显示"标题由模型自己给出"）。该分支的正确性由离线断言覆盖，不是实跑证据。

### 9.7 `CREW_MCP=demo`（MCP 工具进入 Crew）

把自带服务端的 4 个工具挂到"技术编辑"Agent 上。模型自主选了 `word_count` 做交稿前自检：

```
MCP 就绪：demo-tools · crew-demo-tools 1.0.0 · 协议 2025-06-18 · 4 个工具
MCP 工具：[word_count, now, echo_debug, probe_client]

  → 技术编辑 开始执行 · 第 3/3 步 · 成文
  ⚙ 工具调用 word_count{text=## 多智能体框架分析
多智能体框架提升应用灵活性与扩展性，但需额外开发智能体逻辑，资源消耗增加可能影响性能。推测仅适用于特定场景。, limit=200.0}
    → 总字符数 67，中文 58 字，非空白 64 字符；未超出上限 200
  ← 完成 · 第 3/3 步 · 39.8s · 67 字

统计  总耗时 96.0s | LLM 调用 6 次 | 输入 5,220 tok | 输出 468 tok
```

这一次调用同时证明了四件事：原生 `tools[]` 里带的是**服务端下发的 Schema**、
模型能自主决定"什么时候该核对字数"、多行中文参数在管道里没有损坏、结果回灌后模型照常收尾。

**文本协议下降级同样可用**（`CREW_NATIVE_TOOLS=0 CREW_MCP=demo`）：

```
  ⚙ 工具调用 word_count{text=## 多智能体框架在Java应用中的应用 …
统计  总耗时 74.6s | LLM 调用 5 次 | 工具调用 2 次
```

这条路径依赖 `McpTool.signature()`——把服务端 Schema 里的顶层属性反推成
`text*: string, limit: integer` 写进提示词，必填项加 `*`。不这么做，文本协议下模型
既看不到参数名也看不到必填性，只能靠猜。

### 9.8 `CREW_MCP=everything`（真实第三方服务端）

```
启动 MCP 服务端：npx -y @modelcontextprotocol/server-everything
! MCP 服务端 everything 工具数超过上限 5，其余 8 个未注册（避免挤占提示词预算）
MCP 就绪：everything · mcp-servers/everything 1.0.0 · 协议 2025-06-18 · 5 个工具
MCP 工具：[echo, get-annotated-message, get-env, get-resource-links, get-resource-reference]

统计  总耗时 62.1s | LLM 调用 4 次 | 工具调用 1 次
```

握手、翻页、工具名（`get-sum` 这类带连字符的）全部正常，中文 `echo` 往返无乱码。

但**模型一次都没用这些工具**——这 5 个工具与"写一段 200 字简报"毫无关系，
模型正确地忽略了它们。这是这一轮最值得记的结论：**工具挂上去 ≠ 模型会用**，
工具描述与当前任务的咬合程度决定使用率。对比 9.7 里 `word_count` 立刻被调用，
差别不在协议实现，而在"这个工具是否解决了它当下的困境"。

连续调用同一个第三方服务端做 5 项协议验证（含图片内容块、参数校验失败、2 秒长任务、
反向请求）也都正常：

```
[sum]      The sum of 17 and 25 is 42.
[image]    Here's the image you requested:
           [image 内容已省略：image/png]
[badargs]  ERROR: MCP error -32602: Input validation error: Invalid arguments for tool get-sum…
[lro]      用时 2033ms（期间夹着 progress 通知，未错位）
```

### 9.9 记忆的跨运行复用（P3）

```
# 第一轮：空库，写入 3 条
CREW_MEMORY=1 ./run.sh
  记忆未命中 ×3 → 记忆 3 条（短期 3）平均重要性 0.60    总耗时 60.7s · 3 次调用

# 第二轮：同一份 memories.jsonl，跨进程
CREW_MEMORY=1 ./run.sh
  ◈ 记忆召回 3 条（0.89 / 0.77 / 0.74）   ← 事实收集
  ◈ 记忆召回 2 条（0.91 / 0.76）          ← 趋势分析
  ◈ 记忆召回 1 条（0.81）                 ← 成文
  总耗时 57.5s · 输入 2,373 tok（第一轮 1,532 tok，多出的 841 tok 是记忆块）
```

**这个结果一半是成功，一半是警告。** 成功在于跨进程召回确实生效、分数排序合理。
警告在于模型几乎逐字复制了自己上一轮的第 1 步产出——SHORT_TERM 记忆会让模型锚定在旧答案上，
不再重新推理。这不是 bug，是"保持一致"与"重新思考"之间的固有张力。

实践上的界线：**SHORT_TERM 只用于同一次会话内的连贯性；跨运行要沉淀的应当是结论与教训，
写进 LONG_TERM 或常驻。** 把完整产出长期留在短期记忆里，等于给模型准备了一份标准答案。

### 9.10 常驻约定与重要性豁免（P3）

```
CREW_MEMORY=1 CREW_SEED=1 ./run.sh     # 预置 2 条常驻 + 1 条长期教训
预置记忆：3 条（2 条常驻 + 1 条长期教训）
  → 第 1/3 步  · 常驻约定 2 条已注入
               ◈ 记忆召回 1 条  [长期 0.61] 踩坑：给 7B 模型同时要求「输出 JSON」和「调用工具」…
  → 第 2/3 步  · 常驻约定 2 条已注入
               ◈ 记忆召回 1 条  [长期 0.69] 同上
               ⚙ 工具调用 calculator{expression=(3*2-1)/1*100} → 500
  → 第 3/3 步  · 常驻约定 2 条已注入   成文 164 字（上限 200，以 ## 开头）
```

三条预置记忆与任务主题的相关性分别是 0.326 / 0.371 / 0.443，**全部低于 0.54 的噪声地板**。
前两条靠"常驻"注入，第三条靠"重要性豁免"（0.44 ≥ 基线 0.39，且重要性 0.85 ≥ 0.7）。
这是仅靠阈值检索会全军覆没、而实际必须生效的场景。

第 3 步 164 字、以二级标题开头，两条常驻约定都被遵守了。

### 9.11 知识库（P3）

```
CREW_MEMORY=1 CREW_KNOWLEDGE=1 ./run.sh
知识库索引：README.md → 66 块 · 27.8s

▌ 知识库检索自检
  ├ 查询：上下文预算不足时怎么压缩早期产出
  ├ 0.607 · 第 21 块 · JK / 非 CJK 分别计权… `ContextBudget` 三级策略：
  ├ 0.560 · 第 43 块 · 判断 2 条 · 增幅 500% → 技术编辑 开始执行
  ├ 0.543 · 第 20 块 · ``` 请求体带上 `tools[]`，响应解析 `message.tool_calls[]`…
```

索引 66 块的耗时 27.8s 全部来自嵌入（bge-m3 批量 8 条一次，共 9 次请求），
其中大部分是模型首次加载。同一个进程内第二次索引会快得多（`OllamaEmbedder` 有 LRU 缓存）。

召回质量受**语料质量**制约而不是算法：README 里混着运行日志的粘贴片段，
检索正确地找到了"包含 topic 字符串的那一块"，但那块内容本身是日志，注入后就是噪声。
文档语料里混日志，任何检索算法都救不回来。

### 9.12 工具集与任务绑定（`CREW_TOOLSET`）

同一个 Crew、同一个服务端，只改工具绑定方式：

```
# A. CREW_TOOLSET=agent —— 服务端 4 个工具全挂到 writer
→ 数据分析师 开始执行 · 第 2/3 步 · 趋势分析
  ⚙ 工具调用 calculator{expression=(3*2-1)/1*100} → 500
  ← 完成 · 第 2/3 步 · 51.5s · 188 字 · 重试 1 次 · 结构化 ✓
→ 技术编辑 开始执行 · 第 3/3 步 · 成文
  ⚙ 工具调用 word_count{... limit=200.0} → 总字符数 66，中文 57 字；未超出上限 200
  ← 完成 · 第 3/3 步 · 56.4s · 66 字
统计  LLM 调用 6 次 | 输入 5,179 tok | 输出 442 tok | 工具调用 2 次

# B. CREW_TOOLSET=task —— 工具按任务给
→ 数据分析师 开始执行 · 第 2/3 步 · 趋势分析
  ⚙ 工具集「趋势分析·计算」· 来源 local · calculator
  ⚙ 工具调用 calculator{expression=(3*2-1)/1*100} → 500
  ← 完成 · 第 2/3 步 · 42.8s · 178 字 · 结构化 ✓
→ 技术编辑 开始执行 · 第 3/3 步 · 成文
  ⚙ 工具集「成文·字数核对」· 来源 mcp:demo-tools · word_count
  ⚙ 工具调用 word_count{... limit=200.0} → 总字符数 88；未超出上限 200
  ← 完成 · 第 3/3 步 · 51.7s · 88 字
统计  LLM 调用 5 次 | 输入 3,674 tok | 输出 427 tok | 工具调用 2 次
```

**不要把 1,505 tok 的差值全算作工具集的功劳**。A 那一轮第 2 步多了一次重试
（结构化校验没过），一次重试带着完整上下文，本身就上千 token。工具清单的真实差额是
121 tok（185 → 64），在 7.6 里单独量过。

两轮都生效的是：**每个任务只看到自己那一个工具**。`⚙ 工具集「…」· 来源 …` 这行日志
只在任务显式绑定时打印——继承 Agent 工具是默认情况，每步都打一行"继承了 4 个工具"只是噪音；
而一旦绑了，它就是排查工具误用的第一手线索（名字、来源、实际工具名全在）。

B 里 `writer` 的工具来自 `mcp:demo-tools`，`analyst` 的来自 `local`——同一个流程里
两个任务的工具来源不同，这在"按 Agent 绑定"时是做不到的（一个 Agent 只有一份工具表）。

### 9.13 离线断言（140 项，不需要 Ollama，0.3 秒）

```
CREW_SELFTEST=1 ./run.sh
▌ 结果
  ├ 通过 140 项
  └ 耗时 0.4s
```

四组，都不依赖模型。

**31 项（P1）**：用假 `LlmClient` 跑真实 `Crew`，覆盖存档往返、幂等重放、中断续跑、
三道一致性校验、拓扑排序、祖先输入隔离、环检测、`CrewNode` 输出透传、监听器广播顺序。

**28 项（P2）**：MCP 协议层用假传输、适配层与端到端用自带服务端，覆盖

- 握手字段、跨页 `tools/list`、`nextCursor` 跟随；
- **响应前插入通知**、**响应乱序**、**服务端反向请求**（客户端是否回 `-32601`）；
- 工具名合法化与原始名保留、前缀、schema 原样透传、签名必填标记、描述压平；
- 子进程真实往返、缺参回灌、未知工具本地拦截、整数参数不写成浮点；
- 关闭后调用**冒泡**而非回灌、启动失败、重复注册被拒。

**54 项（P3）**：向量编解码与余弦、哈希嵌入的确定性与区分度、写入/去重/作用域过滤、
复合打分的衰减曲线、importance 裁决边界、相对截断、噪声地板、`excludeRunId` 隔离、
常驻注入、重要性豁免及限额、JSONL 往返与坏行容错、分块的边界/重叠/围栏保护/Markdown 分节、
知识库索引与重检索、Crew 集成的召回与写入、预算预留。

**27 项（工具集）**：集合运算（挑选/别名/排除/合并/冲突/改名/空集）、子集不重建 Schema、
集外工具被拒绝分发，以及任务绑定的**实际下发声明**——继承全部、只下发任务集、
空集不下发且不回退、上一个任务的收窄不泄漏给下一个任务、系统提示词与声明同源、
空集时提示词不讲工具协议也不要求"先调工具再填 JSON"。

前 54 项用 `HashingEmbedder` 而不是 bge-m3。理由不是"省事"：记忆层绝大多数 bug
（打分算错、去重阈值、作用域过滤、存档往返）跟语义质量无关，用真模型测只会让每轮多等
十几秒，还引入"模型今天答得不一样"的噪声。语义质量的结论另在 9.9–9.11 用实跑数据支撑。

其中"假传输的 `receive()` 必须真阻塞"本身就是一条结论：写成"队列空就返回 null"，
读线程一启动就会误判服务端关闭。

## 十、踩到的坑（都可复现）

### P0 的三个

**坑 1：结构化输出会压掉工具调用。** 把"输出 JSON"和"用 calculator 算数"写在同一个
prompt 里，三次尝试**一次都没调工具**，模型直接编了数字。JSON 是强格式约束，模型为了
满足它会跳过中间的调工具步骤。修法是把两步顺序写死进 prompt。

**坑 2：结构化输出让"编造的数字"变得合法。** 修好坑 1 后跑出一次"干净"结果：结构化
一次通过，但 `increasePercent = 100`（正确答案 500），根本没调工具。Schema 校验只保证
格式合法，对内容为真毫无察觉——它把"一眼假"变成了"看起来真"。加数值一致性 guardrail 才收敛。

**坑 3：校验标准过严 = 必然降级放行。** 要求"恰好 2 条"时 7B 稳定给 3 条，耗尽重试后
106s 才放行。放宽成 `>= 2` 一次通过。校验标准必须定在模型能做到的水平上。

### P1 的三个

**坑 4：续跑的"缓存"语义比"恢复"语义更危险。** 最初想的是"有存档就复用"，
但那样改完 prompt 再跑会拿到上一版的产出，而且**完全静默**——它看起来就是一次正常成功。
必须校验任务标签与输入变量，宁可报错要求换 `runId`。

**坑 5：中断路径必须自己落盘 `FAILED`。** 任务抛异常时如果不写状态，下次续跑看到的是
`RUNNING`，无法区分"还在跑"和"上次挂了"。`CrewException` 里带 `completedSteps` 也是同理：
只说"失败了"的异常没法用来决策。

**坑 6：`CrewOutput.millis` 的口径要提前定。** 如果让它包含复用任务的历史耗时，
"续跑省了多少时间"就看不出来了（续跑会显示 40s 而不是 0s）。现在的口径是"本次运行的
墙钟时间"，复用数单独放在 `reusedTasks`。

### P2 的三个

**坑 7：整数被写成浮点，跨协议才暴露。** 接入 MCP 后实测参数是 `limit=200.0`。
根因在 JSON 层：解析阶段所有数字都变成 `Double`，原样输出时 `200` 就成了 `200.0`。
对声明 `integer` 的参数，严格校验的服务端（zod 之类）会直接拒绝整个调用。
本地工具不会暴露这个问题——反射绑定对 `200.0` 是宽容的。**跨进程协议会把本地
"能跑"的东西照出原形**，这是接 MCP 的额外收益。

**坑 8：服务端反向请求不回复 = 静默挂起。** 服务端会发 `roots/list`、`sampling/createMessage`，
然后等回应。客户端若把"带 id 的入站请求"当成不认识的消息丢掉，服务端就永久卡住，
而客户端那边看到的是"工具调用超时"——报错信息指向错误的一方。必须显式回 `-32601`。

**坑 9：工具挂上去 ≠ 模型会用。** 接官方 `server-everything` 时挂了 5 个工具，
模型一次都没调（它们与任务无关）；换成 `word_count`（正好能解决"200 字上限"这个
它当下的困境）立刻被调用。选 MCP 服务端时，"有哪些工具"远不如"这些工具是否咬合当前任务"
重要。同理，`maxTools` 不是可选项：13 个工具的描述会挤掉任务描述本身。

### P3 的五个

**坑 10：存的是产出，检索键却是任务描述。** 第一版把记忆写成"任务标签 + 产出"，
结果下一轮同样的任务几乎召不回自己上次的结果。根因很直白：检索时用的 query 是
**任务描述**，而存进去的是**产出**，两者在语义空间里可能相距很远（一个说"分析成本"，
另一个讲"调试链路变长"）。**检索键与存储键必须是同一个东西。**
改成"任务描述 + 产出"之后，召回分数从召不回变成 0.89。

**坑 11：绝对阈值在 bge-m3 上不可用。** 按直觉写"相似度 < 0.5 算无关"，实测发现
完全无关的两句中文最高能到 0.538，而真正弱相关的只有 0.56，两个区间重叠。
这不是阈值调得好不好的问题，是绝对阈值这条路本身走不通。改用相对截断（保留最高分的 72% 以内），
绝对阈值只用于判断"整批候选是不是全是噪声"。

**坑 12：噪声地板会系统性误杀高价值记忆。** 加上 0.54 的地板后，三条精心写的长期记忆
（相关性 0.326 / 0.371 / 0.443）全部被挡。它们分别是两条输出规范和一条踩坑教训——
**价值最高的信息往往字面相关性最低**。这不是调整阈值能解决的，而是需要两条独立通道：
常驻注入（无条件生效的约定）与重要性豁免（高价值低相关的教训）。

**坑 13：被切断的代码块，检索看不出问题。** 通用分块按标点切，会把 ``` 围栏拦腰截断，
两半都是无法阅读的碎片。但它们仍然会被正常嵌入、正常检索命中、正常注入 prompt——
**检索质量指标完全正常，只有把 prompt 打印出来才看得见。** 40 KB 文档里 76 块有 27 块
带未闭合围栏。加围栏保护后降到 15，再按标题分节降到 66 块 15 个。

**坑 14：记忆会让模型抄自己上一轮的答案。** 第二轮跑同一条流水线，召回分 0.89，
模型第 1 步的产出与上一轮**逐字相同**。这既是记忆生效的证据，也是它的副作用：
SHORT_TERM 记忆给了模型一份标准答案，它不再重新推理。
结论是划清界限——短期记忆只服务同一次会话内的连贯性，跨运行要沉淀的应当是结论与教训。

### 工具集的两个

**坑 15：用 token 数论证工具集的价值，会得出"不值得做"的结论。** 4 个工具 185 tok、
1 个工具 64 tok，省 65% 听起来不错，但那只是**工具清单**的 65%，占一次请求输入的 3%。
更危险的是 A/B 两轮的输入差 1,505 tok，其中约 1,400 来自 A 那轮多出的一次重试——
差一点就把重试的运气算成工具集的功劳。小样本对比里，重试次数和采样随机性会轻易盖过
真实的效应量，必须把变量拆开单独量。真正的论据不是 token，而是 9.8 里 13 个工具一个都没被调用。

**坑 16：工具集一旦写回 Agent 字段就会串味。** CrewAI 里 `tools` 是 `Agent` 的属性，
照着抄最自然的写法是把"本轮可用工具"也塞进字段。但同一个 Agent 会被多个任务共用，
任务 A 收窄过的工具集会被任务 B 继承——而且**不会报错**，只是任务 B 少看到几个工具，
表现为"模型有时不调工具"。所以工具集必须是 `execute(...)` 的运行期参数。
连带的一个约束：提示词里的工具清单、`tools[]` 声明、调用分发、以及"先调工具再填 JSON"
那句措辞，必须全部从同一个注册表取值。只改其中一处，模型就会去调一个没有声明的工具。

## 十一、仍未对齐的部分

| 项 | 说明 | 建议 |
|---|---|---|
| 并行任务（`async_execution`） | 依赖拓扑 + 并发执行 | 单卡 Ollama 收益有限；"便宜模型跑草稿、贵模型评审"才有意义 |
| MCP 远程传输 | SSE / streamable HTTP | 传输层已是接口，新增实现即可；要处理会话 ID 与重连 |
| MCP 服务端 / A2A | 把本项目暴露成工具提供方 / Agent 间协议 | 服务端只需实现 `tools/list` + `tools/call`，`DemoMcpServer` 是起点 |
| 声明式配置 | CrewAI 1.15 已改 JSON-first | Jackson + JSON Schema 校验；`McpServerConfig` 已按此形状设计 |
| 完整 Flow 引擎 | 条件边、循环、并行分支 | 用你已有的 DAG 引擎，只保留 `CrewNode` |
| 向量索引 | 当前检索是全量扫描（千条量级约 2 ms） | 十万条以上再上 ANN；PgVector 只需替换 `MemoryStore.byScope` |

另外两条**明确不建议对齐**的：`planning=True`（多一轮调用，7B 上计划质量不稳，
还多一个失败点）和 hierarchical 的动态任务分解（流程用代码写更可复现）。
现在的"验收 + 打回"是 hierarchical 里唯一值钱的部分。

## 十二、这套代码的八个可复用结论

1. **契约 > 提示词**。`expectedOutput` 是一句话，`outputType` 是一套可校验的结构。
   前者靠模型自觉，后者靠代码兜底。凡是产出要被程序消费，就必须有契约。
2. **格式合法 ≠ 内容正确**。结构化输出把错误的可见度降低了，必须再配一层语义校验，
   否则你只是把"一眼假"变成了"看起来真"。
3. **校验要有退路**。三道闸都设了"重试耗尽则放行 + 记录 note"，保证链路不会因为校验器
   卡死，同时问题不会被静默吞掉。这两者必须同时成立。
4. **内核不能写 `System.out`**。一旦写死，就没法在 web 服务里用，也没法写断言测试。
   事件钩子不是"可观测性加分项"，它是库能被复用的前提。
5. **自主性要有边界**。Crew 只覆盖"需要模型自主协作"的那一段，前后步骤用代码写。
   `CREW_DAG=1` 那个例子里，Crew 只占三个节点中的一个——这才是生产形态。
6. **别为外部能力写适配层，让它从协议里来**。MCP 接完，新增一个外部工具的成本从
   "写类 + 写 Schema + 编译发布"降到"改一行启动命令"。代价是要处理三种失败语义
   （业务失败回灌、请求被拒回灌、连接故障冒泡）——这三者的边界不划清，
   接入越容易，线上越难查。
7. **记忆的检索键必须与写入键一致，而价值最高的信息往往最不像**。
   前者是纯粹的实现纪律：存"产出"却用"任务描述"去查，必然一无所获。
   后者是机制层面的限制：两条输出规范与任务主题的相似度只有 0.33，比随机无关还低，
   却对每一份产出都有约束力。任何"按相关性检索"的方案都会漏掉它们——
   必须单开一条"无条件注入"的通道。**不是所有该记住的东西都该被检索出来。**
8. **工具的可见性属于任务，不属于角色。** 同一个角色在不同阶段需要的东西不一样，
   而人设不该为此分裂。把工具做成绑定在任务上的不可变集合，还顺手解决了两个问题：
   服务端给多少不等于这一步要用多少；"业务失败回灌、连接故障冒泡"的边界只需要在一个
   分发点上定义。但别用 token 数去论证它的价值——省下的那点描述只占请求的 3%，
   真正的收益是让模型不必在十几个无关工具里做选择。

