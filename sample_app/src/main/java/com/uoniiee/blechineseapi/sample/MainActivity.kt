package com.uoniiee.blechineseapi.sample

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.uoniiee.blechineseapi.发送结果
import com.uoniiee.blechineseapi.文本消息编解码器
import com.uoniiee.blechineseapi.通信角色
import com.uoniiee.blechineseapi.连接状态
import com.uoniiee.blechineseapi.蓝牙通信器
import com.uoniiee.blechineseapi.蓝牙通信配置
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private companion object {
        const val 示例版本 = "v0.6.3-debug"
        const val 最大日志行数 = 260
    }

    private val 作用域 = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val 日志时间格式 = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private lateinit var 通信器: 蓝牙通信器<String>
    private lateinit var 状态文本: TextView
    private lateinit var 角色文本: TextView
    private lateinit var 邻机文本: TextView
    private lateinit var 消息列表: TextView
    private lateinit var 输入框: EditText
    private var 当前角色 = 通信角色.自动
    private var 发送序号 = 0
    private var 上次连接状态 = ""
    private var 上次邻机摘要 = ""
    private val 日志行列表 = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        通信器 = 创建通信器()
        setContentView(创建界面())
        添加设备诊断日志()
        申请权限()
        订阅通信状态()
    }

    override fun onDestroy() {
        super.onDestroy()
        runBlocking {
            通信器.停止()
        }
        通信器.释放()
        作用域.cancel()
    }

    private fun 创建界面(): LinearLayout {
        状态文本 = TextView(this).apply { text = "状态：未启动" }
        角色文本 = TextView(this).apply { text = "模式：自动" }
        邻机文本 = TextView(this).apply { text = "连接通道：0，可写通道：0" }
        消息列表 = TextView(this).apply { text = "" }
        输入框 = EditText(this).apply {
            hint = "输入短消息，例如 A1"
            minLines = 1
        }

        val 启动按钮 = Button(this).apply {
            text = "启动通信"
            setOnClickListener {
                作用域.launch {
                    通信器.设置通信角色(当前角色)
                    添加日志("[APP] start-click role=$当前角色")
                    val 结果 = 通信器.启动()
                    添加日志("[APP] start-result=$结果")
                }
            }
        }

        val 停止按钮 = Button(this).apply {
            text = "停止通信"
            setOnClickListener {
                作用域.launch {
                    添加日志("[APP] stop-click")
                    通信器.停止()
                    添加日志("[APP] stop-done")
                }
            }
        }

        val 清空按钮 = Button(this).apply {
            text = "清空日志"
            setOnClickListener {
                日志行列表.clear()
                消息列表.text = ""
                添加设备诊断日志()
            }
        }

        val 发送按钮 = Button(this).apply {
            text = "发送"
            setOnClickListener {
                val 内容 = 输入框.text.toString()
                if (内容.isBlank()) return@setOnClickListener
                发送文本(内容, 清空输入 = true)
            }
        }

        val 短测按钮 = Button(this).apply {
            text = "短测"
            setOnClickListener {
                发送文本("T${发送序号 + 1}", 清空输入 = false)
            }
        }

        val 连发按钮 = Button(this).apply {
            text = "连发5次"
            setOnClickListener {
                作用域.launch {
                    repeat(5) {
                        执行发送文本("B${发送序号 + 1}", 清空输入 = false)
                        delay(250L)
                    }
                }
            }
        }

        val 顶部 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(启动按钮, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(停止按钮, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(清空按钮, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val 测试栏 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(短测按钮, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(连发按钮, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val 发送栏 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(输入框, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(发送按钮, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val 滚动区 = ScrollView(this).apply {
            addView(消息列表)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(this@MainActivity).apply { text = "BLE 中文 API 最小诊断 $示例版本" })
            addView(状态文本)
            addView(角色文本)
            addView(邻机文本)
            addView(顶部)
            addView(测试栏)
            addView(滚动区, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(发送栏)
        }
    }

    private fun 创建通信器(): 蓝牙通信器<String> =
        蓝牙通信器(
            context = this,
            配置 = 蓝牙通信配置(
                服务UUID = "f77d0a4b-2b74-4e43-a9de-6cb27a0f7a91",
                写入UUID = "1bb5f2d4-9f73-4f47-a351-9e0d2b3517cf",
                厂商编号 = 0x1234,
                应用标记 = "ble-chinese-api-sample",
                角色 = 当前角色,
                写入超时毫秒 = 1_500L,
            ),
            编解码器 = 文本消息编解码器(),
        )

    private fun 订阅通信状态() {
        作用域.launch {
            通信器.连接状态流.collect { 状态 ->
                val 状态名称 = 状态.显示名称()
                状态文本.text = "状态：$状态名称"
                if (状态名称 != 上次连接状态) {
                    上次连接状态 = 状态名称
                    添加日志("[STATE] $状态名称")
                }
            }
        }
        作用域.launch {
            通信器.邻机状态流.collect { 邻机列表 ->
                val 已连接数量 = 邻机列表.count { it.已连接 }
                val 可写数量 = 邻机列表.count { it.可写入 }
                val 摘要 = 邻机列表.joinToString(";") {
                    "${it.设备编号.takeLast(4)}:c=${it.已连接},w=${it.可写入}"
                }.ifBlank { "empty" }
                邻机文本.text = "连接通道：$已连接数量，可写通道：$可写数量"
                val 完整摘要 = "connected=$已连接数量 writable=$可写数量 peers=$摘要"
                if (完整摘要 != 上次邻机摘要) {
                    上次邻机摘要 = 完整摘要
                    添加日志("[PEERS] $完整摘要")
                }
            }
        }
        作用域.launch {
            通信器.收到消息流.collect { 消息 ->
                添加日志("[RECV] $消息")
            }
        }
        作用域.launch {
            通信器.调试事件流.collect { 事件 ->
                添加日志("[API] $事件")
            }
        }
    }

    private fun 发送文本(内容: String, 清空输入: Boolean) {
        作用域.launch {
            执行发送文本(内容, 清空输入)
        }
    }

    private suspend fun 执行发送文本(内容: String, 清空输入: Boolean) {
        发送序号 += 1
        val 本次序号 = 发送序号
        val 开始时间 = System.currentTimeMillis()
        添加日志("[APP#$本次序号] send-start text=$内容")
        when (val 结果 = 通信器.发送(内容)) {
            发送结果.已写入 -> {
                添加日志("[APP#$本次序号] send-result=success cost=${System.currentTimeMillis() - 开始时间}ms")
                if (清空输入) 输入框.text.clear()
            }
            is 发送结果.失败 -> {
                添加日志("[APP#$本次序号] send-result=failed cost=${System.currentTimeMillis() - 开始时间}ms reason=${结果.原因}")
            }
        }
    }

    private fun 申请权限() {
        val 权限 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val 缺失 = 权限.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (缺失.isNotEmpty()) {
            requestPermissions(缺失.toTypedArray(), 100)
        }
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled == false) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            添加日志("诊断：权限请求结果=${permissions.zip(grantResults.toTypedArray()).joinToString { "${it.first}:${it.second == PackageManager.PERMISSION_GRANTED}" }}")
            添加日志("诊断：当前缺失权限=${当前缺失权限().ifEmpty { listOf("无") }.joinToString()}")
        }
    }

    private fun 添加设备诊断日志() {
        val adapter = BluetoothAdapter.getDefaultAdapter()

        添加日志("诊断：版本=$示例版本")
        添加日志("诊断：设备=${Build.MANUFACTURER} ${Build.MODEL}，Android=${Build.VERSION.RELEASE}，SDK=${Build.VERSION.SDK_INT}")
        添加日志("诊断：BLE支持=${packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}，蓝牙开启=${adapter?.isEnabled == true}")
        添加日志("诊断：定位服务开启=${定位服务已开启()}")
        添加日志("诊断：多广播=${adapter?.isMultipleAdvertisementSupported == true}，硬件过滤=${adapter?.isOffloadedFilteringSupported == true}，批量扫描=${adapter?.isOffloadedScanBatchingSupported == true}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            添加日志("诊断：扩展广播=${adapter?.isLeExtendedAdvertisingSupported == true}，2M PHY=${adapter?.isLe2MPhySupported == true}，Coded PHY=${adapter?.isLeCodedPhySupported == true}")
        }
        添加日志("诊断：启动前缺失权限=${当前缺失权限().ifEmpty { listOf("无") }.joinToString()}")
    }

    private fun 定位服务已开启(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun 当前缺失权限(): List<String> {
        val 权限 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return 权限.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun 添加日志(内容: String) {
        日志行列表 += "${日志时间格式.format(Date())} $内容"
        while (日志行列表.size > 最大日志行数) {
            日志行列表.removeAt(0)
        }
        消息列表.text = 日志行列表.joinToString(separator = "\n", postfix = "\n")
    }

    private fun 连接状态.显示名称(): String =
        when (this) {
            连接状态.未启动 -> "未启动"
            连接状态.启动中 -> "启动中"
            连接状态.扫描广播中 -> "扫描广播中"
            is 连接状态.已连接 -> "已连接"
            is 连接状态.出错 -> "出错(原因=$原因)"
        }
}
