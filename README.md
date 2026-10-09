# FreeCoreAgent

FreeCore Minecraft 社区游戏端网关插件 (Paper / Purpur 1.21+)。

---

## 插件定位与设计原则

`FreeCoreAgent` 遵循**极简无状态网关**原则，专职作为 Minecraft 游戏端与外部认知中枢（CoreNyan）之间的桥梁：

- **0 存储、0 记忆**：所有的认知大模型调用、记忆库（Memory Vault）、知识库（Skills）以及玩家好感度数据均 100% 托管于中枢 `FreeCore-CoreNyan`，插件内部不保留任何记忆与技能数据。
- **纯粹传感器与信使 (Sensor & Messenger)**：
  - 监听游戏内公屏聊天与事件，通过 Redis 神经总线即时上报至中枢 `freecore:agent:mc:inbound`；
  - 接收中枢的下发广播或私信消息，在游戏内原汁原味地呈现给玩家。
- **安全远程工具执行器 (Remote Tool RPC)**：
  - 响应 CoreNyan 的调度请求，安全执行只读/脱敏诊断工具（CoreProtect 方块回溯、容器查验、Spark 实时性能采样、配置文件查阅等）。
- **Tab 列表虚拟在线展示 (Virtual Tab Player)**：
  - 在游戏内 Tab 列表保持专属小可形象与皮肤展示。

---

## 核心代码架构

```
io.github.freecoreagent/
├── FreeCoreAgentPlugin.java          # 插件主入口与服务装配
├── command/
│   └── AgentCommand.java             # /fca 管理命令 (/fca reload, status, issues, say)
├── config/
│   └── LanguageManager.java          # 语言与提示信息管理
├── perception/
│   ├── AgentPerceptionService.java   # 玩家与世界环境感知服务
│   └── WorldEventPerceptionListener.java # 游戏内事件监听
├── redis/
│   └── AgentRedisBridge.java         # Redis 跨服双向订阅发布中继
├── service/
│   ├── AgentInteractionService.java  # 游戏内消息输入/输出交互分发
│   ├── ChatIntentClassifier.java     # 本地轻量意图分类辅助
│   └── SparkDiagnosticService.java   # Spark 性能采样对接
├── tab/
│   └── VirtualTabPlayerManager.java  # Tab 列表虚拟小可实体呈现
├── ticket/
│   ├── TicketGui.java                # 工单待办可视化箱子界面
│   └── TicketManager.java            # 运维工单持久化存储
└── tool/
    ├── AgentToolExecutor.java        # 远程运维安全工具执行器
    └── SafeConfigLens.java           # 配置文件只读安全透镜
```