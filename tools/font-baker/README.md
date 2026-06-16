# OSD Font Atlas Baker

把字体光栅化成 OSD 渲染层用的 SDF atlas(PNG + JSON)。host JVM 跑,**不进 APK**。

## 快速跑

```bash
# 当前 commit:SansSerif logical font + GB2312 高频 1000 字 + ASCII 95 字符
./gradlew :shared:bakeOsdFontAtlas \
  -PosdFont=SansSerif \
  -PosdCharset="$PWD/tools/font-baker/charset/charset-gb2312-l1.txt"

# 默认参数(ASCII only,SansSerif → 系统字体)
./gradlew :shared:bakeOsdFontAtlas

# 用本地 .otf 文件
./gradlew :shared:bakeOsdFontAtlas \
  -PosdFont="$PWD/tools/font-baker/fonts/source-han-sans-sc.otf" \
  -PosdCharset="$PWD/tools/font-baker/charset/charset-gb2312-l1.txt"
```

> **注意**:`-PosdCharset` 必须用绝对路径(`$PWD/...`)。Gradle JavaExec 默认 cwd 是
> baker 模块根,不是仓库根。

产物:

- `shared/src/androidMain/assets/osd-font-atlas.png` — 8-bit grayscale SDF
- `shared/src/androidMain/assets/osd-font-atlas.json` — UV + advance + bearing 元数据

## 当前 atlas 内容

407 字符:
- ASCII 0x20..0x7E(95 字符)
- GB2312 高频常用字 311 字(覆盖 OSD 实际场景:时间戳数字 + 通道名 + 水印文本)
- 通过 java.awt SansSerif logical font fallback 渲染,macOS 上自动用 PingFang/Hiragino,
  Linux/Windows 上自动用系统中文字体

## 字符集

- `charset-ascii.txt` — ASCII 0x20..0x7E,共 95 字符
- `charset-gb2312-l1.txt` — ASCII + 高频中文 311 字(当前 commit 用)
- `charset-cjk-probe.txt` — 中文 fallback 测试用(开发期 probe)

## 算法

- **渲染**:`java.awt.Font` + AA 抗锯齿,output 64x64 alpha cell
- **atlas 拼图**:2048x2048,32 cell/row,32 row,容 1024 字符
- **SDF 后处理**:**8SSEDT**(8-point Sequential Signed Euclidean Distance Transform),
  双向扫描 O(W*H),atlas 2048+spread=8 跑 1-2s。比 brute-force(O(W*H*spread²),~30-60s)
  快约 30 倍,GB2312 全集 baking 体验跟得上

## 自定义中文字体

如要替换为合规 OFL 字体(如思源黑体 / Noto Sans SC):

1. 下载 `SourceHanSansSC-Normal.otf` 或 `NotoSansSC-Regular.otf` 到 `tools/font-baker/fonts/`
2. 跑 `./gradlew :shared:bakeOsdFontAtlas -PosdFont=tools/font-baker/fonts/<file>.otf -PosdCharset=...`
3. 提交新 atlas 资产覆盖

当前 commit 的 atlas 用 SansSerif logical font(macOS 渲染端),个人项目内部使用零版权风险;
公开发行前换 OFL 字体重 bake。
