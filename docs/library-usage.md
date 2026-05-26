# Library 使用说明

## 模块

当前工程包含两个 Android module：

- `:ble_chinese_api`：BLE 中文 API library。
- `:sample_app`：最小文本收发示例。

业务 App 接入时依赖 library：

```kotlin
dependencies {
    implementation(project(":ble_chinese_api"))
}
```

## 权限

Android 12 及以上需要：

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

Android 11 及以下需要：

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```

业务 App 负责申请运行时权限。library 在 `启动()` 时会检查缺失权限并返回失败原因。

## 最小接入

```kotlin
val 通信器 = 蓝牙通信器(
    context = applicationContext,
    配置 = 蓝牙通信配置(
        服务UUID = "9a31b0a1-7d44-4b1a-8f23-1e9a2e0d6c01",
        写入UUID = "0c8b7f0b-7e8a-4d9f-9d25-6c3a8b28d4f2",
        厂商编号 = 0x1234,
        应用标记 = "my-ble-app",
    ),
    编解码器 = 文本消息编解码器(),
)
```

启动通信：

```kotlin
val 结果 = 通信器.启动()
```

订阅状态与消息：

```kotlin
通信器.连接状态流.collect { 状态 ->
    // 更新界面状态
}

通信器.邻机状态流.collect { 邻机列表 ->
    // 显示附近设备数量、是否可写等
}

通信器.收到消息流.collect { 消息 ->
    // 处理对端消息
}
```

发送消息：

```kotlin
when (val 结果 = 通信器.发送("你好")) {
    发送结果.已写入 -> Unit
    is 发送结果.失败 -> println(结果.原因)
}
```

停止通信：

```kotlin
通信器.停止()
```

Activity 或 ViewModel 销毁时释放内部协程：

```kotlin
通信器.释放()
```

## 自定义业务消息

业务 App 可以定义自己的消息类型，并实现 `消息编解码器`。

```kotlin
data class 帖子消息(
    val 编号: String,
    val 正文: String,
    val 发帖人: String,
    val 时间戳: Long,
)
```

```kotlin
class 帖子消息编解码器 : 消息编解码器<帖子消息> {
    override fun 编码(消息: 帖子消息): ByteArray {
        TODO("可用 JSON、CBOR 或自定义二进制格式")
    }

    override fun 解码(数据: ByteArray): Result<帖子消息> =
        runCatching {
            TODO("解析为帖子消息")
        }
}
```

此时通信器类型为：

```kotlin
蓝牙通信器<帖子消息>
```

剪刀石头布也类似，可以把 `对局消息` 作为泛型消息类型。

## 当前限制

- 当前实现聚焦最小示例，不做历史消息补发。
- 当前实现只报告“已写入”，不代表业务级送达确认。
- 当前实现支持 GATT write 与 notification 两条发送路径，以便一条 BLE 连接也能完成基本双向文本收发。
- 当前默认启用简单中继转发：收到未见过的消息后，会转发给其他已连接/已订阅邻机，用于验证三机通信的最小可行路径。
- 当前实现设置了单次载荷上限，尚未实现完整分片与重组。
- 多设备 mesh、后台长期扫描、加密鉴权可作为后续迭代。

## 示例 App 测试说明

`sample_app` 使用一组独立 UUID，避免与 BLE_BBS 或其他历史测试服务缓存混在一起：

```text
服务UUID: f77d0a4b-2b74-4e43-a9de-6cb27a0f7a91
写入UUID: 1bb5f2d4-9f73-4f47-a351-9e0d2b3517cf
```

`v0.3.1-debug` 起，点击“启动通信”会先清理旧通信资源，再重新启动 GATT 服务端、广播与扫描。通常不需要测试前手动点击“停止通信”；如果系统蓝牙栈处于异常状态，可杀掉 App 或重启蓝牙后再测试。

最小通过标准：

- 两台设备均显示启动成功。
- 至少一台设备显示可写邻机数量大于 0。
- A 发送文本，B 显示“对端：文本”。
- B 发送文本，A 显示“对端：文本”。

## 当前真机测试记录

已在两台 Android 真机上验证最小文本双向收发：

- Honor 30S 与 Redmi Note 9 均可启动通信。
- 一侧显示可写连接时，另一侧可通过 notification 回传。
- Honor 30S -> Redmi Note 9 文本收发成功。
- Redmi Note 9 -> Honor 30S 文本收发成功。

三台设备同时测试时，当前版本通过简单中继转发验证最小互通：

- 中间设备同时连接到另外两台时，会把收到的未见过消息继续转发。
- 消息按载荷 SHA-256 去重，避免在小网络内反复转发。
- 这仍不是完整 mesh 协议；后续如需产品化三机互联，需要继续设计房间成员、跳数、消息编号、确认与过期策略。

### v0.3.1-debug 三机复测

测试设备：

- A：Honor 50 Pro / MagicOS 8
- B：Honor 30S / HarmonyOS
- C：Redmi Note 9 / MIUI 14

测试结果：

- A、B、C 三机均可启动通信。
- A 发送消息，B/C 可收到。
- B 发送消息，A/C 可收到。
- C 发送消息，A/B 可收到。

观察到的现象：

- 三机通信依赖至少一台设备同时连接到另外两台，再通过简单中继转发消息。
- 调试界面仍会周期性显示少量“发现邻机”日志，这是为了观察扫描状态保留的调试信息。
- 当前版本验证的是最小三机互通路径，不包含历史消息同步、成员列表、业务级送达确认或完整 mesh 路由。
