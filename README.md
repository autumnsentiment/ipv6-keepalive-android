# IPv6 Keepalive

IPv6 Keepalive is a small Android utility for keeping IPv6 Wi-Fi connectivity active by periodically sending lightweight IPv6 keepalive traffic from a foreground service.

## Features

- Foreground service based keepalive loop for long-running operation.
- Immediate keepalive attempt when the service starts.
- Periodic UDP keepalive packets to a configurable IPv6 target.
- IPv6-capable network selection with Wi-Fi preferred when available.
- Socket binding to the selected Android network, reducing the chance of traffic leaving through the wrong interface.
- Fallback `ping6` check when UDP sending fails.
- Wake lock and Wi-Fi lock support to reduce idle interruption.
- AlarmManager wakeups to help recover from service interruption.
- Optional boot receiver restart using saved settings.
- In-app controls for target address, interval, gateway, status, and basic success/failure statistics.
- Optional root-based IPv6 default route repair using the configured gateway.

## Default Settings

- Target IPv6 address: `2001:4860:4860::8888`
- Interval: `30` seconds
- Gateway: `fe80::a6a9:30ff:fecd:28bc`

The gateway is only used for the optional route repair path, which requires root access.

## Build

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

## Permissions

The app requests network, foreground service, wake lock, boot completed, notification, and exact alarm related permissions so the keepalive service can run in the background and recover after reboot where supported by the device.

Some Android vendors apply aggressive background restrictions. For best results, allow battery optimization exemptions for this app.

## License

This project is licensed under the PolyForm Noncommercial License 1.0.0.

You may use, study, modify, and share the source for noncommercial purposes. Commercial use is not permitted without separate written permission from the copyright holder.

See [LICENSE](LICENSE) for the full license text.
