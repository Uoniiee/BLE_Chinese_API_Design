# BLE 中文 API 设计

本仓库用于整理 Android BLE 通信库的中文 API 设计文档。

目标是把 BLE_BBS 与 BLE_Rock_Paper_Scissors 中可复用的蓝牙能力抽成一个 Android library module，让业务应用只关心连接状态、收到的消息和发送接口。

## 文档

- [BLE 中文 API 与 Android Library Module 设计](docs/ble-chinese-api-design.md)
- [最小示例 App 草案](docs/minimal-sample-app.md)
- [Library 使用说明](docs/library-usage.md)
- [Library 发布说明](docs/library-publishing.md)
- [调试版本与测试记录](docs/release-records.md)
- [Issue #1 可靠传输层整理计划](docs/issue-1-reliable-transport-plan.md)

## 当前状态

Issue #1：可靠传输层整理与接口收敛，已在 `v0.6.1-debug` 完成阶段性交付。

当前开发分支：`issue-1-reliable-transport`

本阶段已把稳定 ID、方向仲裁、通知订阅确认、发送队列、写入串行化、
超时失败反馈、邻机老化和传输帧去重整理进 library 与示例 App。

维护者已确认 `v0.6.1-debug` 三机测试通过，并已按 Issue #1 支付
人民币 400 元。

## 设计范围

第一阶段只做设计文档，不修改 BLE_BBS 或 BLE_Rock_Paper_Scissors 现有代码。

文档重点说明：

- library module 的目标与边界
- 扫描、广播、连接、收发、帧解析的职责划分
- 面向业务 App 的中文公开 API
- BBS、剪刀石头布等业务如何接入
- 后续最小示例 App 应验证哪些行为

## 后续阶段

如果设计方向确认，后续可以继续建设：

- Android library module
- 最小示例 App
- 简要使用文档
- library 发布文档

## 当前代码

第二阶段已新增：

- `:ble_chinese_api`：Android BLE 中文 API library module
- `:sample_app`：最小文本收发示例 App

当前示例已验证两台 Android 真机之间的最小文本双向收发，也已验证
三台 Android 真机之间的短消息互通。当前实现是基于 BLE GATT write
与 notification 的最小可靠传输示例，不是标准 Bluetooth Mesh 协议；
多跳路由、洪泛、MTU 协商和长消息分片可作为后续迭代。

构建命令：

```powershell
.\gradlew.bat --no-daemon --console=plain :ble_chinese_api:assembleDebug :sample_app:assembleDebug
```

## 调试版本记录

### 当前本地待测版本：v0.6.2-debug

更新时间：2026-06-01

本版本基于 BLE_RPS 剪刀石头布示例 `v1.0.30` 真机测试过程中暴露的
断开/重连问题，对 library 的发送链路做保守修复：

- 当已有通知订阅通道时，`发送()` 优先走 GATT server notification，
  不再先等待可能陈旧的 GATT client write 通道。
- notification 已成功发起时，GATT client write 改为后台补发与清理，
  避免陈旧写入通道拖慢业务消息。
- 写入超时从固定 5 秒改为配置项 `写入超时毫秒`，默认 1.5 秒。
- 当 notification 请求被系统拒绝时，移除对应订阅邻机并更新邻机状态。

`v0.6.1-debug` 仍保留为 Issue #1 已验收基线；`v0.6.2-debug` 是后续
基于 RPS 反馈的稳定性待测版本。

### 当前最新已验证版本：v0.6.1-debug

更新时间：2026-05-31

本版本用于验证可靠传输层整理后的 Android BLE 中文 API library 与
最小示例 App。

已完成核心能力：

- 稳定应用层设备 ID，避免只依赖 Android 随机蓝牙地址。
- 通信方向仲裁，减少双向同时连接造成的碰撞。
- 只有通知订阅真正写入成功后，才登记为可写通道。
- 发送队列与 `onCharacteristicWrite` 串行化写入。
- 5 秒发送超时与失败反馈。
- 60 秒邻机老化清理。
- 7 字节传输帧头与消息编号去重。
- 横竖屏切换时保留日志和通信状态。

已验证核心流程：

- 扫描并发现邻机。
- 建立 GATT 连接。
- 订阅通知。
- 串行写入消息。
- 接收对端消息。
- 去重自己发出的消息和已见过的消息。

测试结论：

- 本地 A/C 两台 Android 真机可互发中文短消息。
- 本地 A/B/C 三台 Android 真机可完成短消息互通。
- 维护者反馈三星手机、平板等三台设备测试通过。
- 测试中未再出现原始 `BCAPI1...` 帧文本暴露到界面的问题。
- 测试中未再出现自己收到自己消息的重复显示问题。
- 已按 Issue #1 收到人民币 400 元悬赏。

测试注意事项：

- 不同 Android 版本和厂商系统的权限提示不完全一致。
- 测试前建议确认蓝牙已开启，并允许“附近设备”权限。
- 如果系统暴露“定位/位置信息”权限，也建议允许并开启系统定位。
- 若权限不完整，可能出现扫描不到邻机、能广播但不能被发现、
  或连接通道/可写通道一直为 0 的现象。
- 示例 App 中的“连接通道”和“可写通道”表示 BLE 通信通道数量，
  不是物理设备数量。三台设备互通时显示 4/4 是当前拓扑下的正常现象。
- 当前未做 MTU 协商，仍跑在 BLE 默认 ATT MTU 上。传输帧头占 7 字节，
  单包有效载荷约 13 字节，约 4 个中文字；更长消息需要后续补充分片或
  MTU 协商。

已知界面问题：

- v0.6.1 已修复横竖屏切换导致日志和界面状态清空的问题。
- 三星设备上仍反馈输入法弹出时按钮可能被遮挡，后续可继续针对 UI
  布局处理。
- 这些属于示例 App 的测试体验问题，不影响 v0.6.1 作为 BLE
  最小可靠传输验证版本。

详细版本路径、构建命令和测试记录见：

- [调试版本与测试记录](docs/release-records.md)

### 历史记录：v0.3.1-debug

本版本用于回应真机测试中的三个问题：

- 点击“启动通信”时会先清理旧通信资源，不再要求测试前手动点击“停止通信”。
- 示例 App 顶部显示版本号，便于确认安装的是当前调试版。
- 示例 App 输出通信过程调试日志，包括 GATT 服务端、广播、扫描、发现邻机、连接、通知订阅与发送过程。
- library 保留简单中继转发：收到未见过的消息后转发给其他已连接或已订阅邻机，用于验证三机通信的最小可行路径。
- 对重复“发现邻机”日志做了降噪，同一设备短时间内不会连续输出相同发现日志。

本地真机测试：

- A：Honor 50 Pro / MagicOS 8
- B：Honor 30S / HarmonyOS
- C：Redmi Note 9 / MIUI 14

测试结果：

- A、B、C 三机均可启动通信。
- A 发送消息，B/C 可收到。
- B 发送消息，A/C 可收到。
- C 发送消息，A/B 可收到。

已知现象：

- 调试版仍会保留少量“发现邻机”日志，用于观察扫描状态；后续可增加调试开关或进一步降噪。
