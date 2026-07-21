# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Symphony Java 是一个基于 Akka Actor 模型的问题自动处理编排器。主要功能：
- 轮询 Linear API 获取候选 Issue
- 调度 Agent 处理 Issue
- 支持本地和 SSH 远程工作区
- 通过 Codex（AI）执行 Issue 处理

## 构建和运行

```bash
# 编译打包
mvn clean package

# 运行测试
mvn test

# 运行单个测试类
mvn test -Dtest=AppConfigTest

# 运行特定测试方法
mvn test -Dtest=AppConfigTest#testLoadValidConfig

# 启动应用（需要先创建 WORKFLOW.md）
export LINEAR_API_KEY="xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
./bin/symphony /Users/lixin/GitRepository/astro-parent/WORKFLOW.md  --i-understand-that-this-will-be-running-without-the-usual-guardrails
```

## 架构

```
SymphonyApplication (入口)
├── ActorSystem (Akka)
│   ├── Orchestrator (核心编排器)
│   │   ├── 管理 Issue 生命周期 (running/completed/blocked/retry)
│   │   ├── 定时轮询 Linear
│   │   └── 调度 Agent slots
│   └── StatusDashboard (终端UI)
├── Tracker (问题追踪接口)
│   ├── LinearTracker (生产环境)
│   └── MemoryTracker (测试用)
├── WorkspaceManager (工作区管理)
│   ├── 本地工作区
│   └── SSH 远程工作区
└── AgentRunner (Issue 执行器)
    └── CodexSession (AI 会话)
```

### 核心模块

| 模块 | 路径 | 职责 |
|------|------|------|
| `orchestrator` | `src/main/java/.../orchestrator/` | 核心状态机、轮询调度、重试逻辑 |
| `agent` | `src/main/java/.../agent/` | Agent 执行器、Codex turn 循环 |
| `tracker` | `src/main/java/.../tracker/` | Linear/Memory 追踪器实现 |
| `workspace` | `src/main/java/.../workspace/` | 工作区创建/删除、钩子执行 |
| `codex` | `src/main/java/.../codex/` | Codex API 会话管理 |
| `config` | `src/main/java/.../config/` | YAML 配置加载和 AppConfig 数据结构 |

### Orchestrator 状态机

- `running`: Agent 正在处理
- `completed`: 已完成（终态）
- `claimed`: 已声明待处理
- `blocked`: 被阻塞（等待输入）
- `retryAttempts`: 等待重试

### 配置层次 (AppConfig)

从 `WORKFLOW.md` 的 YAML 解析：
- `tracker`: Linear 连接配置 (endpoint, apiKey, projectSlug, activeStates, terminalStates)
- `workspace`: 工作区根目录
- `agent`: maxConcurrentAgents, maxTurns, maxRetryBackoffMs
- `codex`: command, turnTimeoutMs, promptTemplate
- `hooks`: afterCreate, beforeRun, afterRun, beforeRemove

## 关键设计

1. **Actor 消息传递**: Orchestrator 通过 Akka 消息与 AgentRunner 通信，使用 `OrchestratorProtocol` 定义消息类型
2. **异步追踪**: Tracker 接口所有方法返回 `CompletionStage`，实现非阻塞 API 调用
3. **工作区隔离**: 每个 Issue 有独立工作区，支持本地/SSH远程，路径经过安全验证
4. **钩子系统**: 工作区生命周期钩子（afterCreate, beforeRun 等）可执行任意 shell 命令
