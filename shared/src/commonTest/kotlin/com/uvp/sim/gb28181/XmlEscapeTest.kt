package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals

class XmlEscapeTest {

    @Test
    fun escapesFiveSpecialCharacters() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", escapeXmlText("a&b<c>d\"e'f"))
    }

    @Test
    fun preservesNormalText() {
        assertEquals("Camera-01 入口", escapeXmlText("Camera-01 入口"))
    }

    @Test
    fun emptyStringStaysEmpty() {
        assertEquals("", escapeXmlText(""))
    }

    @Test
    fun closingItemTagIsNeutralised() {
        // 攻击向量:名字里塞 </Item><Item>... 想插入额外条目
        val malicious = "OK</Item><Item><DeviceID>injected</DeviceID></Item>"
        val out = escapeXmlText(malicious)
        // & < > 必须全转,转完不再含原始 </Item>
        assertEquals(
            "OK&lt;/Item&gt;&lt;Item&gt;&lt;DeviceID&gt;injected&lt;/DeviceID&gt;&lt;/Item&gt;",
            out
        )
    }
}
