# Security Policy

## Supported Versions

| 版本 | 安全更新 |
|---|---|
| `main` 分支 | 是（持续维护） |
| 最新 release（GitHub Releases） | 是 |
| 旧 release | 否（建议升级到最新） |

## In-Scope / Out-of-Scope（M-5,audit §3）

本项目是 GB/T 28181-2022 下级设备**模拟器**,设计目标是局域网联调与协议合规性验证。
安全披露范围按这个定位划分。

### In-Scope（接受报告 + 修复）

- **SIP 协议解析**:畸形 SIP 消息触发的 OOM / 崩溃 / dangling dialog / 鉴权绕过
- **MANSCDP / SDP / RTP 解析**:畸形 XML / SDP / RTP header 触发的崩溃 / 越界
- **认证与鉴权**:Digest auth 实现缺陷、cnonce 弱随机、nonce 重放
- **TCP / UDP 传输**:Content-Length 注入、超长包 OOM、socket 资源泄漏
- **配置入口**:配置文件 / DataStore 解析触发的反序列化漏洞
- **日志泄露**:SystemLogger 输出含密码 / token 等敏感字段
- **构建产物**:release APK 签名 / 嵌入凭据 / proguard 配置缺陷

### Out-of-Scope（非项目接管,不接受报告）

- **真机硬件 / 操作系统漏洞**(Android / iOS 厂商问题)
- **平台侧软件**(WVP / EasyCVR / LiveGBS 等上级平台漏洞,请直接报给对应项目)
- **UI 显示 bug / 文案 typo / 国际化**(开 issue 即可,不走安全通道)
- **明文协议本身的固有弱点**:GB/T 28181-2022 协议默认明文,SIP/RTP 中间人 /
  嗅探属于协议层风险,不在本项目修复范围(详见下方"已知安全边界")
- **公网部署**:本项目仅承诺 LAN 安全模型,公网直连模拟器不在保障范围
- **DoS / 压力测试**:模拟器不抗 flood,DoS 报告需要附 PoC 且能在 LAN 内单设备触发

## Severity Scoring（M-5,audit §3)

采用 [CVSS v3.1](https://www.first.org/cvss/v3.1/specification-document) 评分。
报告时**鼓励**附自评 CVSS,我方会复核;不附也接受,只是处理可能稍慢。

| 严重程度 | CVSS 分值 | 我方响应优先级 |
|---|---|---|
| Critical | 9.0–10.0 | 立即停下手头工作,最快路径出补丁 |
| High | 7.0–8.9 | 1 周内出补丁 |
| Medium | 4.0–6.9 | 当月 release 周期内出补丁 |
| Low | 0.1–3.9 | 下一常规 release 修(可合 PR) |

参考向量(供常见场景自评):

- **远程 OOM(RCE 前置)**:`AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H` ≈ 7.5 (High)
- **认证绕过(平台冒充设备发 INVITE)**:`AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H` ≈ 9.8 (Critical)
- **日志泄露密码**:`AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N` ≈ 5.5 (Medium)
- **DoS 单连接卡死**:`AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L` ≈ 5.3 (Medium)

## 报告漏洞 / Reporting a Vulnerability

请**不要**通过公开 issue 或 PR 报告安全漏洞——这会让其他用户在补丁发布前暴露在风险中。

### 推荐渠道:GitHub Security Advisory

1. 打开本仓库的 [Security 标签页](../../security/advisories/new)
2. 选 **Report a vulnerability**
3. 描述漏洞细节 + 复现步骤 + 影响范围(+ 可选 CVSS 自评)

GitHub Security Advisory 全程私有,只有项目维护者可见,直到协调披露完成。

### 邮箱回退

若 GitHub Security Advisory 不可用,请发邮件到(详见仓库根 README 的 *联系我* 段落联系方式)。
邮件主题请以 `[SECURITY]` 开头。

## Disclosure Timeline（M-5,audit §3)

下面是承诺时间线,以**收到完整报告**(含复现步骤)为 T0:

| 阶段 | 时限 | 动作 |
|---|---|---|
| T0 | 收到报告 | GitHub Security Advisory 通知触发 |
| T0 + 2 工作日 | 初步响应 | 确认收到 + 分配严重程度 + 是否 in-scope |
| T0 + 5 工作日 | 初步分析 | 漏洞确认 / 拒绝(out-of-scope)/ 信息求补 |
| T0 + 30 天 | 补丁就绪 | High+ 漏洞补丁 PR + release tag |
| T0 + 90 天 | 公开披露 | GitHub Security Advisory 转 public + CVE 申请(如适用) |

特殊情况:

- **Critical(CVSS ≥ 9)**:30 天上限缩到 14 天,确认有 active exploitation 时立即出补丁
- **复杂修复**(协议层重构 / 跨多模块):可与报告者协商把 90 天披露窗口延到 120 天
- **已公开 0day**(他人 leak):立即转公开,优先出补丁
- **报告者要求保密**:尊重报告者署名意愿,但 90 天后无条件转 public(除非协商延期)

## Responsible Disclosure Policy

本项目遵循 **协调披露(Coordinated Disclosure)**:

1. 报告者私下提交漏洞 → 维护者确认 + 修复
2. 修复版本发布后,默认 90 天宽限期(或双方协商)
3. 宽限期结束或修复全面 rollout 后,公开漏洞细节 + 致谢报告者(如同意)
4. 报告者**承诺**:在公开披露前不向第三方泄露,不利用漏洞触达任何真实用户系统

违反协调披露(提前公开 / 真实环境攻击)会让本项目对该报告失去保护承诺。

## 已知安全边界

GB/T 28181-2022 协议默认采用明文传输,本项目不在以下场景做加密承诺:

- 公网直连:SIP/RTP 明文,易被嗅探与中间人攻击
- 弱密码:Digest 认证仅做 MD5 哈希,弱密码可被字典攻击
- DoS:模拟器面向局域网联调设计,不抗压力测试或恶意 flood

详见 [README.md](README.md) *安全使用边界* 段。

如果你的场景需要 SIP over TLS / SRTP / GB35114 国密扩展,请在 issues 区开 enhancement 标签提需求,我们正在评估 v1.1 排期。

## 致谢 / Hall of Fame

感谢以下安全研究人员负责任地披露问题(待补,欢迎提交首位):

- _空_
