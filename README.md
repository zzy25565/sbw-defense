# SBW Defense

Superb Warfare 主防与爆反附属（Minecraft 1.21.1 / NeoForge）。

> 当前版本 **1.9.0**，成品 jar 见 [Releases](https://github.com/zzy25565/sbw-defense/releases/latest)。
> 仓库只放源码，编译依赖（`libs/`）因版权不随仓库分发。

## 功能

- **ERA（爆反）**：车体五面分格独立计血，命中消耗一块。对 SBW 自家弹药 80% 减伤、对外部命名空间弹药 100% 全挡（两档可配）；
  全挡时不跳过 SBW 自己的护甲 / 入射角 / 部件结算，战斗部照炸但爆炸伤害封顶，避免贴脸殉爆带走车体。
- **APS（主防）**：按车型布局表在对应扇区发射**真实拦截弹**，不是凭空删弹。同一目标只占用一发拦截弹，
  命中 / 失效 / `engageTimeout` 超时才释放；一辆车每 tick 只更新一次，多人乘车不会让装填加速。
- **HUD**：一行 `ERA 72%   APS 3/4`，颜色随最弱子系统变化。发射显示 `LAUNCH`、**确认拦截**才闪 `INTERCEPT`，
  事件带车辆 ID，只对本车乘员显示。

## 兼容性

| 目标 | 版本 |
|---|---|
| Minecraft / NeoForge | 1.21.1 / 21.1.203+ |
| Superb Warfare | 0.8.9.1 / 0.8.9.2 |
| [SBW VLS 载具包](https://github.com/zzy25565/sbw-vls) | 1.0.0b+（`zzyvehicles:` 命名空间） |
| SBW Radar | 可选，非必需 |

VLS 1.0.0b 起把命名空间合并成 `zzyvehicles`，本附属的默认布局表已切到该命名空间，
并与 VLS 1.0.0k 的 **281 份载具定义**逐一核对（24 台预填车，5 台带 APS）。
旧配置里的 `sbw_vls:*` 写法在启动时自动改写为 `zzyvehicles:*`（保留自定义条目，原文件备份 `.bak`），
运行时查询也走 `canonical()` / `alias()` 兜底。

## 编译

`libs/` 需放入（不随仓库分发）：`minecraft-neoforge-compiled-*.jar`、`superbwarfare-*.jar`、
`loader`、`bus`、`modlauncher`、`annotations`、`joml`、`gson`、`slf4j-api`、
`datafixerupper`、`brigadier`、`netty-buffer`、`netty-common`、`mergetool-api`

```bash
gradle jar
```

没有 Gradle 也可以直接 `javac`：

```bash
javac -encoding UTF-8 -cp "libs/*" -d build/classes $(find src/main/java -name "*.java")
printf 'Manifest-Version: 1.0\r\nImplementation-Version: 1.9.0\r\n\r\n' > build/MANIFEST.MF
jar --create --file build/libs/sbw_defense-1.9.0.jar \
    --manifest build/MANIFEST.MF -C build/classes . -C src/main/resources .
```

## 文档

- [`主防与爆反_附属说明.md`](主防与爆反_附属说明.md)：行为、配置项、车型布局表格式、改动记录
- [`APS与ERA模型需求.md`](APS与ERA模型需求.md)：车体分块模型的美术需求

## 许可

GPL-3.0-only，见 [LICENSE](LICENSE)。
