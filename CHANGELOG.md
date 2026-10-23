# Changelog

本项目所有显著变更将记录在此文件,格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

---

## [1.0.8] - 2026-10-23

修掉「设备编码按 132 段填写时，第三方国标平台一点播就失败」的兼容问题。

### 修复

- **设备编码填成 132 段时点播被 488 拒绝**：接第三方国标平台时，如果「设备编码」按 132 段
  （摄像机/视频通道）填写——海康一类 IPC 出厂就是这种注册方式——平台直接点这个编号会被判成
  「点播设备根」而回 `488 cannot invite device root`，表现是注册、心跳全正常，一点播就失败。
  现在按编码语义放行：20 位编码第 11-13 位为 132 的设备根允许点播并正常出流；111 / 118 这类
  真正的设备编码、以及非 20 位的自定义编码仍按 §C.2.3 拒绝。设备根编码不是 132 的原有行为
  完全不变。

### 升级提示

- 直接覆盖安装即可，原有配置、目录与设备状态都会保留。

---

## [1.0.7] - 2026-10-23

修掉 UDP 场景下平台看不到设备真实映射地址导致的「注册成功但下行全超时」，并让界面上的
NAT 提示与实际策略一致。

### 修复

- **注册映射**：设备 Contact 一直填的是本机地址，设备在 NAT / VPN 后面时，平台按公网映射地址
  回 ACK，源地址对不上被判无效，点播、云台、查询在 200 OK 之后全部超时。现在会把平台在响应
  Via 里回填的 `received` 与 `rport` 组成完整端点，写入后续 REGISTER 与注销报文的 Contact。
  只有地址和端口都回填时才采用，只回填一半的保持原值，避免把不可用地址传播出去。
- **端点刷新**：本次 REGISTER 的 200 到达后发现请求仍宣告的是旧端点，后台自动做一次完整
  重注册刷新 Contact，只做一次，不会反复抖动；注销或断链时相关状态随会话一起清空。

### 变更

- **NAT 提示不再强推 TCP**：此前判定处于地址转换就提示“必须改用 TCP 注册”。现在改为先尝试用
  映射端点修正 Contact，UDP 可继续使用；只有平台没回填完整端点、或下行仍然异常时，才建议
  切换 TCP 或配置端口映射。设置页与日志文案同步调整。
- 主屏订阅卡片「PTZ 精准位置订阅」更名为「PTZ 订阅」（业务含义不变，仍是订阅精准位置）。

### 升级提示

- 直接覆盖安装即可，原有配置、目录与设备状态都会保留。

---

## [1.0.6] - 2026-10-21

国标 2022 设备端能力补齐 + 目录结构重做，同时修掉三个现场联调里踩到的信令问题。

### 新增

- **目录**：支持 GB/T 28181-2022 的**业务分组(215) / 虚拟组织(216)** 目录结构，目录管理里
  提供「下级平台」标准模板；目录项补「通道安装位置」；通道属性同时按 2016 / 2022 两种形态输出
  并支持增量通知；支持按目标 ID 查询目录。
- **设备配置**：设备配置族 8 组 ConfigType 全量落地，配置面板可直接下发与回读。
- **存储卡**：虚拟存储卡模型 + 录像码流号记账，「状态」页可直接格式化，格式化会真正改变
  设备侧读数（A.2.3.1.13）。
- **云台**：表 A.10 自动扫描族；巡航执行、看守位自动归位与精准状态查询；预置位编号改为取
  字节 6，补齐 FI 族解码。
- **图像**：拉框放大 / 缩小、画面遮挡 / 镜像都作用到**真实视频流**（A.2.3.1.8 / .9）；
  目标跟踪落设备侧持续态，并在画面上显示跟踪框（A.2.3.1.14）。
- **信令**：附录 I 版本协商、Via 观测端点（Rport）、TCP 断链观测与重连监管，长连接自愈过程可见；
  信令字符集抽象（GB2312 / UTF-8）贯通全部出站报文。
- **状态持久化**：预置位 / 巡航 / 看守位 / 姿态 / 辅助开关重启后保留。
- **移动位置**：MobilePosition NOTIFY 按国标版本分别输出 2022 列表与 2016 扁平格式。
- **扫码接入**：扫码成功有提示音与振动；注册失败态可直接再扫一次重试。
- **模拟中心**：HUD 重新排布，云台控制台改为四方形操作盘，同时展示光圈、聚焦与看守位状态。

### 修复

- 手机同时连局域网和 VPN 时，Contact 里公布了 VPN 地址，平台按它回 ACK 后源地址不匹配被设备侧
  拒绝，点播断在 200 OK 之后。改为**按信令路由**选择本机地址，媒体地址与信令地址分开取。
- 平台因 UDP 事务超时重发同一条 MESSAGE（同 Call-ID 同 CSeq）时，业务层每收到一次就重复执行，
  把一次正常的设备抓拍污染成「整批失败」。现按事务去重，重复报文不再进入业务。
- 一条 DeviceConfig 报文里并存多块配置时，只有第一块生效。
- SUBSCRIBE 报文缺 Contact 时，回到 NOTIFY 的请求 URI 拼错。
- 目录查询偶发超时（sourceIp 归一化占用主线程）。
- Android 相机预览在旋转缓冲下比例失真、偶发重复旋转。

### 变更

- **辅助开关收敛为「雨刷」单项**：GB/T 28181-2022 表 A.11 全节只定义编号 1（雨刷），
  此前的红外灯 / 加热 / 除雾 / 制冷没有标准依据，已移除对应状态灯与画面叠加。
  收到编号 2~5 仍会解码（编号域是开放值，不能拒收），但不落状态，只在指令记录里留痕。
- 目录管理界面按节点类型约束可选父节点；移动 / 新增节点时自动维护虚拟组织的业务分组归属与
  通道的物理父级路径，后代分组同步跟随。

### 升级提示

- 直接覆盖安装即可，原有配置、目录与设备状态都会保留。
- 老配置的目录树仍按设备视角生成默认树，不会被打散。

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
