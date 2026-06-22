# Changelog

本项目所有显著变更将记录在此文件,格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

---

## [1.0.1] - 2026-06-22

首次启动 UX 补漏 — SIP 配置卡新手友好性 + 注册按钮防误触。

### 新增

- SIP 配置卡所有字段加 placeholder 格式提示(IP / 端口 / 服务器 ID / 服务器域 / 设备 ID / 密码)
- SIP 卡编辑态新增「取消」按钮,可无校验丢弃编辑退出(解决填到一半想撤回但被表单校验卡死的死锁)
- 注册按钮接入 `SimConfig.isReadyToRegister` 校验,SIP 必填项不全时按钮灰显 + 文案「请先填写 SIP 配置」,避免向上级平台发空字段 REGISTER

### 变更

- 首次启动默认值:上级平台相关字段(IP / serverId / domain / password / port)全部清空,强制用户填真实参数,避免误连测试环境
- 设备侧三个 ID(deviceId / videoChannelId / alarmChannelId)预填国标示例值 `34020000...`,降低 onboarding 门槛
- `migrateDualChannel` 增加 domain 空兜底,避免 domain 未填时生成伪造前置通道编码 `0000000000xxx`
- `updateConfig` 入口也跑 `migrateDualChannel`,用户首次填好 domain 保存后立刻补全 `frontChannelId`(不再等冷启动)

---

## [1.0.0] - 2026-06-22

首个正式版本。已在 WVP-Pro / EasyGBS / LiveGBS / UVP 四个国标上级平台完成联调验证,
GB/T 28181-2022 设备端协议覆盖率 94.5%(103/109,去除明确不做项)。

### 注册与信令(§9.1 / §9.4 / §5.2)

- REGISTER 注册 / Digest MD5 鉴权 / Unregister 注销
- Keepalive 心跳 + 超时 N 次自动重注册(指数退避)
- Expires 到期前自动续约(80% 时机)
- SIP over UDP / TCP(RFC 4571 长度前缀)
- OPTIONS 探活响应(Allow 头 9 方法)
- Via rport NAT 穿透 / Date 头 RFC1123 / Subject 头 / User-Agent 头

### 实时音视频(§9.2)

- INVITE / 200 OK(SDP answer) / BYE / CANCEL / ACK 全流程,32s ACK 超时看门狗
- H.264 + H.265 视频编码(Android MediaCodec)
- G.711A + AAC 音频编码与 PS 复用
- PS 封装(Pack / System Header / PSM / PES)
- RTP over UDP / TCP(主动 + 被动)
- 强制关键帧响应(IFameCmd)
- RTCP SR 反馈(RFC3550 §6.4.1,5s 周期)

### 历史录像与回放(§9.6 / §9.7)

- 录像列表查询(RecordInfo,GB-2022 高级过滤字段:IndistinctQuery / FilePath / Address / RecorderID)
- 历史回放 INVITE(s=Playback)+ 多段 PTS 平移 + 节流推流
- 倍速播放 0.25× / 0.5× / 1× / 2× / 4×(WVP 真机验)
- 拖动 / 暂停 / 继续(INFO + MANSRTSP)
- 录像下载 INVITE(s=Download)+ MediaStatus 完成通知(EasyGBS 真机验)

### 设备查询(§9.3)

- Catalog 目录查询(GB-2022 全字段:IPAddress / Port / PTZType / PositionType / RoomType / UseType / SupplyLightType / DirectionType / Resolution / BusinessGroupID)
- DeviceInfo / DeviceStatus / ConfigDownload / PresetQuery / RecordInfo
- MobilePosition 单次查询
- AlarmStatusQuery(GB-2016/2022 双版本)
- HomePositionQuery 看守位查询
- StorageCardStatusQuery 存储卡状态查询
- CruiseTrackListQuery / CruiseTrackQuery 巡航轨迹查询
- PTZPreciseStatusQuery PTZ 精准状态查询(GB-2022)
- GB-2016 / GB-2022 双版本切换

### 设备控制(§9.3.4)

- PTZCmd 8 字节解码(Pan / Tilt / Zoom,3D 模型实时旋转)
- 预置位 CRUD(SetPreset / CallPreset / DelPreset,最多 8 个)
- 看守位 HomePosition + 自动归位动画
- 巡航控制(SetPoint / DelPoint / Speed / Time / Start)
- PTZPreciseCtrl 精确云台控制(GB-2022)
- 辅助控制(雨刷 / 红外 / 加热 / 除雾 / 制冷,海康/大华事实标准)
- Focus 聚焦(byte3 bit6/bit7)
- 强制关键帧(IFameCmd)
- 远程重启(TeleBoot)+ 复用开机自检动画
- 远程录像(RecordCmd Start / Stop)
- 布防 / 撤防(GuardCmd)+ GuardOverlay 视觉反馈
- 报警复位(AlarmCmd)
- 拉框放大 / 缩小(DragZoomIn / Out)
- 平台下发抓拍(SnapShotCmd)+ 快门动画
- 设备配置修改(DeviceConfig BasicParam)
- 在线升级(DeviceUpgrade)+ 4 步进度 NOTIFY 闭环
- 格式化 SD 卡(FormatSDCard,协议合规)
- 目标跟踪(TargetTrack,协议合规)
- SIP Date 头校时(注册 200 OK 接入)

### 主动业务(§9.5)

- 报警上报 Alarm Notify(9 字段全集)+ AlarmManagementScreen 自定义模板
- 移动设备位置 MobilePosition Notify(周期 + 单次)
- 录像完成 / 异常通知 MediaStatusNotify(121 完成 / 122 异常 / 123 存储满)
- Catalog 通道在线状态切换 NOTIFY(增量 + 简化两种格式)

### 订阅(§9.3.1.4 / §9.5.2 / §9.5.4)

- Catalog 订阅 + initial 全量 + 增量 NOTIFY(diff 状态机,大改动走全量)
- Alarm 报警事件订阅(WVP 兼容格式 Event:presence + body CmdType=Alarm)
- MobilePosition 位置订阅

### 多通道与外设(§6 / §9.8)

- 多视频通道 + 虚拟通道 CRUD + 4 套模板(单设备 / 8ch NVR / 3×2 跨区划 / 16ch 大型双业务分组)
- 报警通道作为独立 Catalog 节点(typeCode=134)
- 语音广播 Broadcast 下行(G.711A,UDP / TCP 主被动)

### OSD 叠加(行业惯例,非协议要求)

- 时间戳叠加(左上角,默认 ON)
- 通道名叠加(右上角,跟随当前推流通道)
- 自定义水印(全屏斜向平铺)
- OSD 屏幕预览同步(16:9 比例)
- OSD 录像 / 回放路径烧戳(MediaCodec + MediaMuxer)

### UI / 工程

- Compose Multiplatform 构建,Material 3
- 浮窗 PTZ 控制面板 + 辅助控制开关 + 巡航轨迹 chip
- 协议日志可视化展示 + 通知面板(平台命令)
- Filament 3D `.glb` 真机模型实时云台 / 焦距演示

### 不发布范围(明确不做)

- iOS 端:代码骨架已就位,媒体线接入排期 v1.1
- 抓拍 HTTP 图片上传:代码完成,缺生产平台真机验收
- SRTP / TLS 信令加密 / REFER 级联:模拟器场景不适用
- 次码流 / 主子码流切换:GB28181 标准本身无此信令,业界靠多通道实现

[1.0.0]: ../../releases/tag/v1.0.0
