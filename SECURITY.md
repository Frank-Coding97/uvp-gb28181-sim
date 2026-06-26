# Security Policy

## Supported Versions

| 版本 | 安全更新 |
|---|---|
| `main` 分支 | 是（持续维护） |
| 最新 release（GitHub Releases） | 是 |
| 旧 release | 否（建议升级到最新） |

## 报告漏洞 / Reporting a Vulnerability

请**不要**通过公开 issue 或 PR 报告安全漏洞——这会让其他用户在补丁发布前暴露在风险中。

### 推荐渠道：GitHub Security Advisory

1. 打开本仓库的 [Security 标签页](../../security/advisories/new)
2. 选 **Report a vulnerability**
3. 描述漏洞细节 + 复现步骤 + 影响范围

GitHub Security Advisory 全程私有，只有项目维护者可见，直到协调披露完成。

### 邮箱回退

若 GitHub Security Advisory 不可用，请发邮件到（详见仓库根 README 的 *联系我* 段落联系方式）。
邮件主题请以 `[SECURITY]` 开头。

### 我们的响应承诺

- **48 小时内**确认收到（工作日）
- **7 个工作日内**给出初步分析与修复时间表
- **高危漏洞**在补丁验证通过后立即发版，并在 GitHub Security Advisory 公开 CVE 级别说明
- **中低危漏洞**纳入下一个常规 release 周期

## 披露策略 / Disclosure Policy

本项目遵循 **协调披露（Coordinated Disclosure）**：

1. 报告者私下提交漏洞 → 维护者确认 + 修复
2. 修复版本发布后，给报告者 90 天宽限期（或双方协商）
3. 宽限期结束或修复全面 rollout 后，公开漏洞细节 + 致谢报告者（如同意）

## 已知安全边界

GB/T 28181-2022 协议默认采用明文传输，本项目不在以下场景做加密承诺：

- 公网直连：SIP/RTP 明文，易被嗅探与中间人攻击
- 弱密码：Digest 认证仅做 MD5 哈希，弱密码可被字典攻击
- DoS：模拟器面向局域网联调设计，不抗压力测试或恶意 flood

详见 [README.md](README.md) *安全使用边界* 段。

如果你的场景需要 SIP over TLS / SRTP / GB35114 国密扩展，请在 issues 区开 enhancement 标签提需求，我们正在评估 v1.1 排期。

## 致谢 / Hall of Fame

感谢以下安全研究人员负责任地披露问题（待补，欢迎提交首位）：

- _空_
