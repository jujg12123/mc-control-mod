# MC-Control：My Neuro 控制 Minecraft（Fabric 1.20.1）

让 My Neuro 的 AI 通过 WebSocket 操控 Minecraft 客户端（移动、挖掘、放置、合成、寻路、战斗等），
mod 负责执行动作并回报游戏状态，插件负责把状态注入 AI 上下文并封装 mc_ 工具。

## 项目结构

- `mod/` — Fabric 客户端 mod（Java 17，Minecraft 1.20.1，Fabric API 0.92.2）
  - `ActionExecutor.java` — 动作执行器：长任务（寻路/挖掘/合成）为客户端 tick 状态机
  - `StateCollector.java` — 游戏状态采集（位置/背包/视线/实体/行为日志），200ms 去重上报
  - `ControlServer.java` — 本机 WebSocket 服务（localhost:8765）
  - `RecipeLookup.java` — 动态配方查询（原版 + 模组配方）
  - `AutoBehaviorManager.java` — 自动行为（自卫/防饥饿/防卡/拾取）
- `plugin/` — My Neuro 插件（Node.js）
  - `index.js` — 插件入口：mc_ 工具、自主任务循环、系统提示词注入
  - `mc-client.js` — WebSocket 客户端（断线指数退避重连）
  - `metadata.json` / `plugin_config.json` — 插件部署清单与配置项

## 构建 Mod

环境要求：JDK 17+、Gradle 8.x（wrapper 指向 gradle-8.5）。

方式一（推荐）：GitHub Actions 自动构建
- 把项目推到 GitHub 仓库后，`.github/workflows/build-mod.yml` 会自动构建；
  也可在仓库 Actions 页面手动触发（workflow_dispatch）。
- 构建产物在 Actions 运行的 Artifacts 里：`mc-control-mod`（`mod/build/libs/*.jar`）。

方式二：本机构建（项目未附带 `gradlew`/`gradle-wrapper.jar`，需自备 Gradle）
```
cd mod
gradle build
```
产物：`mod/build/libs/mc-control-mod-<version>.jar`，放入 `.minecraft/mods` 并安装
Fabric Loader 与 Fabric API（1.20.1）。

- 注意：项目源码为 UTF-8 编码，`build.gradle` 已配置 `options.encoding = 'UTF-8'`，
  避免中文 Windows（GBK 默认编码）下编译报“不可映射字符”。

## 部署插件

把 `plugin/` 整个目录复制到 My Neuro 的插件目录（与已安装的 mc-control 插件同结构），
然后在插件设置里确认：

- `ws_url`：`ws://127.0.0.1:8765`（mod 默认监听地址）
- `auto_control`：是否启动后自动给 AI 设定生存目标（默认 false）

## WebSocket 协议

- 插件 → mod：动作 JSON，如 `{"action":"go_to_pos","x":..,"y":..,"z":..,"call_id":123}`
- mod → 插件（状态）：`{"type":"state", ...}`，每 200ms 上报、内容未变化时不上报
- mod → 插件（结果）：`{"type":"action_result","action":"...","call_id":123,"success":true,"message":"..."}`
  - `call_id` 由插件生成、mod 原样带回，插件按它精确匹配结果；
    未携带 `call_id` 的旧版 mod 回传时插件退回 FIFO 匹配。
  - 长任务被新动作打断、或被 `stop_nav` 停止时，mod 会为旧任务补发失败结果，避免插件空等超时。

## 寻路说明

- 导航采用“窗口式”卡住检测：每 0.5 秒结算一次位移，避免逐 tick 抖动误判（旧版会因此视角抽搐）。
- 遇障处理：卡住 1 秒后自动锁定并挖掘正前方的阻挡方块（白名单内方块），
  头顶被挡则挖头顶；不可挖掘（基岩等）或挖掘 4 秒无效时自动侧移绕行；
  绕行有进展后恢复正常导航。所有状态变化会写入行为日志（behavior_log）供 AI 感知。

## 合成说明

- `mc_craft` 动态查询全部配方，优先用配方书合成（`clickRecipe`）；
  若服务端因配方未解锁而静默忽略（无产物），自动回退为逐格 `clickSlot` 手动摆放，
  有序/无序合成均可可靠完成。
- 3×3 配方需要 5 格内有工作台（自动寻找）；2×2 配方在背包直接合成。

## 主要工具（插件）

`mc_look / mc_status / mc_move / mc_dig / mc_place / mc_jump / mc_switch / mc_turn /
mc_use / mc_sneak / mc_unsneak / mc_drop / mc_goToBlock / mc_goToPos / mc_stopNav /
mc_goal / mc_goalDone / mc_digBlock / mc_digDown / mc_goToSurface / mc_attackEntity /
mc_equip / mc_consume / mc_craft / mc_queryRecipe / mc_enableAuto / mc_disableAuto`

## 与 My Neuro SDK 的适配（已在 D:\hermes\my-neuro 源码核实）

- `Plugin` 基类生命周期与钩子：`onStart/onStop/onUserInput/onLLMRequest` 等均存在。
- `context.sendMessage / addSystemPromptPatch / removeSystemPromptPatch / getPluginConfig / log` 均存在。
- `onLLMRequest` 的 `request.tools` 与 SDK 内部 `allTools` 是同一数组引用，
  插件内必须用 `splice` 原地修改才能生效（重新赋值无效）。
- 冲突工具屏蔽名单已按 built-in 插件真实注册名更新：
  `execute_code / install_packages / type_text / click_mouse / press_arrow / take_screenshot / pc_screen_click`。
- 插件部署目录：`D:\hermes\my-neuro\live-2d\plugins\built-in\mc-control-plugin\`（已安装修复版）。

## 已知限制

- 寻路是直线 + 挖障碍/绕行的简单导航，无完整 A* 路径规划；复杂地形可能超时。
- `go_to_surface` 需要头顶 20 格内最终能露天，否则以插件侧 30s 超时结束。
- 本机无 JDK/Gradle/Node 时无法在此环境直接构建，请在装有 JDK 17 的机器上构建。
