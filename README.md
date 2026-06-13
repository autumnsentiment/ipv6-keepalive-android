# IPv6 Keepalive Android

[中文](#中文) | [English](#english)

## 中文

IPv6 Keepalive Android 是一个轻量级 Android 工具，用于通过前台服务定期发送 IPv6 保活流量，帮助维持 IPv6 Wi-Fi 连接活跃。

### 软件特点

- 基于前台服务运行，适合长时间后台保活。
- 服务启动后立即执行一次保活发送。
- 按可配置间隔向目标 IPv6 地址发送 UDP 保活包。
- 自动选择具备 IPv6 地址的网络，并优先使用 Wi-Fi。
- 将 UDP socket 绑定到选中的 Android 网络，减少流量走错接口的概率。
- UDP 发送失败后使用 `ping6` 作为备用检测方式。
- 使用 CPU WakeLock 和 Wi-Fi Lock，降低系统休眠对保活的影响。
- 使用 AlarmManager 定时唤醒，帮助服务在被系统中断后恢复。
- 支持开机后按已保存设置自动启动。
- 应用内可配置目标地址、发送间隔、网关地址，并查看运行状态与成功/失败统计。
- 支持可选的 root 路径修复 IPv6 默认路由。

### 默认配置

- 目标 IPv6 地址：`2001:4860:4860::8888`
- 发送间隔：`30` 秒
- 网关地址：`fe80::a6a9:30ff:fecd:28bc`

网关地址仅用于可选的路由修复逻辑，该功能需要 root 权限。

### 构建方式

构建要求：

- Android Studio 或 Android SDK
- JDK 17

构建 debug APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 权限说明

应用会请求网络、前台服务、WakeLock、开机广播、通知和精确闹钟等权限，用于后台保活、前台服务通知、重启恢复和定时唤醒。

部分 Android 厂商系统会施加更激进的后台限制。为了获得更稳定的保活效果，建议允许本应用忽略电池优化。

### 许可证

本项目采用 MIT License。

你可以自由使用、复制、修改、合并、发布、分发、再授权和销售本项目副本，但需保留原始版权声明和许可证声明。

完整许可证文本见 [LICENSE](LICENSE)。

## English

IPv6 Keepalive Android is a lightweight Android utility that periodically sends IPv6 keepalive traffic from a foreground service to help keep IPv6 Wi-Fi connectivity active.

### Features

- Foreground service based keepalive loop for long-running operation.
- Immediate keepalive attempt when the service starts.
- Periodic UDP keepalive packets to a configurable IPv6 target.
- IPv6-capable network selection with Wi-Fi preferred when available.
- Socket binding to the selected Android network, reducing the chance of traffic leaving through the wrong interface.
- Fallback `ping6` check when UDP sending fails.
- CPU WakeLock and Wi-Fi Lock support to reduce idle interruption.
- AlarmManager wakeups to help recover from service interruption.
- Optional boot receiver restart using saved settings.
- In-app controls for target address, interval, gateway, status, and basic success/failure statistics.
- Optional root-based IPv6 default route repair using the configured gateway.

### Default Settings

- Target IPv6 address: `2001:4860:4860::8888`
- Interval: `30` seconds
- Gateway: `fe80::a6a9:30ff:fecd:28bc`

The gateway is only used for the optional route repair path, which requires root access.

### Build

Requirements:

- Android Studio or Android SDK
- JDK 17

Build a debug APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

The generated APK will be written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Permissions

The app requests network, foreground service, WakeLock, boot completed, notification, and exact alarm related permissions so the keepalive service can run in the background and recover after reboot where supported by the device.

Some Android vendors apply aggressive background restrictions. For best results, allow battery optimization exemptions for this app.

### License

This project is licensed under the MIT License.

You may freely use, copy, modify, merge, publish, distribute, sublicense, and sell copies of this project, provided that the original copyright notice and license notice are retained.

See [LICENSE](LICENSE) for the full license text.
