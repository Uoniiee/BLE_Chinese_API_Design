package com.uoniiee.blechineseapi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class 蓝牙通信配置(
    val 服务UUID: String,
    val 写入UUID: String,
    val 通知UUID: String? = null,
    val 厂商编号: Int,
    val 应用标记: String,
    val 最大载荷字节数: Int = 180,
    val 启用中继转发: Boolean = true,
    val 角色: 通信角色 = 通信角色.自动,
)

enum class 通信角色 {
    自动,
    中继,
    普通,
    只扫描,
    只广播,
}

sealed interface 连接状态 {
    data object 未启动 : 连接状态
    data object 启动中 : 连接状态
    data object 扫描广播中 : 连接状态
    data class 已连接(val 连接通道数量: Int) : 连接状态
    data class 出错(val 原因: String) : 连接状态
}

data class 邻机状态(
    val 设备编号: String,
    val 名称: String?,
    val 已连接: Boolean,
    val 可写入: Boolean,
    val 最近发现时间: Long,
)

sealed interface 启动结果 {
    data object 成功 : 启动结果
    data class 失败(val 原因: String) : 启动结果
}

sealed interface 发送结果 {
    data object 已写入 : 发送结果
    data class 失败(val 原因: String) : 发送结果
}

interface 消息编解码器<消息> {
    fun 编码(消息: 消息): ByteArray
    fun 解码(数据: ByteArray): Result<消息>
}

class 文本消息编解码器 : 消息编解码器<String> {
    override fun 编码(消息: String): ByteArray = 消息.toByteArray(StandardCharsets.UTF_8)

    override fun 解码(数据: ByteArray): Result<String> =
        runCatching { String(数据, StandardCharsets.UTF_8) }
}

interface 蓝牙通信器接口<消息> {
    val 连接状态流: StateFlow<连接状态>
    val 邻机状态流: StateFlow<List<邻机状态>>
    val 收到消息流: SharedFlow<消息>
    val 调试事件流: SharedFlow<String>

    suspend fun 启动(): 启动结果
    suspend fun 停止()
    suspend fun 发送(消息: 消息): 发送结果
}

class 蓝牙通信器<消息>(
    context: Context,
    private val 配置: 蓝牙通信配置,
    private val 编解码器: 消息编解码器<消息>,
) : 蓝牙通信器接口<消息> {

    private val 应用上下文 = context.applicationContext
    private val 作用域 = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val 蓝牙管理器 =
        应用上下文.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val 蓝牙适配器: BluetoothAdapter? = 蓝牙管理器.adapter
    private val 服务UUID by lazy { UUID.fromString(配置.服务UUID) }
    private val 写入UUID by lazy { UUID.fromString(配置.写入UUID) }
    private val 通知描述符UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val 本机稳定编号 by lazy { 读取或生成本机稳定编号() }
    private val 已发现邻机 = ConcurrentHashMap<String, 邻机记录>()
    private val 地址到稳定编号 = ConcurrentHashMap<String, String>()

    private var Gatt服务端: BluetoothGattServer? = null
    private var 可写特征: BluetoothGattCharacteristic? = null
    private val 广播回调列表 = mutableListOf<AdvertiseCallback>()
    private var 扫描回调: ScanCallback? = null
    private var 已启动 = false
    private var 当前通信角色 = 配置.角色
    private val Gatt连接 = ConcurrentHashMap<String, BluetoothGatt>()
    private val 可写连接 = ConcurrentHashMap<String, 可写邻机连接>()
    private val 已订阅设备 = ConcurrentHashMap<String, BluetoothDevice>()
    private val 已处理消息编号 = ConcurrentHashMap<String, Long>()
    private val 最近发现日志时间 = ConcurrentHashMap<String, Long>()
    private var 扫描总数 = 0
    private var 匹配总数 = 0
    private var 最近扫描诊断时间 = 0L
    private var 扫描诊断任务: Job? = null

    private val 可变连接状态流 = MutableStateFlow<连接状态>(连接状态.未启动)
    private val 可变邻机状态流 = MutableStateFlow<List<邻机状态>>(emptyList())
    private val 可变收到消息流 = MutableSharedFlow<消息>(
        replay = 0,
        extraBufferCapacity = 32,
    )
    private val 可变调试事件流 = MutableSharedFlow<String>(
        replay = 8,
        extraBufferCapacity = 64,
    )

    override val 连接状态流: StateFlow<连接状态> = 可变连接状态流
    override val 邻机状态流: StateFlow<List<邻机状态>> = 可变邻机状态流
    override val 收到消息流: SharedFlow<消息> = 可变收到消息流
    override val 调试事件流: SharedFlow<String> = 可变调试事件流

    fun 设置通信角色(角色: 通信角色) {
        当前通信角色 = 角色
        记录调试事件("通信角色已设置：$角色")
    }

    override suspend fun 启动(): 启动结果 {
        if (已启动) {
            记录调试事件("已在运行，先清理旧通信资源")
            停止()
        }

        val 能力错误 = 检查启动条件()
        if (能力错误 != null) {
            可变连接状态流.value = 连接状态.出错(能力错误)
            记录调试事件("启动前检查失败：$能力错误")
            return 启动结果.失败(能力错误)
        }

        可变连接状态流.value = 连接状态.启动中
        记录调试事件("开始启动通信，角色=$当前通信角色，本机ID=$本机稳定编号")
        return runCatching {
            when (当前通信角色) {
                通信角色.只扫描 -> {
                    记录调试事件("诊断模式：只扫描；不启动 GATT 服务端和广播，会主动连接可广播邻机")
                    启动扫描()
                }
                通信角色.只广播 -> {
                    记录调试事件("诊断模式：只广播；不启动扫描")
                    启动Gatt服务端()
                    启动广播()
                }
                通信角色.自动,
                通信角色.中继,
                通信角色.普通,
                -> {
                    启动Gatt服务端()
                    启动广播()
                    启动扫描()
                }
            }
            已启动 = true
            可变连接状态流.value = 连接状态.扫描广播中
            记录调试事件("通信已启动：角色=$当前通信角色")
            启动结果.成功
        }.getOrElse { 错误 ->
            val 原因 = 错误.message ?: 错误::class.java.simpleName
            可变连接状态流.value = 连接状态.出错(原因)
            记录调试事件("启动失败：$原因")
            停止()
            启动结果.失败(原因)
        }
    }

    override suspend fun 停止() {
        runCatching { 蓝牙适配器?.bluetoothLeScanner?.stopScan(扫描回调) }
        广播回调列表.forEach { callback ->
            runCatching { 蓝牙适配器?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        }
        扫描诊断任务?.cancel()
        Gatt连接.values.forEach { gatt -> runCatching { gatt.close() } }
        runCatching { Gatt服务端?.close() }
        Gatt连接.clear()
        可写连接.clear()
        已订阅设备.clear()
        已处理消息编号.clear()
        最近发现日志时间.clear()
        地址到稳定编号.clear()
        扫描总数 = 0
        匹配总数 = 0
        最近扫描诊断时间 = 0L
        扫描诊断任务 = null
        Gatt服务端 = null
        广播回调列表.clear()
        扫描回调 = null
        已启动 = false
        已发现邻机.clear()
        可变邻机状态流.value = emptyList()
        可变连接状态流.value = 连接状态.未启动
        记录调试事件("通信已停止")
    }

    override suspend fun 发送(消息: 消息): 发送结果 {
        val 载荷 = 编解码器.编码(消息)
        记录调试事件("准备发送：${载荷.size} 字节")
        if (载荷.size > 配置.最大载荷字节数) {
            return 发送结果.失败("消息过大：${载荷.size} 字节，当前上限 ${配置.最大载荷字节数} 字节")
        }
        已处理消息编号[载荷.消息编号()] = System.currentTimeMillis()
        清理旧消息编号()

        val 连接列表 = 可写连接.values.toList()
        val 失败原因 = mutableListOf<String>()
        var 已成功发送 = false

        for (连接 in 连接列表) {
            val 结果 = 写入到邻机(连接, 载荷, 排除设备编号 = null)
            if (结果 == 发送结果.已写入) 已成功发送 = true
            if (结果 is 发送结果.失败) 失败原因 += "${连接.设备编号}:${结果.原因}"
        }

        val 通知结果 = 通知已订阅邻机(载荷, 排除设备编号 = null)
        if (通知结果 == 发送结果.已写入) 已成功发送 = true
        if (通知结果 is 发送结果.失败) 失败原因 += "通知:${通知结果.原因}"

        return if (已成功发送) {
            记录调试事件("发送请求已写出")
            发送结果.已写入
        } else {
            val 原因 = 失败原因.joinToString("; ").ifBlank { "当前没有可写连接或通知订阅" }
            记录调试事件("发送失败：$原因")
            发送结果.失败(原因)
        }
    }

    private fun 写入到邻机(
        连接: 可写邻机连接,
        载荷: ByteArray,
        排除设备编号: String?,
    ): 发送结果 =
        runCatching {
            if (连接.设备编号 == 排除设备编号) return@runCatching 发送结果.失败("跳过来源设备")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val 状态 = 连接.gatt.writeCharacteristic(
                    连接.characteristic,
                    载荷,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                if (状态 == BluetoothGatt.GATT_SUCCESS) 发送结果.已写入 else 发送结果.失败("写入失败：$状态")
            } else {
                @Suppress("DEPRECATION")
                连接.characteristic.value = 载荷
                @Suppress("DEPRECATION")
                if (连接.gatt.writeCharacteristic(连接.characteristic)) {
                    发送结果.已写入
                } else {
                    发送结果.失败("写入请求未被系统接受")
                }
            }
        }.getOrElse { 发送结果.失败(it.message ?: it::class.java.simpleName) }

    @SuppressLint("MissingPermission")
    private fun 通知已订阅邻机(载荷: ByteArray, 排除设备编号: String?): 发送结果 {
        val 服务端 = Gatt服务端 ?: return 发送结果.失败("GATT 服务端未启动")
        val 特征 = 可写特征 ?: return 发送结果.失败("通知特征不存在")
        val 设备列表 = 已订阅设备
            .filterKeys { it != 排除设备编号 }
            .values
            .toList()
        if (设备列表.isEmpty()) return 发送结果.失败("没有已订阅通知的邻机")

        var 成功数量 = 0
        for (设备 in 设备列表) {
            val 成功 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                服务端.notifyCharacteristicChanged(设备, 特征, false, 载荷) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                特征.value = 载荷
                @Suppress("DEPRECATION")
                服务端.notifyCharacteristicChanged(设备, 特征, false)
            }
            if (成功) 成功数量 += 1
        }

        return if (成功数量 > 0) 发送结果.已写入 else 发送结果.失败("通知请求未被系统接受")
    }

    @SuppressLint("MissingPermission")
    private fun 启动Gatt服务端() {
        val 服务端 = 蓝牙管理器.openGattServer(应用上下文, Gatt服务端回调)
            ?: error("无法创建 GATT 服务端")
        val 服务 = BluetoothGattService(服务UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val 特征 = BluetoothGattCharacteristic(
            写入UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val 通知描述符 = BluetoothGattDescriptor(
            通知描述符UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        特征.addDescriptor(通知描述符)
        服务.addCharacteristic(特征)
        服务端.addService(服务)
        Gatt服务端 = 服务端
        可写特征 = 特征
        记录调试事件("GATT 服务端已创建")
    }

    @SuppressLint("MissingPermission")
    private fun 启动广播() {
        val advertiser = 蓝牙适配器?.bluetoothLeAdvertiser ?: error("当前设备不支持 BLE 广播")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(服务UUID))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(配置.厂商编号, 广播载荷())
            .setIncludeDeviceName(false)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                可变连接状态流.value = 连接状态.出错("广播启动失败：$errorCode")
                记录调试事件("服务UUID广播启动失败：$errorCode")
            }

            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                记录调试事件("服务UUID广播启动成功")
            }
        }
        advertiser.startAdvertising(settings, data, scanResponse, callback)
        广播回调列表 += callback
    }

    @SuppressLint("MissingPermission")
    private fun 启动扫描() {
        val scanner = 蓝牙适配器?.bluetoothLeScanner ?: error("当前设备不支持 BLE 扫描")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (应该输出扫描诊断()) 记录扫描诊断(result)
                val 信息 = 解析广播邻机信息(result) ?: return
                匹配总数 += 1
                处理发现设备(result.device, result.device?.name, 信息)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    if (应该输出扫描诊断()) 记录扫描诊断(result)
                    val 信息 = 解析广播邻机信息(result) ?: return@forEach
                    匹配总数 += 1
                    处理发现设备(result.device, result.device?.name, 信息)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                可变连接状态流.value = 连接状态.出错("扫描失败：$errorCode")
                记录调试事件("扫描失败：$errorCode")
            }
        }
        scanner.startScan(null, settings, callback)
        扫描回调 = callback
        记录调试事件("全量扫描已启动：LOW_LATENCY/ALL_MATCHES/AGGRESSIVE")
        启动扫描诊断心跳()
    }

    private fun 启动扫描诊断心跳() {
        扫描诊断任务?.cancel()
        扫描诊断任务 = 作用域.launch {
            while (true) {
                delay(5_000L)
                if (应该输出扫描诊断()) {
                    记录调试事件("扫描心跳：总数=$扫描总数，匹配=$匹配总数")
                }
            }
        }
    }

    private fun 应该输出扫描诊断(): Boolean =
        当前通信角色 == 通信角色.只扫描 || 可写连接.isEmpty() && 已订阅设备.isEmpty()

    private fun 记录扫描诊断(result: ScanResult) {
        扫描总数 += 1
        val 现在 = System.currentTimeMillis()
        if (现在 - 最近扫描诊断时间 < 5_000) return
        最近扫描诊断时间 = 现在
        val 扫描记录 = result.scanRecord
        val 含服务UUID = 扫描记录?.serviceUuids?.any { it.uuid == 服务UUID } == true
        val 含厂商数据 = 扫描记录?.getManufacturerSpecificData(配置.厂商编号) != null
        val 地址 = result.device?.address ?: "未知"
        记录调试事件(
            "扫描诊断：总数=$扫描总数，匹配=$匹配总数，最近=$地址，服务UUID=$含服务UUID，厂商数据=$含厂商数据",
        )
    }

    private fun 广播载荷(): ByteArray =
        "BC${角色码()}$本机稳定编号".toByteArray(StandardCharsets.US_ASCII)

    private fun 角色码(): Char =
        when (当前通信角色) {
            通信角色.自动 -> '0'
            通信角色.中继 -> '1'
            通信角色.普通 -> '2'
            通信角色.只广播 -> '3'
            通信角色.只扫描 -> '4'
        }

    private fun 解析广播邻机信息(result: ScanResult): 广播邻机信息? {
        val 扫描记录 = result.scanRecord ?: return null
        val 厂商数据 = 扫描记录.getManufacturerSpecificData(配置.厂商编号)
        if (厂商数据 != null) {
            val 文本 = runCatching { String(厂商数据, StandardCharsets.US_ASCII) }.getOrNull() ?: return null
            if (文本.startsWith("BC") && 文本.length >= 11) {
                val 稳定编号 = 文本.substring(3, 11)
                if (稳定编号 == 本机稳定编号) return null
                val 角色 = when (文本[2]) {
                    '0' -> 通信角色.自动
                    '1' -> 通信角色.中继
                    '2' -> 通信角色.普通
                    '3' -> 通信角色.只广播
                    '4' -> 通信角色.只扫描
                    else -> null
                }
                return 广播邻机信息(稳定编号, 角色, "身份广播")
            }
        }

        val 含服务UUID = 扫描记录.serviceUuids?.any { it.uuid == 服务UUID } == true
        if (含服务UUID) {
            val 地址 = result.device?.address ?: return null
            return 广播邻机信息("addr-${地址.replace(":", "").takeLast(8)}", 通信角色.自动, "服务UUID广播")
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun 处理发现设备(device: BluetoothDevice?, 名称: String?, 信息: 广播邻机信息) {
        if (device == null) return
        val 地址 = device.address ?: return
        val 设备编号 = 信息.稳定编号
        地址到稳定编号[地址] = 设备编号
        val 已有记录 = 已发现邻机[设备编号]
        已发现邻机[设备编号] = 已有记录
            ?.copy(
                名称 = 名称 ?: 已有记录.名称,
                通信角色 = 信息.角色 ?: 已有记录.通信角色,
                蓝牙地址 = 地址,
                最近发现时间 = System.currentTimeMillis(),
            )
            ?: 邻机记录(设备编号, 名称, 信息.角色, 地址, 已连接 = false, 可写入 = false)
        if (已有记录?.已连接 != true && 已有记录?.可写入 != true) {
            记录发现邻机事件(设备编号, 名称, 信息.角色, 地址, 信息.来源)
        }
        发布邻机状态()
        if (Gatt连接.containsKey(设备编号).not()) {
            if (!应该连接对端(信息)) {
                记录跳过连接事件(设备编号, 信息.角色)
                return
            }
            Gatt连接[设备编号] =
                device.connectGatt(应用上下文, false, Gatt客户端回调, BluetoothDevice.TRANSPORT_LE)
            记录调试事件("请求连接邻机：$设备编号，地址=$地址，角色=${信息.角色 ?: "未知"}，来源=${信息.来源}")
        }
    }

    private fun 应该连接对端(信息: 广播邻机信息): Boolean =
        when (当前通信角色) {
            通信角色.自动 -> 信息.角色 != 通信角色.只扫描
            通信角色.中继 -> true
            通信角色.只扫描 -> 信息.角色 != 通信角色.只扫描
            通信角色.只广播 -> false
            通信角色.普通 -> when (信息.角色) {
                通信角色.中继 -> false
                通信角色.只广播 -> true
                通信角色.只扫描 -> false
                通信角色.普通 -> 本机稳定编号 > 信息.稳定编号
                通信角色.自动 -> 本机稳定编号 > 信息.稳定编号
                null -> 本机稳定编号 > 信息.稳定编号
            }
        }

    private val Gatt服务端回调 = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val 设备编号 = 设备编号(device) ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                记录调试事件("服务端侧邻机已连接(status=$status)：$设备编号")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                已订阅设备.remove(设备编号)
                记录调试事件("服务端侧邻机已断开(status=$status)：$设备编号")
                标记连接(device, 已连接 = false, 可写入 = false)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic?.uuid == 写入UUID && value != null) {
                处理收到载荷(value, 来源设备编号 = 设备编号(device))
            }
            if (responseNeeded) {
                Gatt服务端?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor?.uuid == 通知描述符UUID && device != null) {
                val 设备编号 = 设备编号(device) ?: return
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    已订阅设备[设备编号] = device
                    记录调试事件("邻机已订阅通知：$设备编号")
                    标记连接(device, 已连接 = true, 可写入 = true)
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    已订阅设备.remove(设备编号)
                    记录调试事件("邻机取消通知订阅：$设备编号")
                    标记连接(device, 已连接 = true, 可写入 = false)
                }
            }
            if (responseNeeded) {
                Gatt服务端?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    private val Gatt客户端回调 = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val 设备编号 = 设备编号(gatt.device)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                记录调试事件("已连接邻机(status=$status)：$设备编号")
                标记连接(gatt.device, 已连接 = true, 可写入 = false)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                记录调试事件("邻机已断开(status=$status)：$设备编号")
                标记连接(gatt.device, 已连接 = false, 可写入 = false)
                if (设备编号 != null) {
                    可写连接.remove(设备编号)
                    Gatt连接.remove(设备编号)
                }
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                记录调试事件("发现服务失败(status=$status)：${设备编号(gatt.device)}")
                return
            }
            val characteristic = gatt.getService(服务UUID)?.getCharacteristic(写入UUID)
            if (characteristic != null) {
                val 设备编号 = 设备编号(gatt.device)
                if (设备编号 != null) {
                    记录调试事件("发现可写特征，准备订阅通知：$设备编号")
                    订阅通知(gatt, characteristic)
                    标记连接(gatt.device, 已连接 = true, 可写入 = false)
                }
            } else {
                记录调试事件("未找到可写特征：${设备编号(gatt.device)}")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == 写入UUID) {
                处理收到载荷(value, 来源设备编号 = 设备编号(gatt.device))
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == 写入UUID) {
                处理收到载荷(characteristic.value, 来源设备编号 = 设备编号(gatt.device))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != 通知描述符UUID) return
            val 设备编号 = 设备编号(gatt.device) ?: return
            val characteristic = descriptor.characteristic
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == 写入UUID) {
                可写连接[设备编号] = 可写邻机连接(设备编号, gatt, characteristic)
                记录调试事件("通知订阅写入成功(status=$status)：$设备编号")
                标记连接(gatt.device, 已连接 = true, 可写入 = true)
            } else {
                可写连接.remove(设备编号)
                记录调试事件("通知订阅写入失败(status=$status)：$设备编号")
                标记连接(gatt.device, 已连接 = true, 可写入 = false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun 订阅通知(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val 描述符 = characteristic.getDescriptor(通知描述符UUID) ?: return
        记录调试事件("写入通知订阅描述符：${设备编号(gatt.device)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(描述符, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            描述符.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(描述符)
        }
    }

    private fun 处理收到载荷(载荷: ByteArray, 来源设备编号: String?) {
        val 消息编号 = 载荷.消息编号()
        if (已处理消息编号.putIfAbsent(消息编号, System.currentTimeMillis()) != null) return
        清理旧消息编号()

        编解码器.解码(载荷).onSuccess { 消息 ->
            作用域.launch { 可变收到消息流.emit(消息) }
        }
        if (配置.启用中继转发) {
            中继转发(载荷, 来源设备编号)
        }
    }

    private fun 中继转发(载荷: ByteArray, 来源设备编号: String?) {
        可写连接.values.forEach { 连接 ->
            写入到邻机(连接, 载荷, 排除设备编号 = 来源设备编号)
        }
        通知已订阅邻机(载荷, 排除设备编号 = 来源设备编号)
    }

    private fun ByteArray.消息编号(): String {
        val 摘要 = MessageDigest.getInstance("SHA-256").digest(this)
        return 摘要.joinToString("") { "%02x".format(it) }
    }

    private fun 清理旧消息编号() {
        if (已处理消息编号.size < 512) return
        val 截止时间 = System.currentTimeMillis() - 5 * 60 * 1000
        已处理消息编号.entries.removeIf { it.value < 截止时间 }
    }

    private fun 标记连接(device: BluetoothDevice?, 已连接: Boolean, 可写入: Boolean) {
        val 设备编号 = 设备编号(device) ?: return
        已发现邻机[设备编号] = 已发现邻机[设备编号]
            ?.copy(已连接 = 已连接, 可写入 = 可写入, 最近发现时间 = System.currentTimeMillis())
            ?: 邻机记录(设备编号, device?.name, null, device?.address, 已连接, 可写入)
        发布邻机状态()
    }

    private fun 设备编号(device: BluetoothDevice?): String? {
        val 地址 = device?.address ?: return null
        return 地址到稳定编号[地址] ?: 地址
    }

    private fun 发布邻机状态() {
        val 列表 = 已发现邻机.values.map { 记录 ->
            邻机状态(
                设备编号 = 记录.设备编号,
                名称 = 记录.名称,
                已连接 = 记录.已连接,
                可写入 = 记录.可写入,
                最近发现时间 = 记录.最近发现时间,
            )
        }
        可变邻机状态流.value = 列表
        val 可写数量 = 列表.count { it.可写入 }
        if (可写数量 > 0) {
            可变连接状态流.value = 连接状态.已连接(可写数量)
        }
    }

    private fun 检查启动条件(): String? {
        val adapter = 蓝牙适配器 ?: return "当前设备没有蓝牙适配器"
        if (!adapter.isEnabled) return "蓝牙未开启"
        if (应用上下文.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE).not()) {
            return "当前设备不支持 BLE"
        }
        if (adapter.isMultipleAdvertisementSupported.not()) {
            return "当前设备不支持 BLE 广播"
        }
        val 权限 = 缺失权限()
        if (权限.isNotEmpty()) return "缺少权限：${权限.joinToString()}"
        runCatching { UUID.fromString(配置.服务UUID) }.getOrElse { return "服务UUID格式错误" }
        runCatching { UUID.fromString(配置.写入UUID) }.getOrElse { return "写入UUID格式错误" }
        return null
    }

    private fun 缺失权限(): List<String> {
        val 权限 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return 权限.filter {
            应用上下文.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun 读取或生成本机稳定编号(): String {
        val 存储 = 应用上下文.getSharedPreferences("ble_chinese_api", Context.MODE_PRIVATE)
        val 已有 = 存储.getString("stable_device_id", null)
        if (!已有.isNullOrBlank()) return 已有
        val 新编号 = UUID.randomUUID().toString().replace("-", "").take(8)
        存储.edit().putString("stable_device_id", 新编号).apply()
        return 新编号
    }

    fun 释放() {
        作用域.cancel()
    }

    private fun 记录调试事件(内容: String) {
        可变调试事件流.tryEmit(内容)
    }

    private fun 记录发现邻机事件(设备编号: String, 名称: String?, 对端角色: 通信角色?, 地址: String, 来源: String) {
        val 现在 = System.currentTimeMillis()
        val 上次 = 最近发现日志时间[设备编号] ?: 0L
        if (现在 - 上次 < 10_000L) return
        最近发现日志时间[设备编号] = 现在
        记录调试事件("发现邻机：${名称 ?: "未知"} ID=$设备编号，地址=$地址，角色=${对端角色 ?: "未知"}，来源=$来源")
    }

    private fun 记录跳过连接事件(设备编号: String, 对端角色: 通信角色?) {
        val 键 = "跳过-$设备编号"
        val 现在 = System.currentTimeMillis()
        val 上次 = 最近发现日志时间[键] ?: 0L
        if (现在 - 上次 < 30_000L) return
        最近发现日志时间[键] = 现在
        记录调试事件("跳过连接邻机：$设备编号，当前角色=$当前通信角色，对端角色=${对端角色 ?: "未知"}")
    }

    private data class 邻机记录(
        val 设备编号: String,
        val 名称: String?,
        val 通信角色: 通信角色?,
        val 蓝牙地址: String?,
        val 已连接: Boolean,
        val 可写入: Boolean,
        val 最近发现时间: Long = System.currentTimeMillis(),
    )

    private data class 广播邻机信息(
        val 稳定编号: String,
        val 角色: 通信角色?,
        val 来源: String,
    )

    private data class 可写邻机连接(
        val 设备编号: String,
        val gatt: BluetoothGatt,
        val characteristic: BluetoothGattCharacteristic,
    )
}
