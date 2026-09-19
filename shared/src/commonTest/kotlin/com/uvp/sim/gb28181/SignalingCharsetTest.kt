package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.sip.SipBuilders
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I-1 信令字符集(GB/T 28181 §6.10)—— 出站声明与字节一致性 + 入站按声明解码。
 *
 * ⛔ 本文件刻意**不**用被测代码的 helper 造 wire 字节:金标字节是硬编码的 GBK 值
 * (「海康威视」= BA A3 BF B5 CD FE CA D3)。用 `encodeSignalingText` 生成期望值再断言
 * 等于 `encodeSignalingText` 的输出,是"自己跟自己印证",守不住任何东西。
 */
class SignalingCharsetTest {

    private fun cfg(gbVersion: GbVersion = GbVersion.V2022) = SimConfig(
        gbVersion = gbVersion,
        server = ServerConfig(ip = "10.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "pwd",
        ),
    )

    /** 「海康威视」的 GBK/GB18030 字节(GB2312 区位码,人工按表核过)。 */
    private val haikangGbk = byteArrayOf(
        0xBA.toByte(), 0xA3.toByte(),
        0xBF.toByte(), 0xB5.toByte(),
        0xCD.toByte(), 0xFE.toByte(),
        0xCA.toByte(), 0xD3.toByte(),
    )

    // ---------------------------------------------------------------- 版本 → 字符集

    @Test fun charset_followsVersion() {
        // 2016 §6.10「宜采用 GB 2312」/ 2022 §6.10「应采用 GB 18030」
        assertEquals(SignalingCharset.GB2312, SignalingCharset.of(GbVersion.V2016))
        assertEquals(SignalingCharset.GB18030, SignalingCharset.of(GbVersion.V2022))
    }

    @Test fun parse_acceptsSameAliasesAsPlatform() {
        // 别名表必须与平台 manscdp.canonicalCharset 对齐,否则会出现"本端声明 GBK、平台不认"
        listOf("GB2312", "gb2312", "GBK", "CP936", "MS936", "WINDOWS-936", "GB_2312-80")
            .forEach { assertEquals(SignalingCharset.GB2312, SignalingCharset.parse(it), it) }
        listOf("GB18030", "gb-18030").forEach {
            assertEquals(SignalingCharset.GB18030, SignalingCharset.parse(it), it)
        }
        listOf("UTF-8", "utf8", "UNICODE-1-1-UTF-8").forEach {
            assertEquals(SignalingCharset.UTF8, SignalingCharset.parse(it), it)
        }
        assertNull(SignalingCharset.parse("BIG5"))
        assertNull(SignalingCharset.parse(null))
    }

    // ---------------------------------------------------------------- 字节层真转码

    @Test fun encode_gb2312_matchesHardcodedGbkBytes() {
        assertContentEquals(haikangGbk, encodeSignalingText("海康威视", SignalingCharset.GB2312))
    }

    @Test fun encode_gb18030_matchesHardcodedGbkBytes_forBmpChars() {
        // BMP 内的汉字在 GB18030 与 GBK 下同码,所以可以拿同一份金标字节守两处
        assertContentEquals(haikangGbk, encodeSignalingText("海康威视", SignalingCharset.GB18030))
    }

    @Test fun encode_utf8_isNotGbkBytes() {
        // 反向锚点:如果哪天有人把 GB2312 映射成 UTF-8(就是本次要修的旧病灶),这条会红
        assertContentEquals("海康威视".encodeToByteArray(), encodeSignalingText("海康威视", SignalingCharset.UTF8))
        assertFalse(haikangGbk.contentEquals("海康威视".encodeToByteArray()))
    }

    @Test fun decode_gbkBytes_roundTripsToChinese() {
        assertEquals("海康威视", decodeSignalingText(haikangGbk, SignalingCharset.GB2312))
        assertEquals("海康威视", decodeSignalingText(haikangGbk, SignalingCharset.GB18030))
        // 拿 GBK 字节按 UTF-8 解 = 乱码 —— 这正是"声明与字节不一致"的现场症状
        assertFalse(decodeSignalingText(haikangGbk, SignalingCharset.UTF8).contains("海康威视"))
    }

    // ---------------------------------------------------------------- 声明改写 / 抽取

    @Test fun rewriteXmlDeclaration_replacesAnyLabelAndKeepsCrlf() {
        val gb2312 = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n<Response/>"
        val asG18030 = rewriteXmlDeclaration(gb2312, SignalingCharset.GB18030)
        assertTrue(asG18030.startsWith("<?xml version=\"1.0\" encoding=\"GB18030\"?>\r\n"))
        assertTrue(asG18030.endsWith("<Response/>"))

        // 已经是 UTF-8 的存量 builder 输出也要能被改回国标口径
        val utf8 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response/>"
        assertTrue(
            rewriteXmlDeclaration(utf8, SignalingCharset.GB2312)
                .startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>")
        )
    }

    @Test fun rewriteXmlDeclaration_addsDeclarationWhenMissing() {
        val bare = "<Response><SN>1</SN></Response>"
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"GB18030\"?>\r\n$bare",
            rewriteXmlDeclaration(bare, SignalingCharset.GB18030),
        )
    }

    @Test fun declaredSignalingCharset_readsLabelFromRawBytes() {
        assertEquals(
            SignalingCharset.GB2312,
            declaredSignalingCharset("<?xml version=\"1.0\" encoding=\"GB2312\"?><x/>".encodeToByteArray()),
        )
        assertEquals(
            SignalingCharset.GB18030,
            declaredSignalingCharset(haikangGbk.let { encodeSignalingBody("<x/>", SignalingCharset.GB18030) }),
        )
        assertNull(declaredSignalingCharset("<Response/>".encodeToByteArray()))
    }

    @Test fun declaredSignalingCharset_ignoresEncodingInsideBody() {
        // 正文里出现 encoding="..." (比如带 CDATA 的描述)不能被当成声明
        val trap = "<Response><Desc>encoding=\"GB18030\"</Desc></Response>".encodeToByteArray()
        assertNull(declaredSignalingCharset(trap))
    }

    @Test fun stripXmlDeclaration_removesAnyLabel() {
        assertEquals("<Response/>", stripXmlDeclaration("<?xml version=\"1.0\" encoding=\"GB2312\"?><Response/>"))
        assertEquals("<Response/>", stripXmlDeclaration("<?xml version=\"1.0\" encoding=\"GB18030\"?><Response/>"))
        assertEquals("<Response/>", stripXmlDeclaration("<Response/>"))
    }

    // ---------------------------------------------------------------- 出站:声明恒等于字节

    @Test fun encodeSignalingBody_declarationAlwaysMatchesBytes() {
        val xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n<Response><Name>海康威视</Name></Response>"
        SignalingCharset.entries.forEach { charset ->
            val bytes = encodeSignalingBody(xml, charset)
            val declared = declaredSignalingCharset(bytes)
            // 兜底分支会在编码器不支持时把两边一起退回 UTF-8 —— 但绝不允许"声明 A、字节 B"
            assertTrue(declared == charset || declared == SignalingCharset.UTF8, "$charset → $declared")
            assertTrue(decodeSignalingText(bytes, declared!!).contains("海康威视"), "$charset 中文丢了")
        }
    }

    @Test fun encodeSignalingBody_nonAsciiBytesAreNotUtf8_underGb18030() {
        // ⛔ 本次修复的核心断言:GB18030 出站的字节里不能出现「海」的 UTF-8 序列
        val xml = "<Response><Name>海康威视</Name></Response>"
        val bytes = encodeSignalingBody(xml, SignalingCharset.GB18030)
        assertFalse(decodeSignalingText(bytes, SignalingCharset.UTF8).contains("海康威视"))
        assertTrue(decodeSignalingText(bytes, SignalingCharset.GB18030).contains("海康威视"))
        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("encoding=\"GB18030\""))
    }

    // ---------------------------------------------------------------- 入站:声明优先、无声明回退

    @Test fun decodeSignalingBody_honoursDeclarationOverFallback() {
        // 对面声明 GB2312(字节真 GBK),即使本端认为该用 GB18030 也按声明解
        val declared = encodeSignalingText("<?xml version=\"1.0\" encoding=\"GB2312\"?><N>海康威视</N>", SignalingCharset.GB2312)
        assertTrue(
            decodeSignalingBody(declared, SignalingCharset.GB18030).contains("海康威视"),
        )
        // 声明 UTF-8 的也照样认(第三方面实现常见)
        val utf8 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><N>海康威视</N>".encodeToByteArray()
        assertTrue(decodeSignalingBody(utf8, SignalingCharset.GB2312).contains("海康威视"))
    }

    @Test fun decodeSignalingBody_fallsBackWhenNoDeclaration() {
        assertTrue(decodeSignalingBody(haikangGbk, SignalingCharset.GB2312).contains("海康威视"))
    }

    // ---------------------------------------------------------------- 跨层:真报文构造

    @Test fun buildMessage_writesRealGb18030BytesAndMatchingDeclaration() {
        val xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n<Response><Name>海康威视</Name></Response>"
        val req = SipBuilders.buildMessage(
            config = cfg(GbVersion.V2022),
            cseq = 1, callId = "c1", branch = "z9hG4bK1", fromTag = "ft",
            localIp = "10.0.0.10", localPort = 5060,
            xmlBody = xml,
            charset = SignalingCharset.of(GbVersion.V2022),
        )
        // Content-Length 必须按**转码后**的字节数算,不能按 String.length(中文一变二等长)
        assertEquals(req.body.size.toString(), req.firstHeader("Content-Length"))
        assertEquals(SignalingCharset.GB18030, declaredSignalingCharset(req.body))
        assertTrue(decodeSignalingText(req.body, SignalingCharset.GB18030).contains("海康威视"))
    }

    @Test fun buildMessage_2016WritesGb2312Declaration() {
        val req = SipBuilders.buildMessage(
            config = cfg(GbVersion.V2016),
            cseq = 1, callId = "c1", branch = "z9hG4bK1", fromTag = "ft",
            localIp = "10.0.0.10", localPort = 5060,
            xmlBody = "<Response/>",
            charset = SignalingCharset.of(GbVersion.V2016),
        )
        assertTrue(req.body.toString(Charsets.ISO_8859_1).contains("encoding=\"GB2312\""))
    }
}
