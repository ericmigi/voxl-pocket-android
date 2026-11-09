# voxl-pocket-android

A native Android client for [voxl](https://github.com/tembo/voxl), the low-latency remote
desktop server. Built for tablets and foldables, works on phones.

## Features

- H.264 video decoded with MediaCodec, one decoder per stripe, straight to the screen
- Trackpad and touch modes: tap to click, two-finger tap for right click, long press to
  drag or right click, two-finger scroll, pinch to zoom
- Hardware keyboards and mice, plus the soft keyboard with a key bar for Esc, Tab,
  sticky Shift/Cmd/Ctrl/Opt, ⌘Space, ⌘Tab and arrows
- Keeps the host at its own resolution by default; can resize it to fit the device
  (Large, Medium, pixel perfect) when the server runs with `--enable-resize`
- Switch between the host's displays
- Clipboard both ways, text and images
- Split screen and any orientation

## Install

Download the APK from [Releases](../../releases) and open it on your device.

Tap **Add** and enter the server's address, for example `wss://my-mac.example.com:6901/ws`,
and its password. The password is encrypted with a key that stays in Android Keystore.

## Build

Requires JDK 17 and the Android SDK.

```sh
./gradlew assembleDebug
```

Release builds are signed with the key given by the `SIGNING_KEYSTORE`,
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` environment
variables, or with the debug key when they are unset.

## License

MIT
