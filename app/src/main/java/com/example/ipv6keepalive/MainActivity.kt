package com.example.ipv6keepalive

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_TARGET = "2001:4860:4860::8888"
        const val DEFAULT_INTERVAL = 30
        const val DEFAULT_GATEWAY = "fe80::a6a9:30ff:fecd:28bc"
        const val DEFAULT_WIFI_RENEW_INTERVAL_MIN = 120
    }

    private lateinit var switchService: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var statusIndicator: View
    private lateinit var etTarget: TextInputEditText
    private lateinit var etInterval: TextInputEditText
    private lateinit var etGateway: TextInputEditText
    private lateinit var switchWifiRenew: SwitchMaterial
    private lateinit var etWifiRenewInterval: TextInputEditText
    private lateinit var cardStats: MaterialCardView
    private lateinit var tvStats: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScrollView: ScrollView

    private var currentTarget: String = DEFAULT_TARGET
    private var currentInterval: Int = DEFAULT_INTERVAL
    private var currentGateway: String = DEFAULT_GATEWAY
    private var currentWifiRenewEnabled: Boolean = false
    private var currentWifiRenewIntervalMin: Int = DEFAULT_WIFI_RENEW_INTERVAL_MIN
    private val logBuilder = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        loadSettings()

        switchService = findViewById(R.id.switchService)
        tvStatus = findViewById(R.id.tvStatus)
        statusIndicator = findViewById(R.id.statusIndicator)
        etTarget = findViewById(R.id.etTarget)
        etInterval = findViewById(R.id.etInterval)
        etGateway = findViewById(R.id.etGateway)
        switchWifiRenew = findViewById(R.id.switchWifiRenew)
        etWifiRenewInterval = findViewById(R.id.etWifiRenewInterval)
        cardStats = findViewById(R.id.cardStats)
        tvStats = findViewById(R.id.tvStats)
        tvLog = findViewById(R.id.tvLog)
        logScrollView = tvLog.parent as ScrollView

        etTarget.setText(currentTarget)
        etInterval.setText(currentInterval.toString())
        etGateway.setText(currentGateway)
        switchWifiRenew.isChecked = currentWifiRenewEnabled
        etWifiRenewInterval.setText(currentWifiRenewIntervalMin.toString())
        etWifiRenewInterval.isEnabled = currentWifiRenewEnabled

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startKeepAlive()
            } else {
                stopKeepAlive()
            }
        }

        switchWifiRenew.setOnCheckedChangeListener { _, isChecked ->
            etWifiRenewInterval.isEnabled = isChecked && !KeepAliveService.isRunning
        }

        requestIgnoreBatteryOptimizations()

        // 直接用静态标志检测
        if (KeepAliveService.isRunning) {
            switchService.isChecked = true
            updateStatusUI(true)
            appendLog("检测到服务运行中")
            startStatsPolling()
        }
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        currentTarget = prefs.getString("target", DEFAULT_TARGET) ?: DEFAULT_TARGET
        currentInterval = prefs.getInt("interval", DEFAULT_INTERVAL)
        currentGateway = prefs.getString("gateway", DEFAULT_GATEWAY) ?: DEFAULT_GATEWAY
        currentWifiRenewEnabled = prefs.getBoolean("wifi_renew_enabled", false)
        currentWifiRenewIntervalMin = prefs.getInt("wifi_renew_interval_min", DEFAULT_WIFI_RENEW_INTERVAL_MIN)
    }

    private fun saveSettings() {
        val target = etTarget.text?.toString()?.trim() ?: DEFAULT_TARGET
        val intervalStr = etInterval.text?.toString()?.trim()
        val interval = intervalStr?.toIntOrNull() ?: DEFAULT_INTERVAL
        val gateway = etGateway.text?.toString()?.trim() ?: DEFAULT_GATEWAY
        val wifiRenewEnabled = switchWifiRenew.isChecked
        val wifiRenewInterval = etWifiRenewInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: DEFAULT_WIFI_RENEW_INTERVAL_MIN

        if (target.isNotEmpty() && interval >= 5) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit()
                .putString("target", target)
                .putInt("interval", interval)
                .putString("gateway", gateway)
                .putBoolean("wifi_renew_enabled", wifiRenewEnabled)
                .putInt("wifi_renew_interval_min", wifiRenewInterval)
                .apply()
            currentTarget = target
            currentInterval = interval
            currentGateway = gateway
            currentWifiRenewEnabled = wifiRenewEnabled
            currentWifiRenewIntervalMin = wifiRenewInterval
        }
    }

    private fun startKeepAlive() {
        val target = etTarget.text?.toString()?.trim() ?: ""
        val intervalStr = etInterval.text?.toString()?.trim()
        val interval = intervalStr?.toIntOrNull() ?: 0
        val wifiRenewEnabled = switchWifiRenew.isChecked
        val wifiRenewInterval = etWifiRenewInterval.text?.toString()?.trim()?.toIntOrNull() ?: 0

        if (target.isEmpty()) {
            Toast.makeText(this, R.string.target_invalid, Toast.LENGTH_SHORT).show()
            switchService.isChecked = false
            return
        }

        if (interval < 5) {
            Toast.makeText(this, R.string.interval_invalid, Toast.LENGTH_SHORT).show()
            switchService.isChecked = false
            return
        }

        if (wifiRenewEnabled && wifiRenewInterval < 5) {
            Toast.makeText(this, R.string.wifi_renew_interval_invalid, Toast.LENGTH_SHORT).show()
            switchService.isChecked = false
            return
        }

        if (wifiRenewEnabled && !requestRootAccess()) {
            Toast.makeText(this, R.string.root_permission_required, Toast.LENGTH_LONG).show()
            appendLog("Root 授权失败，已取消 Wi-Fi 定时重连")
            switchWifiRenew.isChecked = false
            etWifiRenewInterval.isEnabled = false
        }

        saveSettings()

        val intent = Intent(this, KeepAliveService::class.java)
        intent.putExtra("target", currentTarget)
        intent.putExtra("interval", currentInterval)
        intent.putExtra("gateway", currentGateway)
        intent.putExtra("wifiRenewEnabled", currentWifiRenewEnabled)
        intent.putExtra("wifiRenewIntervalMin", currentWifiRenewIntervalMin)
        intent.putExtra("action", "start")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // 延迟检查服务是否真正启动
        appendLog("服务启动中...")
        handler.postDelayed({
            if (KeepAliveService.isRunning) {
                updateStatusUI(true)
                val renewText = if (currentWifiRenewEnabled) {
                    "，Wi-Fi 重连间隔: ${currentWifiRenewIntervalMin}分钟"
                } else {
                    ""
                }
                appendLog("服务已启动，目标: $currentTarget，间隔: ${currentInterval}s$renewText")
                startStatsPolling()
            } else {
                appendLog("服务启动失败")
                switchService.isChecked = false
                updateStatusUI(false)
                Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_LONG).show()
            }
        }, 1500)
    }

    private fun requestRootAccess(): Boolean {
        return try {
            appendLog("正在请求 Root 授权...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                appendLog("Root 授权超时")
                return false
            }
            val exitCode = process.exitValue()
            val granted = exitCode == 0 && output.contains("uid=0")
            if (granted) {
                appendLog("Root 授权成功")
            } else {
                appendLog("Root 授权失败: exit=$exitCode, $error")
            }
            granted
        } catch (e: Exception) {
            appendLog("Root 授权失败: ${e.message}")
            false
        }
    }

    private fun stopKeepAlive() {
        val intent = Intent(this, KeepAliveService::class.java)
        stopService(intent)
        updateStatusUI(false)
        stopStatsPolling()
        appendLog("服务已停止")
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                // 某些设备不支持
            }
        }
    }

    private fun updateStatusUI(running: Boolean) {
        if (running) {
            tvStatus.text = getString(R.string.status_running)
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            statusIndicator.setBackgroundColor(getColor(android.R.color.holo_green_light))
            cardStats.visibility = View.VISIBLE
            etTarget.isEnabled = false
            etInterval.isEnabled = false
            etGateway.isEnabled = false
            switchWifiRenew.isEnabled = false
            etWifiRenewInterval.isEnabled = false
        } else {
            tvStatus.text = getString(R.string.status_stopped)
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            statusIndicator.setBackgroundColor(getColor(android.R.color.holo_red_light))
            cardStats.visibility = View.GONE
            etTarget.isEnabled = true
            etInterval.isEnabled = true
            etGateway.isEnabled = true
            switchWifiRenew.isEnabled = true
            etWifiRenewInterval.isEnabled = switchWifiRenew.isChecked
        }
    }

    private fun startStatsPolling() {
        stopStatsPolling()
        statsRunnable = object : Runnable {
            override fun run() {
                if (KeepAliveService.isRunning) {
                    val s = KeepAliveService.successCount.get()
                    val f = KeepAliveService.failCount.get()
                    val lastTime = if (KeepAliveService.lastSuccessTime.get() > 0) {
                        val elapsed = (System.currentTimeMillis() - KeepAliveService.lastSuccessTime.get()) / 1000
                        "${elapsed}s前"
                    } else "无"
                    val renewCount = KeepAliveService.wifiRenewCount.get()
                    val renewFail = KeepAliveService.wifiRenewFailCount.get()
                    val lastRenew = if (KeepAliveService.lastWifiRenewTime.get() > 0) {
                        val elapsed = (System.currentTimeMillis() - KeepAliveService.lastWifiRenewTime.get()) / 1000
                        "${elapsed}s前"
                    } else "无"
                    tvStats.text = "成功: $s\n失败: $f\n最近成功: $lastTime\nWi-Fi 重连: $renewCount\n重连失败: $renewFail\n最近重连: $lastRenew"
                    handler.postDelayed(this, 3000)
                } else {
                    switchService.isChecked = false
                    updateStatusUI(false)
                }
            }
        }
        handler.post(statsRunnable!!)
    }

    private fun stopStatsPolling() {
        statsRunnable?.let { handler.removeCallbacks(it) }
        statsRunnable = null
    }

    private fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logBuilder.append("[$timestamp] $message\n")
        if (logBuilder.length > 10000) {
            logBuilder.delete(0, 2000)
        }
        tvLog.text = logBuilder.toString()
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时同步状态
        if (KeepAliveService.isRunning && !switchService.isChecked) {
            switchService.isChecked = true
            updateStatusUI(true)
            startStatsPolling()
        }
    }

    override fun onDestroy() {
        stopStatsPolling()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
