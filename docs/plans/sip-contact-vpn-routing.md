---
title: SIP Contact 目的地址选路修复方案与任务
date: 2026-10-13
status: implementing
---

老板已授权直接实现、测试及 ADB 覆盖安装，省略阶段间等待。

## 技术方案

- 在平台资源契约增加可挂起的按目的地址取本机 IPv4 方法，其他平台默认沿用现有提供器。
- Android 在 IO 线程使用临时 UDP socket connect 查询内核选路后的 localAddress；不发送 SIP 或探测报文，不改变现有 SIP socket。系统选路自动遵循 VPN 路由及进程网络绑定。
- 显式绑定的 WIFI/CELLULAR 地址保持优先；AUTO 按目标选路。查询失败时记录警告并沿用现有地址降级逻辑。
- 注册、MESSAGE 等出站 SIP 使用访问配置平台的信令地址。每次 connect 更新该地址。
- 直播及回放的 200 OK Contact 按收到 INVITE 的对端选路，SDP 按 offer 的媒体对端选路；两者分别求值。
- 广播反向 INVITE 尚无媒体对端，保留原媒体地址提供器，SIP Contact 使用平台信令地址，避免影响现有广播媒体路径。
- 不修改来源校验、平台 ACK 路由或 RTP 发送实现。

## 风险与边界

- DNS 与选路查询必须在 IO 线程，避免 Android 主线程网络异常。
- UDP connect 只查询路由，不代表对端服务可用；手机实测确认实际报文。
- 自动化测试注入不同目的地址对应的本机地址，验证信令与媒体地址没有再次混用；Android 测试使用真实本地 socket 验证查询不发报文。
- 已建立会话的网络迁移不在范围内；重新连接及新 INVITE 重新选址。
- Release 包包含当前工作区已有目录管理修改，保留其源码改动。

## 任务与测试用例

| 任务 | 输入 | 期望结果 |
| --- | --- | --- |
| T1 目的地址选路 | 本地 UDP 接收器及目的地址 | 返回正确本机 IPv4，接收器收不到探测报文；无法解析的地址返回空值。 |
| T2 直播地址分离 | 默认 VPN 地址，LAN INVITE，VPN 媒体地址 | Contact 为 LAN，SDP 为 VPN；合法 ACK 后超过 watchdog 时限会话仍有效，合法 BYE 清理。 |
| T3 选路回归 | 纯 LAN / VPN 平台及媒体 / 显式网络偏好 | 使用对应的本机地址；原有来源身份校验测试继续通过。 |
| T4 回放地址分离 | LAN 回放 INVITE，VPN 媒体地址 | Contact 为 LAN，SDP 为 VPN，原有回放身份测试通过。 |
| T5 注册装配 | 默认 VPN 地址，平台路由返回 LAN 地址 | REGISTER Via 和 Contact 为平台路径对应的 LAN 地址。 |
| T5b 广播地址分离 | LAN 信令地址，原媒体地址为 VPN | 广播 INVITE Contact 为 LAN，SDP 保留 VPN 地址。 |
| T6 构建装机 | JDK 17、现有 Release 签名、手机 293659f9 | Release 构建与签名验证通过；adb install -r 成功，版本匹配并可启动。 |
| T7 现场验证 | 保持手机 VPN 开启，经 LAN 平台点播 | Contact 为 192.168.124.4，SDP 对 VPN 媒体服务器使用 10.8.0.4，ACK 被接受，出流并正常停止。 |

测试与实际结果在本文件补充；未执行的真机拓扑不能标为通过。

## 已执行结果

- 缺陷行为验证：关闭目的地址解析注入后，局域网 INVITE 用例断言失败，Contact 实际为 `10.8.0.4`，期望为 `192.168.124.4`。
- 恢复目的地址解析后，共享层针对性回归 29 项通过（直播地址、注册重连、直播/回放来源身份、广播地址分离及已有点播流程）。
- Android Robolectric 3 项通过：内核地址查询不发送探测报文、无效地址返回空值、显式网络绑定优先。
- 普通压缩 Release 构建在 R8 优化阶段耗时超过二十分钟，主动停止。改用 `/tmp/uvp-local-validation.gradle` 临时关闭本次 Release 的代码压缩与资源裁剪，保留原签名和版本；仓库发行构建配置未修改。
- 本地 Release APK 构建成功，包名 `cn.uvp.gb28181sim`，版本 `1.0.3/10003`。它的 Release 证书和手机上预装包的本机 Debug 证书不同；没有卸载或清除用户配置，而是用已有 Debug keystore 对同一构建产物重签一份本地更新 APK。`apksigner verify` 通过，ADB 流式安装返回 `Success`，包版本/更新时间已核验，进程 PID `6950` 正常启动。
- 真机在 VPN 和 Wi-Fi 同时开启时输出 `本机 IPv4 选路 remote=192.168.124.3 local=192.168.124.4`，而两个接口分别是 `wlan0=192.168.124.4`、`tun0=10.8.0.4`；修复选址已在目标手机上生效。
- 点播/ACK/SDP 的真机验收未完成：测试期间平台 UDP `5062` 没有监听，本机后端 health 探测无响应，已登录平台页面的刷新接口返回 HTTP 500；手机 REGISTER 连续 8 秒无响应并停止重试。因此没有宣称 ACK、媒体出流的现场验收通过。
- 安装的重签 APK 已从设备回读校验，SHA-256：`9a161e606ca62923ce87d75f203c69b3f6741a10b45455b57a163217a6e17553`。普通压缩的 Release 包留在 `androidApp/build/outputs/apk/release/uvp-gb28181-sim-1.0.3.apk`，不适用于该手机原位覆盖签名。
