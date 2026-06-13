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

class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_TARGET = "2001:4860:4860::8888"
        const val DEFAULT_INTERVAL = 30
        const val DEFAULT_GATEWAY = "fe80::a6a9:30ff:fecd:28bc"
    }

    private lateinit var switchService: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var statusIndicator: View
    private lateinit var etTarget: TextInputEditText
    private lateinit var etInterval: TextInputEditText
    private lateinit var etGateway: TextInputEditText
    private lateinit var cardStats: MaterialCardView
    private lateinit var tvStats: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScrollView: ScrollView

    private var currentTarget: String = DEFAULT_TARGET
    private var currentInterval: Int = DEFAULT_INTERVAL
    private var currentGateway: String = DEFAULT_GATEWAY
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
        cardStats = findViewById(R.id.cardStats)
        tvStats = findViewById(R.id.tvStats)
        tvLog = findViewById(R.id.tvLog)
        logScrollView = tvLog.parent as ScrollView

        etTarget.setText(currentTarget)
        etInterval.setText(currentInterval.toString())
        etGateway.setText(currentGateway)

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startKeepAlive()
            } else {
                stopKeepAlive()
            }
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
    }

    private fun saveSettings() {
        val target = etTarget.text?.toString()?.trim() ?: DEFAULT_TARGET
        val intervalStr = etInterval.text?.toString()?.trim()
        val interval = intervalStr?.toIntOrNull() ?: DEFAULT_INTERVAL
        val gateway = etGateway.text?.toString()?.trim() ?: DEFAULT_GATEWAY

        if (target.isNotEmpty() && interval >= 5) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit()
                .putString("target", target)
                .putInt("interval", interval)
                .putString("gateway", gateway)
                .apply()
            currentTarget = target
            currentInterval = interval
            currentGateway = gateway
        }
    }

    private fun startKeepAlive() {
        val target = etTarget.text?.toString()?.trim() ?: ""
        val intervalStr = etInterval.text?.toString()?.trim()
        val interval = intervalStr?.toIntOrNull() ?: 0

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

        saveSettings()

        val intent = Intent(this, KeepAliveService::class.java)
        intent.putExtra("target", currentTarget)
        intent.putExtra("interval", currentInterval)
        intent.putExtra("gateway", currentGateway)
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
                appendLog("服务已启动，目标: $currentTarget，间隔: ${currentInterval}s")
                startStatsPolling()
            } else {
                appendLog("服务启动失败")
                switchService.isChecked = false
                updateStatusUI(false)
                Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_LONG).show()
            }
        }, 1500)
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
        } else {
            tvStatus.text = getString(R.string.status_stopped)
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            statusIndicator.setBackgroundColor(getColor(android.R.color.holo_red_light))
            cardStats.visibility = View.GONE
            etTarget.isEnabled = true
            etInterval.isEnabled = true
            etGateway.isEnabled = true
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
                    tvStats.text = "成功: $s\n失败: $f\n最近成功: $lastTime"
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
