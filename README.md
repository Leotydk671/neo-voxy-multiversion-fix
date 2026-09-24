# Neo Voxy · 多版本

[English](README_EN.md)

Neo Voxy 由 **JohnSnow** 维护，基于 [NHblock714/voxy](https://github.com/NHblock714/voxy) 继续维护多版本、客户端优化与可选模组联动。

> [!IMPORTANT]
> 更新 Neo Voxy 后若出现异常，请先删除 Neo Voxy 配置文件和对应存档中的 Voxy 缓存。
>
> **构建产物**：GitHub Actions 构建产物已被人为删除。请 Fork 本仓库，并在自己的仓库中运行 `Build Neo Voxy multiversion` 后下载产物；也可按照下方教程手动构建。本分支偶尔可能仍显示尚未及时删除的 Actions 构建产物。
>
> **服务器数据共享**：Neo Voxy 兼容 [voxy-server-side](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge)，可在服务器端部署以共享 LOD 数据。
>
> **渲染前置**：NeoForge 使用 Sodium，Forge 使用 Embeddium。Sinytra Connector 与 Forgified Fabric API 不是本项目依赖，不建议额外安装。

## 支持版本

| 版本 | 安装位置 | 渲染前置 | Java | 发布文件 |
|---|---|---|---:|---|
| Minecraft 1.21.1 · NeoForge | 客户端必装，服务端可选 | Sodium 0.8.x / Iris 1.8.12+ | 21 | `neo-voxy-0.5.0-alpha.4-mc1.21.1-neoforge.jar` |
| Minecraft 1.20.1 · Forge | 仅客户端 | Embeddium / Oculus | 17 | `neo-voxy-0.3.5-alpha.2-mc1.20.1-forge-client.jar` |
| Minecraft 26.1.2 · NeoForge | 仅客户端 | Sodium 0.9.2 / Iris 1.11.4+ | 25 | `neo-voxy-0.3.4-mc26.1.2-neoforge-client.jar` |

## 本 Fork 的修复与新增（water-fix 分支）

此分支基于上游最新源码，包含以下修改：

### 修复的缺陷

- **远景水面渲染错误（摩尔纹 / 缺失）**：原版把水面顶面路由到逐体素"形状面"路径，且 `encodeFluidStep` 会把台阶高度写进 quad 的形状面标志位（位 42~45），导致合并后的水面散成 1×1 小面；现已改回普通合并面路径（与 0.2.15 / NH 0.4.0 移植版一致）。
- **流体高度掩码错误**：`modelFluidHeight` 的 5 位掩码把 isFluid 标志位（位 12）一并读入，高度变成 h+16，导致水面顶面被抬高约 1.8 格；掩码已改为 4 位。
- **区块遮罩被相机所在箱子填满**：遮罩光栅不跳过相机所在的区块箱子，其跨近裁剪面的投影铺满全屏并写入远深度，使原版视距内的 LOD 水与原版水重叠闪烁；现已跳过该箱子。
- **边界共面 z-fighting**：LOD 与原版在交接处共面，深度测试逐像素翻边闪烁；顶点级加入 1 ulp 近向深度偏置，让 LOD 稳定胜出。
- **Intel/Windows 驱动兼容**：Intel Windows 驱动会静默丢弃 DSA named-clear（遮罩/距离桶/模板初始化依赖它们）；新增一次性检测 + `glClearBuffer*` 降级路径（`DsaClearPath`）。
- **光照图采样约定**：上游最近恢复的"纹素中心"采样面向 MC 26.x 的约定，与本移植目标（1.21.1/1.20.1）的原版差半档亮度；默认改回左边缘以匹配原版，并提供开关（见下）。

### 新增配置项

- **光照图采样方式（lightmapTexelCenter）**：默认关闭 = 纹素左边缘（与 1.21.1/1.20.1 原版一致）；开启 = 纹素中心（MC 26.x / 上游 Voxy 的约定，整体偏亮半档）。开关为运行时 uniform，切换立即生效，无着色器重编译开销。

### 模组兼容

- **Physics Mod（3.0.34，NeoForge 1.21.1）**：已实测兼容。其海洋物理会改流体高度数据，原版的形状面路径处理不了；本分支的普通合并面路径不受影响，水面正常。

> 构建方式与上游一致：Fork 后运行 `Build Neo Voxy multiversion` Actions，或按下方教程手动构建。

## 安装与服务器兼容（1.21.1）

1.21.1 从 0.5.0 起只发布一个 JAR，不再区分联动版与纯客户端版。客户端必须安装才能使用远景；服务端可选安装同一个 JAR，服务端不需要 Sodium 或 Iris。不要同时安装旧的两个版本。

- 服务器未安装 Neo Voxy：保留本地地形 LOD、光影、缓存及可在客户端完成的联动。不会向不支持的服务器发送 Neo Voxy 订阅包。
- 服务器提供对应通道：启用远景玩家与载具、Create 列车和动态结构位置同步。无对应通道时自动停用依赖它的功能，设置页显示不可用，保留用户开关供下次支持的连接使用。
- Create 动态结构没有服务端支持时仍可显示本地静态快照；视距外实时运动不可用。扩展区块请求仅用于单人内置服务器。
- 地形 LOD 只包含客户端已获取或已有缓存的数据；服务端可选不意味着能绕过服务器视距获得未知地形。服务器安装其他模组的要求不受此规则影响。

1.20.1 与 26.1.2 保留现有平台版本及安装位置，本次未为其新增服务端功能。

## 实验性优化

实验性页面包含静止时优化、显卡负担优化、内存缓存、远近地形过渡和光影简化。提供静止复用、遮挡精度、Hi-Z 批量计算与缓存上限等选项；说明列出帧率、画面、延迟和内存取舍。Hi-Z 编译失败会回退并提示。请逐项比较效果，不保证所有硬件均有性能提升。

## 原版功能

| 原版功能 | 1.21.1 | 1.20.1 | 26.1.2 | 功能说明 |
|---|:---:|:---:|:---:|---|
| 地形 LOD 与多级细节 | ✅ | ✅ | ✅ | 渲染远距离地形 |
| Sodium / Embeddium 设置集成 | ✅ | ✅ | ✅ | 提供 Neo Voxy 设置入口 |
| Iris / Oculus 光影管线 | ✅ | ✅ | ✅ | 提供对应平台的光影渲染支持 |
| 环境雾、天空雾与流体效果 | ✅ | ✅ | ✅ | 修复远景雾效与流体显示 |
| 圆形 LOD 交接 | ✅ | ❌ | ✅ | 在原版区块与 LOD 之间进行圆形交接 |
| 地面植物交叉模型 | ✅ | ✅ | ✅ | 使用轻量交叉模型显示植物 |
| 树叶 LOD 模式 | ✅ | ✅ | ✅ | 提供性能、平衡和质量模式 |
| 扩展区块请求 | ✅ | ✅ | ✅ | 单人游戏中请求更远区块，默认关闭 |
| LOD 群系水色 | ✅ | ✅ | ✅ | 处理不同群系之间的水色过渡 |
| LOD 构建压力控制 | ✅ | ✅ | ✅ | 调整帧率与构建速度的优先级 |
| 世界曲率 | ✅ | ✅ | ✅ | 在 GPU 顶点阶段弯曲远景地形 |
| 远景信标光束 | ✅ | — | — | 在远距离显示信标光束 |
| 扩展高度世界坐标 | ✅ | — | — | 支持超出原版高度范围的 LOD |

`✅` 表示支持，`❌` 表示未提供，`—` 表示不适用。圆形 LOD 交接与光影包自带的交接功能同时启用时，建议关闭其中一项。

## Mod 联动功能

联动功能仅在对应 Mod 已安装时启用。版本列表示当前联动代码的 Minecraft 与 Mod 验证基线。

| Mod（中文 / English）与版本 | 状态 | 联动功能 |
|---|:---:|---|
| [机械动力 / Create](https://www.curseforge.com/minecraft/mc-mods/create)<br>MC 1.21.1 + Create 6.0.10 | ✅ | 覆盖远景列车、轨道、动态结构与动力部件 |
| [机械动力：航空学 / Create Aeronautics](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics)<br>MC 1.21.1 + Aeronautics 1.3.2 | 🧪 | 覆盖模拟结构的远景显示 |
| [机械动力：模拟 / Create Simulated](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics)<br>MC 1.21.1 + Simulated 1.3.2 | 🧪 | 覆盖激光指示器远景光束 |
| [机械动力：交错电网 / Create: Power Grid](https://www.curseforge.com/minecraft/mc-mods/power-grid)<br>MC 1.21.1 + Power Grid 0.6.1 | ✅ | 覆盖悬垂线、方块折线、软线和灯串线 |
| [机械动力：伪装方块+ / Create: Copycats+](https://www.curseforge.com/minecraft/mc-mods/copycats)<br>MC 1.21.1 + Copycats+ 3.0.9 | ✅ | 覆盖斜坡、切片、门、传动杆和齿轮的专属 LOD 模型与材质 |
| [Sable / Sable](https://www.curseforge.com/minecraft/mc-mods/sable)<br>MC 1.21.1 + Sable 2.0.5 | ✅ | 覆盖远景物理结构与深度衔接 |
| [节气 / Ecliptic Seasons](https://www.curseforge.com/minecraft/mc-mods/ecliptic-seasons)<br>MC 1.21.1 + Ecliptic Seasons 0.15.0-rc-3-1 | ✅ | 覆盖季节积雪、结冰水体、季节模型与颜色 |
| [模拟殖民地 / Domum Ornamentum](https://www.curseforge.com/minecraft/mc-mods/domum-ornamentum)<br>MC 1.21.1 + Domum Ornamentum 1.0.236-snapshot | ✅ | 覆盖精细装饰模型与材质 |
| [框架方块 / FramedBlocks](https://www.curseforge.com/minecraft/mc-mods/framedblocks)<br>MC 1.21.1 + FramedBlocks 10.6.1 | ✅ | 覆盖框架方块与伪装材质 |
| [小方块 / LittleTiles](https://www.curseforge.com/minecraft/mc-mods/littletiles)<br>MC 1.21.1 + LittleTiles 1.6.0-pre226 | 🧪 | 覆盖静态小方块 LOD 网格 |

## 主要选项

- 树叶模式：在性能、平衡和质量之间选择树叶 LOD 的显示方式。
- 扩展区块请求：单人游戏中请求原版视距之外的区块，默认关闭，最多请求 48 区块。
- LOD 构建压力：在最高帧率和最高追赶速度之间调节构建预算。
- 圆形 LOD 交接：控制原版区块与 LOD 的交接效果；使用光影包自带交接时建议关闭。
- 渲染精度：七档滑块，从极低到极高；默认标准档（256），较高档为 123，极高档为 28。提高精度会增加细节和开销。树叶模式使用性能、平衡、质量三档滑块。
- 世界曲率：控制远景地形弯曲程度，0 为关闭。
- 联动开关：各项 Mod 联动均提供独立的启用开关和距离选项。

## 构建

Windows 单独构建：

```powershell
.\scripts\build.ps1 neoforge-1.21.1
.\scripts\build.ps1 client-1.20.1
.\scripts\build.ps1 client-26.1.2
```

构建全部版本：

```powershell
.\scripts\build-all.ps1
```

脚本优先读取 `JAVA_HOME_17`、`JAVA_HOME_21` 和 `JAVA_HOME_25`。Linux/macOS 可使用 `scripts/build.sh` 和 `scripts/build-all.sh`。最终产物复制到 `dist/`。

## 许可证

请查看各版本随附的许可证文件。
