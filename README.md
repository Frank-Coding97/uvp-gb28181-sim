# UVP GB28181 Sim

> 通用 GB/T 28181-2022 下级设备模拟器(iOS + Android)
> 把手机变成"国标摄像头",注册到任意国标平台联调

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1+-7F52FF.svg)](https://kotlinlang.org/)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.7+-4285F4.svg)](https://www.jetbrains.com/compose-multiplatform/)

## 状态

🚧 **M1 开发中** — 详见 [项目路线图](https://github.com/Frank-Coding97/uvp-gb28181-sim)

## 简介

UVP GB28181 Sim 是 **UVP 视频接入平台** 的配套开源工具,
让国标平台开发者 / 测试工程师不用买真摄像头就能联调 GB28181 协议。

## 核心特性

- 📡 **GB/T 28181-2022 兼容**:支持注册 / 心跳 / 目录 / 实时点播 / 主动业务
- 📱 **iOS + Android 双端**:Kotlin Multiplatform 单一代码库
- 🎯 **平台预设**:WVP / EasyCVR / LiveGBS / UVP 一键接入
- 📷 **真实媒体流**:手机摄像头 H.264 → PS over RTP 推流
- 🔍 **协议日志可视化**(M3):SIP 报文事务折叠 + 时序图
- ⚡ **测试场景预设**(M3):异常注入 + 协议合规体检

## 路线图

| 里程碑 | 状态 | 时间 |
|---|---|---|
| M1 MVP — 注册 + 实时点播 | 🚧 开发中 | 2026-06 |
| M2 主动业务 + TestFlight | ⏳ | 2026-07 |
| M3 差异化(日志/异常注入/体检) | ⏳ | 2026-08 |
| M4 开源 + App Store 上架 | ⏳ | 2026-09 |

## 技术栈

- **跨平台**: Kotlin Multiplatform + Compose Multiplatform
- **SIP 信令**: 自研 GB28181 子集(commonMain)
- **媒体**: Android `MediaCodec` / iOS `VideoToolbox` + 自研 PS Muxer
- **网络**: Ktor sockets(UDP/TCP)
- **DI**: Koin 4

## 文档

详细 spec/plan/tasks 在 Atlas 知识库:
- specs/v1.md
- plans/v1.md
- tasks/v1.md
- decisions/(技术选型 / 视觉风格)

## 开发

```bash
# JDK 17 (brew install openjdk@17)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

# 编译
./gradlew build

# Android 真机
./gradlew :composeApp:installDebug

# iOS Simulator
open iosApp/iosApp.xcodeproj
```

## License

MIT — see [LICENSE](LICENSE)
