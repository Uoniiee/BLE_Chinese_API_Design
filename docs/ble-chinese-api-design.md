# BLE 中文 API 与 Android Library Module 设计

## 背景

BLE_BBS 已经验证了 Android 设备之间通过 BLE 发现、连接、发送帖子和同步内容的基本路径。BLE_Rock_Paper_Scissors 进一步说明，同一套 BLE 通信能力可以服务于不同业务，例如 BBS 帖子同步和剪刀石头布对局事件。

目前 BLE 相关代码与业务逻辑仍然耦合在应用内。为了让后续业务更容易复用，建议抽出一个 Android library module，统一负责 BLE 扫描、广播、连接、收发和帧解析，业务 App 只通过稳定的中文 API 订阅状态和发送业务消息。

## 目标

- 把 BLE 通信能力沉淀为可复用 Android library module。
- 对外提供中文命名的公开 API。
- 业务 App 不直接处理 BLE GATT、扫描回调、广播回调和底层帧格式。
- 支持 BBS、剪刀石头布以及后续类似的近场多人互动应用。
- 提供最小示例 App，验证 library 的基本接入方式。

## 非目标

- 第一阶段不直接修改现有 BLE_BBS 或 BLE_Rock_Paper_Scissors 代码。
- 不在设计阶段承诺完整多设备组网策略。
- 不把 UI、数据库、帖子列表或游戏规则放进 BLE library。
- 不在 library 中绑定某一个业务协议，例如只支持帖子或只支持剪刀石头布。

## Module 建议

建议后续代码阶段新增一个独立 module：

```text
:ble_chinese_api
```

业务 App 通过 Gradle 依赖该 module：

```kotlin
implementation(project(":ble_chinese_api"))
```

示例 App 可以放在：

```text
:sample_app
```

## 分层职责

### Library 负责

- 检查 BLE 相关权限和系统能力。
- 启动与停止 BLE 广播。
- 启动与停止 BLE 扫描。
- 维护附近设备状态。
- 建立可写连接。
- 接收对端写入的数据。
- 将业务消息编码为传输帧。
- 将传输帧解析为业务消息。
- 对外发布连接状态、邻机状态和收到的消息。

### 业务 App 负责

- 决定何时启动或停止通信。
- 定义业务消息内容。
- 处理收到的业务消息。
- 维护 UI 状态。
- 维护业务数据持久化。
- 决定业务层的冲突处理和重试策略。

## 核心概念

| 概念 | 说明 |
| --- | --- |
| 蓝牙通信器 | library 对业务 App 暴露的主入口 |
| 通信配置 | service UUID、characteristic UUID、厂商编号、设备名等配置 |
| 连接状态 | 当前 BLE 通信是否未启动、启动中、扫描广播中、已连接、出错 |
| 邻机 | 被发现或已连接的附近设备 |
| 业务消息 | BBS 帖子、游戏事件等由业务 App 定义的消息 |
| 传输帧 | library 内部用于 BLE 传输的统一包装格式 |
| 消息编解码器 | 业务消息与字节数组之间的转换接口 |

## 消息模型

本设计中的 `消息` 是业务层泛型，不是某一种固定协议。library 只要求业务 App 提供 `消息编解码器<消息>`，用于在业务消息和字节数组之间转换。

```text
业务 App 消息
  -> 消息编解码器
  -> ByteArray 载荷
  -> library 传输帧
  -> BLE 写入
```

接收方向相反：

```text
BLE 写入
  -> library 传输帧
  -> ByteArray 载荷
  -> 消息编解码器
  -> 业务 App 消息
```

因此，`帖子消息` 和 `对局消息` 是两个不同业务 App 对泛型 `消息` 的具体选择：

| 场景 | 泛型 `消息` 的具体类型 | library 是否理解业务含义 |
| --- | --- | --- |
| BBS | `帖子消息` | 否，只负责传输 |
| 剪刀石头布 | `对局消息` | 否，只负责传输 |
| 最小示例 App | `文本消息` | 否，只负责传输 |

这种关系可以避免 BLE library 绑定 BBS 或游戏规则。BBS 里的排序、去重、入库由 BBS App 处理；剪刀石头布里的胜负判断、回合状态由游戏 App 处理。

## 中文公开 API 草案

### 创建通信器

```kotlin
val 通信器 = 蓝牙通信器(
    上下文 = context,
    配置 = 蓝牙通信配置(
        服务UUID = "...",
        写入UUID = "...",
        通知UUID = "...",
        厂商编号 = 0x1234,
        应用标记 = "ble-demo"
    ),
    编解码器 = Json消息编解码器()
)
```

### 启动与停止

```kotlin
通信器.启动()
通信器.停止()
```

启动后，library 内部同时处理扫描和广播。业务 App 不需要直接调用 Android BLE scanner、advertiser 或 GATT server。

### 订阅状态

```kotlin
通信器.连接状态流.collect { 状态 ->
    // 更新界面状态
}

通信器.邻机状态流.collect { 邻机列表 ->
    // 显示附近设备或调试信息
}

通信器.收到消息流.collect { 消息 ->
    // 交给业务层处理
}
```

### 发送消息

```kotlin
通信器.发送(业务消息)
```

如果当前没有可写邻机，library 可以返回失败结果，由业务 App 决定是否提示用户或稍后重试。

```kotlin
val 结果 = 通信器.发送(业务消息)

when (结果) {
    is 发送结果.成功 -> Unit
    is 发送结果.失败 -> 显示错误(结果.原因)
}
```

## 建议接口

```kotlin
interface 蓝牙通信器接口<消息> {
    val 连接状态流: StateFlow<连接状态>
    val 邻机状态流: StateFlow<List<邻机状态>>
    val 收到消息流: SharedFlow<消息>

    suspend fun 启动(): 启动结果
    suspend fun 停止()
    suspend fun 发送(消息: 消息): 发送结果
}
```

```kotlin
data class 蓝牙通信配置(
    val 服务UUID: String,
    val 写入UUID: String,
    val 通知UUID: String? = null,
    val 厂商编号: Int,
    val 应用标记: String
)
```

对外配置使用字符串形式的 UUID，便于业务 App 从配置文件或文档复制。library 内部负责校验并转换为 Android BLE API 需要的 `UUID`。

```kotlin
interface 消息编解码器<消息> {
    fun 编码(消息: 消息): ByteArray
    fun 解码(数据: ByteArray): Result<消息>
}
```

```kotlin
sealed interface 连接状态 {
    data object 未启动 : 连接状态
    data object 启动中 : 连接状态
    data object 扫描广播中 : 连接状态
    data class 已连接(val 邻机数量: Int) : 连接状态
    data class 出错(val 原因: String) : 连接状态
}
```

```kotlin
sealed interface 启动结果 {
    data object 成功 : 启动结果
    data class 失败(val 原因: String) : 启动结果
}
```

```kotlin
sealed interface 发送结果 {
    data object 已写入 : 发送结果
    data class 失败(val 原因: String) : 发送结果
}
```

`发送结果.已写入` 只表示消息已经交给当前可写连接写出，不表示对端业务层已经处理完成。是否需要业务级确认包、重试或顺序保证，由具体业务 App 决定；library 第一版不强制提供。

```kotlin
data class 邻机状态(
    val 设备编号: String,
    val 名称: String?,
    val 已连接: Boolean,
    val 可写入: Boolean,
    val 最近发现时间: Long
)
```

## 业务接入示例

### BBS

BBS 业务可以把帖子定义为业务消息：

```kotlin
data class 帖子消息(
    val 编号: String,
    val 正文: String,
    val 发帖人: String,
    val 时间戳: Long
)
```

收到消息后，BBS App 自己决定是否写入 Room、如何排序、如何去重。

在这个场景中，通信器泛型可以是：

```kotlin
蓝牙通信器接口<帖子消息>
```

### 剪刀石头布

剪刀石头布可以把选择、揭示、新局等事件定义为业务消息：

```kotlin
sealed interface 对局消息 {
    data class 已选择(val 回合编号: String, val 玩家编号: String) : 对局消息
    data class 已揭示(val 回合编号: String, val 玩家编号: String, val 手势: String) : 对局消息
    data class 新回合(val 回合编号: String) : 对局消息
}
```

library 不判断输赢，只负责把这些事件送到对端。

在这个场景中，通信器泛型可以是：

```kotlin
蓝牙通信器接口<对局消息>
```

## 帧格式建议

library 内部可以使用统一传输帧，避免每个业务 App 直接处理字节数组。

```kotlin
data class 传输帧(
    val 版本: Int,
    val 消息编号: String,
    val 类型: String,
    val 载荷: ByteArray,
    val 发送时间: Long
)
```

第一版可以先使用 JSON 编码，便于调试。后续如需提高效率，再考虑 CBOR、protobuf 或自定义二进制格式。

`传输帧.类型` 用于标识载荷属于哪类业务消息，例如 `bbs.post`、`rps.choice`、`rps.reveal`。library 可以保留该字段用于调试和路由，但不解释其业务含义。

## 错误与边界

- Android BLE 权限缺失时，library 返回明确错误，不直接弹系统权限 UI。
- 蓝牙未开启时，library 返回不可用状态。
- 没有邻机可写时，发送接口返回失败。
- 收到无法解析的数据时，丢弃该消息并输出调试日志。
- 多设备同时存在时，第一版先以可用连接为主，不承诺完整 mesh 行为。
- 后加入设备是否能收到历史消息，由业务 App 自己决定，library 只负责当前消息传输。

## 最小验收建议

设计确认后，第二阶段可以用最小示例 App 验证：

- 两台 Android 手机安装同一 APK。
- 两端能互相发现并建立可写连接。
- A 发送文本消息，B 能收到。
- B 发送文本消息，A 能收到。
- 停止通信后不再发送。
- 重新启动通信后可以再次收发。

## 与现有项目的关系

BLE_BBS 和 BLE_Rock_Paper_Scissors 可作为两个业务案例：

- BLE_BBS 验证“帖子同步”场景。
- BLE_Rock_Paper_Scissors 验证“游戏事件同步”场景。

library 不应依赖两者的 UI、数据库或业务模型；反过来，两者可以逐步改为依赖 library。
