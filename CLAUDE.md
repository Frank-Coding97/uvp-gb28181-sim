# CLAUDE.md — uvp-gb28181-sim

## 项目简介

通用 GB/T 28181-2022 下级设备模拟器(iOS + Android)，Kotlin Multiplatform + Compose Multiplatform。

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew build                          # 全量编译
./gradlew :androidApp:installDebug       # Android 真机(壳模块,composeApp 是 library 无 installDebug)
```

## 模块结构

- `shared/` — commonMain 跨平台核心(SIP 信令栈 / PS Muxer / RTP / GB28181 MANSCDP)
- `composeApp/` — Compose Multiplatform UI 层
- `androidApp/` — Android 壳
- `iosApp/` — iOS 壳(Xcode)
- `dev-env/` — 本地 WVP docker 联调环境

## GB/T 28181 设备端能力追踪

完整矩阵见 Atlas: `~/Documents/Atlas/wiki/projects/uvp-gb28181-sim/research/gb28181-device-capability-matrix.md`

**规则**: 每当实现或完善了 GB28181 协议功能(不论大小),必须同步更新上述矩阵文件中对应行的:
1. **状态**列: ❌ → ✅ (或 ❌ → ⚠️ 表示半完成)
2. **完成日期**列: 填入当天日期 `YYYY-MM-DD`
3. **备注**列: 简要写明实现位置(文件名或关键函数)

触发时机:
- 新增了对某个 CmdType 的响应(如 DeviceInfo / DeviceStatus)
- 新增了 SIP method 路由(如 SUBSCRIBE 处理)
- 新增了媒体能力(如 H.265 / RTP over TCP)
- 修复了协议合规问题(如补 Subject 头 / Date 头)
- 任何让设备端"更合格"的改动

不需要老板提醒,AI 自行判断是否涉及矩阵中的项目并主动更新。

## 开发约定

- commit 格式: `<type>(<scope>): <description>` — 如 `feat(sip): T03 SIP 状态机 + Digest auth`
- 分支策略: trunk-based(main + 短 feature 分支)
- 测试: `shared/src/commonTest/` 单元测试,真机联调手测
- spec/plan/tasks 长期文档在 Atlas,不在项目内
