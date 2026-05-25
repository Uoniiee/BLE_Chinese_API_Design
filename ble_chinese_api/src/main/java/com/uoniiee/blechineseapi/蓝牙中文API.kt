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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
)

sealed interface 连接状态 {
    data object 未启动 : 连接状态
    data object 启动中 : 连接状态
    data object 扫描广播中 : 连接状态
    data class 已连接(val 邻机数量: Int) : 连接状态
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
    private val 已发现邻机 = ConcurrentHashMap<String, 邻机记录>()

    private var Gatt服务端: BluetoothGattServer? = null
    private var 可写特征: BluetoothGattCharacteristic? = null
    private var 广播回调: AdvertiseCallback? = null
    private var 扫描回调: ScanCallback? = null
    private var 已启动 = false
    private val Gatt连接 = ConcurrentHashMap<String, BluetoothGatt>()
    private val 可写连接 = ConcurrentHashMap<String, 可写邻机连接>()
    private val 已订阅设备 = ConcurrentHashMap<String, BluetoothDevice>()
    private val 已处理消息编号 = ConcurrentHashMap<String, Long>()

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
        记录调试事件("开始启动通信")
        return runCatching {
            启动Gatt服务端()
            启动广播()
            启动扫描()
            已启动 = true
            可变连接状态流.value = 连接状态.扫描广播中
            记录调试事件("通信已启动：GATT 服务端、广播、扫描均已请求启动")
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
        runCatching { 蓝牙适配器?.bluetoothLeAdvertiser?.stopAdvertising(广播回调) }
        Gatt连接.values.forEach { gatt -> runCatching { gatt.close() } }
        runCatching { Gatt服务端?.close() }
        Gatt连接.clear()
        可写连接.clear()
        已订阅设备.clear()
        已处理消息编号.clear()
        Gatt服务端 = null
        广播回调 = null
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
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                可变连接状态流.value = 连接状态.出错("广播启动失败：$errorCode")
                记录调试事件("广播启动失败：$errorCode")
            }

            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                记录调试事件("广播启动成功")
            }
        }
        advertiser.startAdvertising(settings, data, callback)
        广播回调 = callback
    }

    @SuppressLint("MissingPermission")
    private fun 启动扫描() {
        val scanner = 蓝牙适配器?.bluetoothLeScanner ?: error("当前设备不支持 BLE 扫描")
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(服务UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                处理发现设备(result.device, result.device?.name)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { 处理发现设备(it.device, it.device?.name) }
            }

            override fun onScanFailed(errorCode: Int) {
                可变连接状态流.value = 连接状态.出错("扫描失败：$errorCode")
                记录调试事件("扫描失败：$errorCode")
            }
        }
        scanner.startScan(listOf(filter), settings, callback)
        扫描回调 = callback
        记录调试事件("扫描已启动")
    }

    @SuppressLint("MissingPermission")
    private fun 处理发现设备(device: BluetoothDevice?, 名称: String?) {
        if (device == null) return
        val 设备编号 = device.address ?: return
        已发现邻机[设备编号] = 邻机记录(设备编号, 名称, 已连接 = false, 可写入 = false)
        记录调试事件("发现邻机：${名称 ?: "未知"} $设备编号")
        发布邻机状态()
        if (Gatt连接.containsKey(设备编号).not()) {
            Gatt连接[设备编号] =
                device.connectGatt(应用上下文, false, Gatt客户端回调, BluetoothDevice.TRANSPORT_LE)
            记录调试事件("请求连接邻机：$设备编号")
        }
    }

    private val Gatt服务端回调 = object : BluetoothGattServerCallback() {
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
                处理收到载荷(value, 来源设备编号 = device?.address)
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
                val 设备编号 = device.address
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
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                记录调试事件("已连接邻机：${gatt.device?.address}")
                标记连接(gatt.device, 已连接 = true, 可写入 = false)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                记录调试事件("邻机已断开：${gatt.device?.address}")
                标记连接(gatt.device, 已连接 = false, 可写入 = false)
                val 设备编号 = gatt.device?.address
                if (设备编号 != null) {
                    可写连接.remove(设备编号)
                    Gatt连接.remove(设备编号)
                }
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val characteristic = gatt.getService(服务UUID)?.getCharacteristic(写入UUID)
            if (characteristic != null) {
                val 设备编号 = gatt.device?.address
                if (设备编号 != null) {
                    可写连接[设备编号] = 可写邻机连接(设备编号, gatt, characteristic)
                    记录调试事件("发现可写特征，准备订阅通知：$设备编号")
                    订阅通知(gatt, characteristic)
                    标记连接(gatt.device, 已连接 = true, 可写入 = true)
                }
            } else {
                记录调试事件("未找到可写特征：${gatt.device?.address}")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == 写入UUID) {
                处理收到载荷(value, 来源设备编号 = gatt.device?.address)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == 写入UUID) {
                处理收到载荷(characteristic.value, 来源设备编号 = gatt.device?.address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun 订阅通知(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val 描述符 = characteristic.getDescriptor(通知描述符UUID) ?: return
        记录调试事件("写入通知订阅描述符：${gatt.device?.address}")
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
        val 设备编号 = device?.address ?: return
        已发现邻机[设备编号] = 已发现邻机[设备编号]
            ?.copy(已连接 = 已连接, 可写入 = 可写入, 最近发现时间 = System.currentTimeMillis())
            ?: 邻机记录(设备编号, device.name, 已连接, 可写入)
        发布邻机状态()
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

    fun 释放() {
        作用域.cancel()
    }

    private fun 记录调试事件(内容: String) {
        可变调试事件流.tryEmit(内容)
    }

    private data class 邻机记录(
        val 设备编号: String,
        val 名称: String?,
        val 已连接: Boolean,
        val 可写入: Boolean,
        val 最近发现时间: Long = System.currentTimeMillis(),
    )

    private data class 可写邻机连接(
        val 设备编号: String,
        val gatt: BluetoothGatt,
        val characteristic: BluetoothGattCharacteristic,
    )
}
