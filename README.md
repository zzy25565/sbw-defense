# SBW Defense

Superb Warfare 主防与爆反附属（Minecraft 1.21.1 / NeoForge）。

## 功能

- **ERA 血量**：爆反装甲按节点独立计血，命中即消耗
- **APS 拦截**：主动防护系统按扇区拦截来袭弹药
- **极简 HUD**：一行 `ERA 72%   APS 3/4`，颜色随最弱子系统变化，拦截成功时行尾闪 `INTERCEPT`

## 编译

`libs/` 需放入（不随仓库分发）：`minecraft-neoforge-compiled-*.jar`、`superbwarfare-*.jar`、
`loader`、`bus`、`modlauncher`、`annotations`、`joml`、`gson`、`slf4j-api`、
`datafixerupper`、`brigadier`、`netty-buffer`、`netty-common`、`mergetool-api`

```bash
gradle jar
```

---

## 第三方依赖与许可

本项目是 [Superb Warfare](https://github.com/atsuishio/superbwarfare) 的附属模组，运行时依赖该模组。

| 项 | 说明 |
|---|---|
| 上游模组 | Superb Warfare（Atsuishio、Roki27、Light_Quanta 及贡献者） |
| 上游许可 | **GNU GPL v3.0**（见其发布 jar 内 `COPYING` / `COPYING.LESSER`） |
| 本仓库内容 | 仅为 ERA / APS 功能的原创实现，通过上游公开 API 交互 |
| 是否包含上游文件 | **否**。不含 Superb Warfare 的源码、资源或二进制 |

因运行时链接 GPL-3.0 组件，本附属同样以 **GPL-3.0** 分发。若需其他许可安排，请自行确认合规性。

本仓库**不包含**任何第三方游戏资源，也不包含构建所需的依赖 jar（`libs/` 已列入 `.gitignore`）。

## 依赖关系

| 方向 | 项目 | 说明 |
|---|---|---|
| 运行依赖 | Superb Warfare (GPL-3.0) | 必需 |
| 可选搭配 | [sbw-radar](https://github.com/zzy25565/sbw-radar) | 两者 HUD 分居屏幕左上与左下，同装不冲突 |
| 被依赖 | [sbw-vls](https://github.com/zzy25565/sbw-vls) | 载具包中的 ERA / APS 数据由本模组解释 |
