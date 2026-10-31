# jenkins-atom-sonar-gate

读 SonarQube 的质量门禁，**红了就让这一步失败**，并把未达标的条件写进消息。

`sonar-scan` 负责扫描与上报，这个原子负责另一半：门禁。两件事分开是因为它们的时机不同——门禁通常在等分析完成后跑，而且它的结论直接决定构建该不该红。

只写「quality gate failed」会让人去 UI 里找原因，所以消息形如：

```
the quality gate is ERROR: new_coverage is 71.4 (required ≥ 80); new_bugs is 3 (required ≤ 0)
```

401/403 归 `USER`（令牌或权限问题），404 单独提示「项目 key 或扫描是否存在」，连不上归 `ENVIRONMENT`。

```bash
./build.sh && ./tools/smoke.sh
```
