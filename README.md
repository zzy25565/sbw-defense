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
