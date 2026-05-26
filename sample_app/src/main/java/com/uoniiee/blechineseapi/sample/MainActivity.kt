package com.uoniiee.blechineseapi.sample

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
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
import com.uoniiee.blechineseapi.蓝牙通信器
import com.uoniiee.blechineseapi.蓝牙通信配置
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : Activity() {

    private companion object {
        const val 示例版本 = "v0.3.1-debug"
    }

    private val 作用域 = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var 通信器: 蓝牙通信器<String>
    private lateinit var 状态文本: TextView
    private lateinit var 邻机文本: TextView
    private lateinit var 消息列表: TextView
    private lateinit var 输入框: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        通信器 = 蓝牙通信器(
            context = this,
            配置 = 蓝牙通信配置(
                服务UUID = "f77d0a4b-2b74-4e43-a9de-6cb27a0f7a91",
                写入UUID = "1bb5f2d4-9f73-4f47-a351-9e0d2b3517cf",
                厂商编号 = 0x1234,
                应用标记 = "ble-chinese-api-sample",
            ),
            编解码器 = 文本消息编解码器(),
        )
        setContentView(创建界面())
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
        邻机文本 = TextView(this).apply { text = "邻机：0" }
        消息列表 = TextView(this).apply { text = "" }
        输入框 = EditText(this).apply {
            hint = "输入要发送的文本"
            minLines = 1
        }

        val 启动按钮 = Button(this).apply {
            text = "启动通信"
            setOnClickListener {
                作用域.launch {
                    添加日志("准备启动通信；会自动清理旧通信资源")
                    val 结果 = 通信器.启动()
                    添加日志("启动结果：$结果")
                }
            }
        }

        val 停止按钮 = Button(this).apply {
            text = "停止通信"
            setOnClickListener {
                作用域.launch {
                    通信器.停止()
                    添加日志("已停止通信")
                }
            }
        }

        val 发送按钮 = Button(this).apply {
            text = "发送"
            setOnClickListener {
                val 内容 = 输入框.text.toString()
                if (内容.isBlank()) return@setOnClickListener
                作用域.launch {
                    when (val 结果 = 通信器.发送(内容)) {
                        发送结果.已写入 -> {
                            添加日志("我：$内容")
                            输入框.text.clear()
                        }
                        is 发送结果.失败 -> 添加日志("发送失败：${结果.原因}")
                    }
                }
            }
        }

        val 顶部 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(启动按钮, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(停止按钮, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            addView(TextView(this@MainActivity).apply { text = "BLE 中文 API 示例 $示例版本" })
            addView(状态文本)
            addView(邻机文本)
            addView(顶部)
            addView(滚动区, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(发送栏)
        }
    }

    private fun 订阅通信状态() {
        作用域.launch {
            通信器.连接状态流.collect { 状态 ->
                状态文本.text = "状态：$状态"
            }
        }
        作用域.launch {
            通信器.邻机状态流.collect { 邻机列表 ->
                邻机文本.text = "邻机：${邻机列表.size}，可写：${邻机列表.count { it.可写入 }}"
            }
        }
        作用域.launch {
            通信器.收到消息流.collect { 消息 ->
                添加日志("对端：$消息")
            }
        }
        作用域.launch {
            通信器.调试事件流.collect { 事件 ->
                添加日志("调试：$事件")
            }
        }
    }

    private fun 申请权限() {
        val 权限 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
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

    private fun 添加日志(内容: String) {
        消息列表.append("$内容\n")
    }
}
