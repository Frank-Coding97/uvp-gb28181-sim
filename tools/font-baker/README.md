# OSD Font Atlas Baker

把字体光栅化成 OSD 渲染层用的 SDF atlas(PNG + JSON)。host JVM 跑,**不进 APK**。

## 当前 atlas 使用的字体

**Adobe/Google Source Han Sans SC (思源黑体) Normal** — [SIL Open Font License 1.1](fonts/OFL.txt)

字体源文件 `fonts/SourceHanSansSC-Normal.otf` 已跟仓库同存,方便贡献者本地复现 bake。OFL 明确允许光栅化产物随软件分发,且**允许商业用途**。

## 快速跑

```bash
# 默认(思源黑体 + GB2312 高频 1000 字 + ASCII 95 字符)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :shared:bakeOsdFontAtlas \
  -PosdFont="$PWD/tools/font-baker/fonts/SourceHanSansSC-Normal.otf" \
  -PosdCharset="$PWD/tools/font-baker/charset/charset-gb2312-l1.txt"

# 仅 ASCII(fallback 到 JVM SansSerif logical font — 用于开发期 probe,勿提交产物)
./gradlew :shared:bakeOsdFontAtlas
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
- 用 Adobe Source Han Sans SC Normal 渲染,所有平台构建一致(不再依赖 macOS 系统字体 fallback)

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

## 替换字体

如需换成其他 OFL 兼容字体(Noto Sans SC / 未来 SC-Medium 等):

1. 下载 .otf/.ttf 到 `tools/font-baker/fonts/`,附带上游 LICENSE 到 `fonts/`
2. 跑 `bakeOsdFontAtlas` 覆盖 atlas
3. 更新本文档 "当前 atlas 使用的字体" 段
4. 更新 About 页开源许可清单里的 OSD 字体条目
