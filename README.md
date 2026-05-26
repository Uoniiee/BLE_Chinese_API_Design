# BLE 中文 API 设计

本仓库用于整理 Android BLE 通信库的中文 API 设计文档。

目标是把 BLE_BBS 与 BLE_Rock_Paper_Scissors 中可复用的蓝牙能力抽成一个 Android library module，让业务应用只关心连接状态、收到的消息和发送接口。

## 文档

- [BLE 中文 API 与 Android Library Module 设计](docs/ble-chinese-api-design.md)
- [最小示例 App 草案](docs/minimal-sample-app.md)
- [Library 使用说明](docs/library-usage.md)
- [Library 发布说明](docs/library-publishing.md)

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

当前示例已验证两台 Android 真机之间的最小文本双向收发。三台设备同时测试时，当前版本提供简单中继转发，用于验证三机通信的最小可行路径；完整 mesh 协议可作为后续迭代。

构建命令：

```powershell
.\gradlew.bat --no-daemon --console=plain :ble_chinese_api:assembleDebug :sample_app:assembleDebug
```

## 调试版本记录

### v0.3.1-debug

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
