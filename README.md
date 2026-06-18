<div align="center">

# UVP GB28181 Sim

**把手机变成一台 GB/T 28181-2022 国标摄像头**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-brightgreen.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1+-7F52FF.svg)](https://kotlinlang.org/)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.7+-4285F4.svg)](https://www.jetbrains.com/compose-multiplatform/)

</div>

---

## 什么是 UVP

**UVP（Unified Video Platform，统一视频接入平台）** 是一套面向公共安全 / 智慧城市场景的国标视频接入平台，负责将海量前端摄像头通过 GB/T 28181 协议统一接入、管理与分发。

**UVP GB28181 Sim** 是 UVP 官方开源的配套调试工具——将 iOS / Android 手机模拟为一台完整的 GB/T 28181-2022 下级设备（IPC），可注册到任意国标上级平台进行联调测试，无需购买真实摄像头。

---

## 为什么做这个

国标平台开发与测试面临一个共同痛点：

> **没有摄像头，就没法调。**

买一台合格的国标 IPC 动辄数百元，还要接线、配网、架设环境。出差在外或居家办公时，一切更是无从下手。

而市面上现有的模拟器工具：

| 问题 | 现状 |
|---|---|
| 全部闭源 | 遇到平台兼容性问题只能干等，无法定制协议细节 |
| 协议日志薄弱 | 调试看不到 SIP 报文，只能抓包猜 |
| 主动业务缺失 | 只能被动等平台拉流，无法主动上报报警 / 抓拍 / 位置 |
| 仅 Android | iOS 设备无法参与测试 |

**UVP GB28181 Sim 的回答：**

- **随身携带的虚拟摄像头** — 手机即设备，零硬件依赖
- **完整协议覆盖** — 实现 GB/T 28181-2022 设备端 60+ 功能点
- **可视化协议日志** — 每一条 SIP 报文结构化展示，调试不靠蒙
- **主动业务完备** — 报警上报、抓拍、GPS 位置、录像通知全支持
- **完全开源** — 遇到兼容性问题，直接改

---

## 能力矩阵

> ✅ 已实现 · ⚠️ 半完成 · ❌ 未实现 · 🚫 明确不做

### 注册与信令

| 功能 | 状态 |
|---|---|
| REGISTER / Digest MD5 鉴权 / 注销 | ✅ |
| 心跳保活 + 超时自动重注册（指数退避） | ✅ |
| SIP over UDP / TCP | ✅ |
| OPTIONS 探活响应 | ✅ |

### 实时音视频

| 功能 | 状态 |
|---|---|
| INVITE 点播 / BYE 停流 / CANCEL | ✅ |
| H.264 / H.265 视频编码 + PS 封装 | ✅ |
| G.711A / AAC 音频复用 | ✅ |
| RTP over UDP / TCP（RFC 4571） | ✅ |
| 强制关键帧（IFameCmd） | ✅ |

### 历史录像与回放

| 功能 | 状态 |
|---|---|
| 录像列表查询（RecordInfo） | ✅ |
| 历史回放流推送 | ✅ |
| 倍速播放（0.25× – 4×） | ✅ |
| 录像下载（Download INVITE） | ✅ |

### 设备查询（平台 → 设备）

| 功能 | 状态 |
|---|---|
| 目录查询（Catalog，GB-2022 全字段） | ✅ |
| 设备信息 / 状态查询 | ✅ |
| 设备配置查询（BasicParam / VideoParamOpt） | ✅ |
| 预置位 / 看守位查询 | ✅ |
| 巡航轨迹列表 / 详情查询 | ✅ |
| 存储卡状态查询 | ✅ |
| PTZ 精准状态查询（GB-2022） | ✅ |
| 报警状态查询 | ✅ |
| GB-2016 / GB-2022 双版本切换 | ✅ |

### 设备控制（平台 → 设备）

| 功能 | 状态 |
|---|---|
| 云台控制（PTZCmd 8 字节，含聚焦/光圈） | ✅ |
| 预置位 CRUD（设置 / 调用 / 删除） | ✅ |
| 看守位设置与自动归位 | ✅ |
| 精确云台控制（GB-2022 PTZPreciseCtrl） | ✅ |
| 巡航轨迹控制（增点 / 删点 / 速度 / 停留 / 启动） | ✅ |
| 布防 / 撤防 / 报警复位 | ✅ |
| 远程重启（复用开机自检动画） | ✅ |
| 远程录像开始 / 停止 | ✅ |
| 平台下发抓拍 | ✅ |
| 拖框放大 / 缩小 | ✅ |
| 辅助控制（雨刷 / 红外 / 加热 / 除雾 / 制冷） | ✅ |
| 在线升级（带 4 步进度回报 NOTIFY） | ✅ |
| 格式化 SD 卡（协议合规，不做业务） | ⚠️ |
| 目标跟踪（鱼眼/球机专属，手机无场景） | ⚠️ |

### 主动上报（设备 → 平台）

| 功能 | 状态 |
|---|---|
| 报警上报（Alarm Notify，9 字段全集） | ✅ |
| 抓拍 SIP 通知 + HTTP 图片上传 | ⚠️ |
| 移动设备 GPS 位置上报（周期 NOTIFY） | ✅ |
| 录像完成 / 异常通知（MediaStatus） | ✅ |

### 订阅与通知

| 功能 | 状态 |
|---|---|
| Catalog 订阅 + 增量 NOTIFY | ✅ |
| Alarm 报警事件订阅 | ✅ |
| MobilePosition 位置订阅 | ✅ |

### 语音对讲

| 功能 | 状态 |
|---|---|
| 语音广播（平台 → 设备，G.711A 下行） | ⚠️ |
| 双向语音对讲 | 🚫 |

---

## 截图

<!-- 主界面 / 连接配置 -->

<!-- 模拟中心 -->

<!-- 云台控制（PTZ + 预置位 + 巡航） -->

<!-- 辅助控制（雨刷 / 红外 / 加热） -->

<!-- 报警管理 -->

<!-- 协议日志 -->

---

## 快速开始

### Android

从 [Releases](../../releases) 下载最新 APK 安装即可，无需 Root。

### iOS

TestFlight 公开测试链接（即将发布）

### 从源码构建

```bash
# 需要 JDK 17
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

# Android 真机安装
./gradlew :androidApp:installDebug

# iOS — 用 Xcode 打开
open iosApp/iosApp.xcodeproj
```

### 兼容平台

已在以下国标上级平台完成联调验证：

| 平台 | 版本 | 验证状态 |
|---|---|---|
| WVP-Pro | — | ✅ |
| EasyGBS | — | ✅ |
| LiveGBS | — | ✅ |
| UVP | — | ✅ |

---

## 联系我

<!-- 填写你的联系方式（邮箱 / GitHub / 微信等） -->

---

## 请我喝杯咖啡 ☕

如果这个工具帮你省下了买摄像头的钱，欢迎请我喝杯咖啡 😄

<!-- 在这里放爱发电 / 微信 / 支付宝收款码图片 -->

---

## License

MIT — see [LICENSE](LICENSE)
