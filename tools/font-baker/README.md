# OSD Font Atlas Baker

把字体光栅化成 OSD 渲染层用的 SDF atlas(PNG + JSON)。host JVM 跑,**不进 APK**。

## 快速跑

```bash
# 默认:Monospaced(JDK logical font) + ASCII 95 字符
./gradlew :shared:bakeOsdFontAtlas

# 换字体(系统已装的字体名)
./gradlew :shared:bakeOsdFontAtlas -PosdFont="PingFang SC"

# 用本地 .otf 文件(需先把字体放到 tools/font-baker/fonts/)
./gradlew :shared:bakeOsdFontAtlas \
  -PosdFont=tools/font-baker/fonts/source-han-sans-sc.otf \
  -PosdCharset=tools/font-baker/charset/charset-ascii-cjk-sample.txt
```

产物:

- `shared/src/androidMain/assets/osd-font-atlas.png` — 8-bit grayscale SDF
- `shared/src/androidMain/assets/osd-font-atlas.json` — UV + advance + bearing 元数据

## 字符集说明

- `charset-ascii.txt` — ASCII 0x20..0x7E,共 95 字符。**当前 commit 进 repo 的 atlas 用这一份。**
- `charset-ascii-cjk-sample.txt` — ASCII + 50 个 GB2312 高频汉字示例,等老板手工放入合规中文字体后可启用。

## 后补中文 atlas

GitHub raw 下思源黑体/Noto Sans SC 在国内网络不稳。建议:

1. 老板手动从合规渠道(如 [Adobe Source Han Sans Release](https://github.com/adobe-fonts/source-han-sans/releases))下载 `SourceHanSansSC-Normal.otf`
2. 放到 `tools/font-baker/fonts/source-han-sans-sc.otf`
3. 扩充 `charset-ascii-cjk-sample.txt` 到 GB2312 一级 1000 字(`tools/font-baker/charset/charset-gb2312-l1.txt` 待建)
4. 重跑 `./gradlew :shared:bakeOsdFontAtlas -PosdFont=... -PosdCharset=...`
5. 提交新生成的 atlas 资产(覆盖 ASCII 版本)

## 算法说明

- 渲染:`java.awt.Font` + AA 抗锯齿,output 64x64 alpha cell
- atlas 拼图:2048x2048,32 cell/row,32 row,容 1024 字符
- SDF 后处理:8-spread brute-force distance field,O(W*H*spread²) ≈ 1.2 G ops 单跑约 30-60s

## 不依赖网络的好处

整个 baker 用 JDK 内置 `java.awt.Font` + `BufferedImage`,零外部 native 依赖(原 plan 写 stb_truetype,但网络下不到 LWJGL native 包/合规中文字体,改了)。质量对 OSD 用够用。
