# jenkins-atom-sonar-gate

读 SonarQube 的质量门禁，红了就让这一步失败（或把构建标成不稳定），并把未达标的条件写进消息。

`sonar-scan` 负责扫描与上报，这个原子负责另一半：门禁。只写「quality gate failed」会让人去 UI 里找原因，所以消息形如：

```
the quality gate is ERROR: new_coverage is 71.4 (required >= 80); new_bugs is 3 (required <= 0)
```

## 两种读法

直接读（默认）是一次 GET：`/api/qualitygates/project_status?projectKey=...`。

但这有个真实的坑：扫描刚上报时分析还在常量引擎队列里，此刻读到的是**上一次**的门禁。打开 `waitForAnalysis` 后，原子先轮询 `/api/ce/component?component=<key>`，等队列空且当前任务是 `SUCCESS`，再去读门禁，读到的就是这次构建自己的结论。

SonarQube 8.0 把这个参数拼成 `componentKey`，8.8 之后是 `component`；第一次拿到 400 时原子自动换拼法重试，不需要配置。

## 参数

- `sonarUrl`：SonarQube 根地址
- `token`：访问令牌（以 Basic 用户名形式发送，密码为空）
- `projectKey`：项目 Key
- `branch` / `pullRequest`：可选，传给门禁查询
- `waitForAnalysis`：先等这次的分析跑完（默认关）
- `waitTimeoutSeconds`：等分析的上限，默认 300，夹在 5..3600
- `pollIntervalSeconds`：轮询间隔，默认 10，夹在 1..60
- `buildStatus`：`FAILURE`（默认，这一步失败）或 `UNSTABLE`（这一步成功，构建标不稳定）
- `failOnWarning`：`WARN` 也算红（默认关）
- `timeoutSeconds`：单次 HTTP 超时，默认 60

出参：`gateStatus`、`passed`、`failingConditions`、`conditions`、`buildStatus`、`ceTaskStatus`。

## 和 Sonar Quality Gates 插件的关系

按 `org.quality.gates` 的 `QGPublisher` 复刻：先等 `/api/ce/component`（队列空且 current 为 `SUCCESS`），再读 `/api/qualitygates/project_status`，红了按 `buildStatus` 走 `setResult(UNSTABLE)` 或构建失败；门禁状态只认 `OK` 前缀为绿。

四处不同，都是有意为之：

1. 插件把异常吞掉只打印堆栈，构建仍是绿的；原子把错误分类上报（`USER` / `THIRD_PART` / `ENVIRONMENT`），JSON 解析失败不会静默通过。
2. 分析任务 `FAILED` / `CANCELED` 时插件一直轮询到超时；原子当场报「这次没有新门禁」，比等五分钟有用。
3. 插件靠全局配置的实例列表带用户名密码登录；原子按步骤收 `sonarUrl` + 令牌，多实例就是多一步。
4. 插件把 `WARN` 一律当红；原子默认只认 `ERROR`，要一致就打开 `failOnWarning`。

## 验证

```bash
./build.sh && ./tools/smoke.sh
```

假 Sonar 有状态机：分析没跑完之前，门禁接口只答**上一次**的结论（`--stale-gate`）。所以「等了才读到新门禁」不是看日志相信的，而是靠结果对比证明的——同一个假服务器，不等待时原子读到旧门禁并失败，等待时才通过。另有超时、分析失败、`componentKey` 拼写回退、`UNSTABLE` 映射、401/404 各一条断言。
