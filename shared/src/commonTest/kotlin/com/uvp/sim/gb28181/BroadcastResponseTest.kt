package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** T1 — Broadcast Response MANSCDP builder (GB28181 §9.8 / §F.2.1). */
class BroadcastResponseTest {

    @Test
    fun okResponseFieldOrderAndGb2312() {
        val xml = BroadcastResponse.build("34020000001320000001", sn = "42", result = BroadcastResponse.Result.OK)
        assertContains(xml, """<?xml version="1.0" encoding="GB2312"?>""")
        assertContains(xml, "<CmdType>Broadcast</CmdType>")
        assertContains(xml, "<SN>42</SN>")
        assertContains(xml, "<DeviceID>34020000001320000001</DeviceID>")
        assertContains(xml, "<Result>OK</Result>")
        // 顺序:CmdType < SN < DeviceID < Result
        val cmdIdx = xml.indexOf("<CmdType>")
        val snIdx = xml.indexOf("<SN>")
        val devIdx = xml.indexOf("<DeviceID>")
        val resIdx = xml.indexOf("<Result>")
        assertTrue(cmdIdx < snIdx && snIdx < devIdx && devIdx < resIdx)
    }

    @Test
    fun errorResponseCarriesReason() {
        val xml = BroadcastResponse.build("dev1", "1", BroadcastResponse.Result.ERROR, reason = "busy")
        assertContains(xml, "<Result>ERROR</Result>")
        assertContains(xml, "<Reason>busy</Reason>")
    }

    @Test
    fun okResponseHasNoReason() {
        val xml = BroadcastResponse.build("dev1", "1", BroadcastResponse.Result.OK)
        assertTrue(!xml.contains("<Reason>"), "OK 响应不应有 Reason")
    }
}
