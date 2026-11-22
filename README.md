# Merged into `sonar`

QuickBuild 把 SonarQube 当一个集成（扫描 + 质量门禁在同一份文档里），我们把它拆成了两个原子，而 `sonar` 本来就会等门禁。
现在门禁是它的一个动作：

```groovy
// 旧
runAtom(atom: 'sonar-gate', version: '1.latest', inputs: [projectKey: 'acme:api', ...])
// 新
runAtom(atom: 'sonar', version: '1.latest', inputs: [action: 'gate', projectKey: 'acme:api', ...])
```

参数名两边都认（`sonarUrl`/`hostUrl`、`token`/`sonarToken`、`componentKey`/`projectKey`）。
本仓库只读保留，便于历史构建追溯。
