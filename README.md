# WA Push

WhatsApp notifications and calls on Android without Google Play Services.

It runs WhatsApp Web in the background as a linked device and shows its messages and calls as normal notifications. You don't show as online and nothing gets marked as read.

Needs Android 14+. Tested on GrapheneOS. Not affiliated with WhatsApp or Meta.

## Setup

1. Install the APK and open it.
2. Tap Link, choose to log in with your phone number and enter the code in WhatsApp under Linked devices.
   If that doesn't work, mirror your phone to a computer with [scrcpy](https://github.com/Genymobile/scrcpy) and scan the QR code instead.
3. Tap every Fix button the app shows.

Done.

## Build

```
./gradlew assembleRelease
```

## License

GPL-3.0
