package com.example.ipv6keepalive

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class KeepAliveService : Service() {

    companion object {
        private const val TAG = "IPv6Keepalive"
        const val CHANNEL_ID = "ipv6_keepalive"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.example.ipv6keepalive.START"
        const val ACTION_STOP = "com.example.ipv6keepalive.STOP"
        const val ACTION_KEEPALIVE = "com.example.ipv6keepalive.KEEPALIVE"
        private const val LEGACY_DEFAULT_GATEWAY = "fe80::a6a9:30ff:fecd:28bc"

        @Volatile
        var isRunning = false
            private set

        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val lastSuccessTime = AtomicLong(0)
        val lastKeepAliveTime = AtomicLong(0)
        @Volatile
        var lastAutoGateway: String = ""
            private set
        @Volatile
        var lastAutoGatewayInterface: String = ""
            private set
        val wifiRenewCount = AtomicInteger(0)
        val wifiRenewFailCount = AtomicInteger(0)
        val lastWifiRenewTime = AtomicLong(0)
    }

    private var handler: Handler? = null
    private var workerThread: HandlerThread? = null
    private var keepAliveTask: Runnable? = null
    private var wifiRenewTask: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var target: String = "2001:4860:4860::8888"
    private var intervalSec: Int = 30
    private var gateway: String = ""
    private var wifiRenewEnabled: Boolean = false
    private var wifiRenewIntervalMin: Int = 120
    private var notifManager: NotificationManager? = null
    private var prefs: SharedPreferences? = null
    private val isKeepAliveRunning = AtomicBoolean(false)
    private var rootAccessGranted = false
    private var lastRouteFixAttemptTime = 0L
    private val lastAutoGateways = mutableMapOf<String, String>()

    private data class SelectedNetwork(
        val network: Network,
        val interfaceName: String?,
        val isWifi: Boolean,
        val gateway: String?
    )

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        acquireWakeLocks()
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("启动中..."))

        workerThread = HandlerThread("IPv6KeepaliveWorker").apply { start() }
        handler = Handler(workerThread!!.looper)
        Log.i(TAG, "KeepAliveService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // 系统重启服务，使用保存的设置
            loadSavedSettings()
            startKeepAliveLoop()
            startWifiRenewLoop()
            scheduleAlarm()
            return START_STICKY
        }

        when (intent.action ?: intent.getStringExtra("action")) {
            ACTION_STOP, "stop" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_KEEPALIVE -> {
                // AlarmManager 触发的保活检查
                Log.d(TAG, "AlarmManager keepalive check")
                if (!isKeepAliveRunning.get()) {
                    startKeepAliveLoop()
                } else {
                    kickKeepAliveNow()
                }
                scheduleAlarm()
                return START_STICKY
            }
            else -> {
                // ACTION_START or normal start
                target = intent.getStringExtra("target") ?: target
                intervalSec = intent.getIntExtra("interval", intervalSec)
                gateway = normalizeGateway(intent.getStringExtra("gateway") ?: gateway)
                wifiRenewEnabled = intent.getBooleanExtra("wifiRenewEnabled", wifiRenewEnabled)
                wifiRenewIntervalMin = intent.getIntExtra("wifiRenewIntervalMin", wifiRenewIntervalMin)

                successCount.set(0)
                failCount.set(0)
                lastSuccessTime.set(0)
                lastAutoGateway = ""
                lastAutoGatewayInterface = ""
                wifiRenewCount.set(0)
                wifiRenewFailCount.set(0)
                lastWifiRenewTime.set(0)

                startKeepAliveLoop()
                startWifiRenewLoop()
                scheduleAlarm()
            }
        }

        return START_STICKY
    }

    private fun loadSavedSettings() {
        prefs?.let {
            target = it.getString("target", "2001:4860:4860::8888") ?: "2001:4860:4860::8888"
            intervalSec = it.getInt("interval", 30)
            gateway = normalizeGateway(it.getString("gateway", ""))
            wifiRenewEnabled = it.getBoolean("wifi_renew_enabled", false)
            wifiRenewIntervalMin = it.getInt("wifi_renew_interval_min", 120)
        }
    }

    private fun acquireWakeLocks() {
        // CPU 唤醒锁
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "IPv6Keepalive::CpuWakeLock"
        )
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()

        // WiFi 锁 — 保持 WiFi 连接
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wifiManager.createWifiLock(
            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "IPv6Keepalive::WifiLock"
        )
        wifiLock?.setReferenceCounted(false)
        wifiLock?.acquire()

        Log.i(TAG, "WakeLocks acquired")
    }

    private fun startKeepAliveLoop() {
        val h = handler
        if (h == null) {
            Log.w(TAG, "Worker handler not ready, cannot start keepalive loop")
            return
        }

        intervalSec = intervalSec.coerceAtLeast(5)
        keepAliveTask?.let { h.removeCallbacks(it) }
        keepAliveTask = object : Runnable {
            override fun run() {
                doKeepAlive()
                h.postDelayed(this, intervalSec * 1000L)
            }
        }

        isKeepAliveRunning.set(true)

        // 立即执行一次
        h.post(keepAliveTask!!)
        Log.i(TAG, "Keepalive loop started: target=$target, interval=${intervalSec}s")
    }

    private fun startWifiRenewLoop() {
        val h = handler
        if (h == null) {
            Log.w(TAG, "Worker handler not ready, cannot start Wi-Fi renew loop")
            return
        }

        wifiRenewTask?.let { h.removeCallbacks(it) }
        wifiRenewTask = null

        if (!wifiRenewEnabled) {
            Log.i(TAG, "Wi-Fi renew loop disabled")
            return
        }

        if (!hasRootAccess()) {
            wifiRenewEnabled = false
            wifiRenewFailCount.incrementAndGet()
            updateNotification("未获得 Root 授权，Wi-Fi 重连已关闭")
            Log.w(TAG, "Wi-Fi renew disabled because root access is unavailable")
            return
        }
        rootAccessGranted = true

        wifiRenewIntervalMin = wifiRenewIntervalMin.coerceAtLeast(5)
        val delayMs = wifiRenewIntervalMin * 60_000L
        wifiRenewTask = object : Runnable {
            override fun run() {
                renewWifiLease()
                h.postDelayed(this, delayMs)
            }
        }
        h.postDelayed(wifiRenewTask!!, delayMs)
        Log.i(TAG, "Wi-Fi renew loop started: interval=${wifiRenewIntervalMin}min")
    }

    private fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Log.w(TAG, "Root check timed out")
                rootAccessGranted = false
                return false
            }
            val granted = process.exitValue() == 0 && output.contains("uid=0")
            rootAccessGranted = granted
            Log.i(TAG, "Root check: granted=$granted, out=$output, err=$error")
            granted
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed: ${e.message}")
            rootAccessGranted = false
            false
        }
    }

    private fun kickKeepAliveNow() {
        handler?.post {
            Log.d(TAG, "Keepalive kicked by alarm")
            doKeepAlive()
        }
    }

    private fun doKeepAlive() {
        try {
            lastKeepAliveTime.set(System.currentTimeMillis())

            // 1. 选择一个带 IPv6 地址的网络，并优先使用 WiFi。
            val selectedNetwork = findIpv6Network()
            if (selectedNetwork == null) {
                failCount.incrementAndGet()
                Log.w(TAG, "No IPv6-capable network found, skipping keepalive")
                updateNotification("未找到可用 IPv6 网络")
                return
            }

            // 2. 检查 IPv6 默认路由
            val hasRoute = hasDefaultRoute(selectedNetwork)
            if (!hasRoute) {
                Log.w(TAG, "No default IPv6 route, attempting fix...")
                fixRoute(selectedNetwork)
            }

            // 3. 发送 UDP 保活包
            val sent = sendUdpPacket(selectedNetwork)
            if (sent) {
                successCount.incrementAndGet()
                lastSuccessTime.set(System.currentTimeMillis())
                Log.d(TAG, "Keepalive packet sent OK via ${selectedNetwork.interfaceName ?: "unknown"}")
            } else {
                Log.w(TAG, "Keepalive packet send failed, trying ping6...")
                // 备用：ping6
                if (pingIPv6(selectedNetwork.interfaceName)) {
                    successCount.incrementAndGet()
                    lastSuccessTime.set(System.currentTimeMillis())
                    Log.d(TAG, "Ping6 OK")
                } else {
                    failCount.incrementAndGet()
                    Log.w(TAG, "All keepalive methods failed")
                }
            }

            updateNotification()

        } catch (e: Exception) {
            failCount.incrementAndGet()
            Log.e(TAG, "Keepalive error: ${e.message}")
            updateNotification()
        }
    }

    private fun renewWifiLease() {
        if (!wifiRenewEnabled) return

        try {
            Log.i(TAG, "Renewing Wi-Fi lease by toggling Wi-Fi")
            updateNotification("正在重连 Wi-Fi 续租...")

            val disableExit = runRootCommand("svc wifi disable")
            if (disableExit != 0) {
                wifiRenewFailCount.incrementAndGet()
                Log.w(TAG, "Wi-Fi disable failed with exit=$disableExit")
                updateNotification("Wi-Fi 重连失败：Root 命令不可用")
                return
            }

            Thread.sleep(3000)

            val enableExit = runRootCommand("svc wifi enable")
            if (enableExit != 0) {
                wifiRenewFailCount.incrementAndGet()
                Log.w(TAG, "Wi-Fi enable failed with exit=$enableExit")
                updateNotification("Wi-Fi 重连失败：无法重新开启")
                return
            }

            lastWifiRenewTime.set(System.currentTimeMillis())
            wifiRenewCount.incrementAndGet()
            Log.i(TAG, "Wi-Fi toggled, waiting for IPv6 network")

            waitForIpv6Network(45_000L)
            kickKeepAliveNow()
            updateNotification("Wi-Fi 已重连，正在恢复保活")
        } catch (e: Exception) {
            wifiRenewFailCount.incrementAndGet()
            Log.e(TAG, "Wi-Fi renew failed: ${e.message}")
            updateNotification("Wi-Fi 重连失败")
        }
    }

    private fun runRootCommand(command: String): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            process.waitFor()
            Log.i(TAG, "Root command '$command': out=$output, err=$error, exit=${process.exitValue()}")
            process.exitValue()
        } catch (e: Exception) {
            Log.e(TAG, "Root command '$command' failed: ${e.message}")
            -1
        }
    }

    private fun waitForIpv6Network(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (findIpv6Network() != null) {
                return true
            }
            Thread.sleep(2000)
        }
        return false
    }

    private fun findIpv6Network(): SelectedNetwork? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val candidates = buildList {
                cm.activeNetwork?.let { add(it) }
                addAll(cm.allNetworks.filterNot { it == cm.activeNetwork })
            }

            var firstIpv6Network: SelectedNetwork? = null
            var firstWifiIpv6Network: SelectedNetwork? = null

            for (network in candidates) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val linkProperties = cm.getLinkProperties(network) ?: continue
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val hasIpv6 = linkProperties.linkAddresses.any {
                    val address = it.address
                    address is Inet6Address && !address.isLinkLocalAddress
                }

                if (!hasIpv6) continue

                val interfaceName = linkProperties.interfaceName
                val detectedGateway = findIpv6Gateway(interfaceName, linkProperties)
                if (!detectedGateway.isNullOrBlank()) {
                    lastAutoGateway = detectedGateway
                    lastAutoGatewayInterface = interfaceName.orEmpty()
                    if (!interfaceName.isNullOrBlank()) {
                        lastAutoGateways[interfaceName] = detectedGateway
                    }
                }

                val selected = SelectedNetwork(
                    network = network,
                    interfaceName = interfaceName,
                    isWifi = isWifi,
                    gateway = detectedGateway ?: interfaceName?.let { lastAutoGateways[it] }
                )
                if (isWifi) {
                    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (hasInternet) return selected
                    if (firstWifiIpv6Network == null) firstWifiIpv6Network = selected
                } else if (firstIpv6Network == null) {
                    firstIpv6Network = selected
                }
            }
            firstWifiIpv6Network ?: firstIpv6Network
        } catch (e: Exception) {
            Log.e(TAG, "Find IPv6 network failed: ${e.message}")
            null
        }
    }

    private fun findIpv6Gateway(
        interfaceName: String?,
        linkProperties: android.net.LinkProperties
    ): String? {
        val routeGateway = linkProperties.routes
            .firstOrNull { route ->
                route.isDefaultRoute &&
                    route.gateway is Inet6Address
            }
            ?.gateway
            ?.hostAddress

        if (!routeGateway.isNullOrBlank()) {
            return stripIpv6Scope(routeGateway)
        }

        val safeInterface = interfaceName?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]+")) }
            ?: return null

        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("ip", "-6", "route", "show", "default", "dev", safeInterface)
            )
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            Regex("""default\s+via\s+([0-9A-Fa-f:.%]+)""")
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { stripIpv6Scope(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Auto gateway lookup failed: ${e.message}")
            null
        }
    }

    private fun stripIpv6Scope(address: String): String {
        return address.substringBefore('%').trim()
    }

    private fun normalizeGateway(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed == LEGACY_DEFAULT_GATEWAY) "" else trimmed
    }

    private fun hasDefaultRoute(selectedNetwork: SelectedNetwork): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProperties = cm.getLinkProperties(selectedNetwork.network) ?: return false
            linkProperties.routes.any { route ->
                route.isDefaultRoute && route.destination.address is Inet6Address
            }
        } catch (e: Exception) {
            Log.e(TAG, "Check route failed: ${e.message}")
            false
        }
    }

    private fun fixRoute(selectedNetwork: SelectedNetwork) {
        val safeInterface = selectedNetwork.interfaceName?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]+")) }
        if (safeInterface == null) {
            Log.w(TAG, "Skip route fix: unknown or unsafe interface name")
            return
        }

        val manualGateway = normalizeGateway(gateway)
        val selectedGateway = if (manualGateway.isNotEmpty()) {
            manualGateway
        } else {
            selectedNetwork.gateway.orEmpty()
        }

        if (selectedGateway.isBlank()) {
            Log.w(TAG, "Skip route fix: no manual or auto IPv6 gateway")
            return
        }

        if (!rootAccessGranted) {
            Log.w(TAG, "Skip route fix: root has not been granted in this service session")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastRouteFixAttemptTime < 10 * 60_000L) {
            Log.d(TAG, "Skip route fix: cooldown active")
            return
        }
        lastRouteFixAttemptTime = now

        try {
            // 先删除旧默认路由，再添加新的
            val command = "ip -6 route del default dev $safeInterface 2>/dev/null; ip -6 route add default via $selectedGateway dev $safeInterface"
            val delCmd = arrayOf("su", "-c", command)
            val process = Runtime.getRuntime().exec(delCmd)
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            process.waitFor()
            Log.i(TAG, "Route fix: out=$output, err=$error, exit=${process.exitValue()}")
        } catch (e: Exception) {
            Log.e(TAG, "Fix route failed: ${e.message}")
        }
    }

    private fun sendUdpPacket(selectedNetwork: SelectedNetwork): Boolean {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 5000
                socket.reuseAddress = true
                selectedNetwork.network.bindSocket(socket)

                // 发送 DNS 查询包到 IPv6 目标地址；UDP send 成功即可产生可见发包。
                val data = byteArrayOf(
                    0x00, 0x01, // Transaction ID
                    0x01, 0x00, // Flags: standard query
                    0x00, 0x01, // Questions: 1
                    0x00, 0x00, // Answer RRs: 0
                    0x00, 0x00, // Authority RRs: 0
                    0x00, 0x00, // Additional RRs: 0
                    0x00,       // Root domain
                    0x00, 0x01, // Type: A
                0x00, 0x01  // Class: IN
                )
                val addr = selectedNetwork.network.getByName(target)
                if (addr !is Inet6Address) {
                    Log.e(TAG, "Target is not IPv6: ${addr.hostAddress}")
                    return false
                }

                val packet = DatagramPacket(data, data.size, addr, 53)
                socket.send(packet)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP send failed: ${e.message}")
            false
        }
    }

    private fun pingIPv6(interfaceName: String?): Boolean {
        return try {
            val safeInterface = interfaceName?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]+")) }
            val command = if (safeInterface == null) {
                arrayOf("ping6", "-c", "1", "-W", "3", target)
            } else {
                arrayOf("ping6", "-I", safeInterface, "-c", "1", "-W", "3", target)
            }
            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Ping6 failed: ${e.message}")
            false
        }
    }

    // ====== AlarmManager 保活：定时唤醒确保服务存活 ======
    private fun scheduleAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_KEEPALIVE
        }
        val pendingIntent = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 使用精确闹钟（如果允许）+ 闹钟间隔 = intervalSec * 2
        val triggerAt = SystemClock.elapsedRealtime() + intervalSec * 2000L
        val interval = intervalSec * 2000L

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            // 某些设备不允许精确闹钟
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                interval,
                pendingIntent
            )
        }

        Log.d(TAG, "Alarm scheduled for ${intervalSec * 2}s")
    }

    private fun cancelAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_KEEPALIVE
        }
        val pendingIntent = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    // ====== 通知 ======
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IPv6 Keepalive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "IPv6 WiFi 保活服务"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notifManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        return builder.build()
    }

    private fun updateNotification(customText: String? = null) {
        val text = customText ?: run {
            val s = successCount.get()
            val f = failCount.get()
            val r = wifiRenewCount.get()
            val rf = wifiRenewFailCount.get()
            val gatewayText = if (lastAutoGateway.isNotBlank()) {
                " | 网关: $lastAutoGateway"
            } else {
                ""
            }
            val lastTime = if (lastSuccessTime.get() > 0) {
                val elapsed = (System.currentTimeMillis() - lastSuccessTime.get()) / 1000
                "${elapsed}s前"
            } else "无"
            "成功: $s | 失败: $f | 重连: $r/$rf | 最近: $lastTime$gatewayText"
        }
        try {
            notifManager?.notify(NOTIF_ID, createNotification(text))
        } catch (e: Exception) {
            Log.e(TAG, "Update notification failed: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isKeepAliveRunning.set(false)

        handler?.removeCallbacksAndMessages(null)
        handler = null
        wifiRenewTask = null
        workerThread?.quitSafely()
        workerThread = null
        cancelAlarm()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock?.let {
            if (it.isHeld) it.release()
        }

        Log.i(TAG, "KeepAliveService destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App 被划掉后重启服务
        Log.i(TAG, "Task removed, scheduling restart")

        val restartIntent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_START
            putExtra("target", target)
            putExtra("interval", intervalSec)
            putExtra("gateway", gateway)
            putExtra("wifiRenewEnabled", wifiRenewEnabled)
            putExtra("wifiRenewIntervalMin", wifiRenewIntervalMin)
        }

        val pendingIntent = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 3000,
            pendingIntent
        )

        super.onTaskRemoved(rootIntent)
    }
}
