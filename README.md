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

当前示例已验证两台 Android 真机之间的最小文本双向收发。三台设备同时测试时，当前版本只在已建立连接/订阅关系的设备之间收发，不提供完整 mesh 转发。

构建命令：

```powershell
.\gradlew.bat --no-daemon --console=plain :ble_chinese_api:assembleDebug :sample_app:assembleDebug
```
