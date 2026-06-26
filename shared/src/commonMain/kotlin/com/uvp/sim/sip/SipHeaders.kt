package com.uvp.sim.sip

import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random

/**
 * SIP 公共头工具(saga §3.5 SipBuilders 拆分的 1/4):
 *  - RFC 1123 Date 头生成(GB §4.15 时钟切面)
 *  - GB §9.2 Subject 头模板
 *  - branch / tag / Call-ID 随机生成器
 */
object SipHeaders {

    /** RFC 1123 date string in GMT, e.g. `Sun, 06 Nov 1994 08:49:37 GMT`. */
    fun rfc1123Date(instant: Instant = Clock.System.now()): String {
        val ldt = instant.toLocalDateTime(TimeZone.UTC)
        val dow = when (ldt.dayOfWeek) {
            DayOfWeek.MONDAY -> "Mon"; DayOfWeek.TUESDAY -> "Tue"; DayOfWeek.WEDNESDAY -> "Wed"
            DayOfWeek.THURSDAY -> "Thu"; DayOfWeek.FRIDAY -> "Fri"
            DayOfWeek.SATURDAY -> "Sat"; DayOfWeek.SUNDAY -> "Sun"
            else -> "???"
        }
        val mon = when (ldt.month) {
            Month.JANUARY -> "Jan"; Month.FEBRUARY -> "Feb"; Month.MARCH -> "Mar"
            Month.APRIL -> "Apr"; Month.MAY -> "May"; Month.JUNE -> "Jun"
            Month.JULY -> "Jul"; Month.AUGUST -> "Aug"; Month.SEPTEMBER -> "Sep"
            Month.OCTOBER -> "Oct"; Month.NOVEMBER -> "Nov"; Month.DECEMBER -> "Dec"
            else -> "???"
        }
        fun p2(v: Int) = v.toString().padStart(2, '0')
        return "$dow, ${p2(ldt.dayOfMonth)} $mon ${ldt.year} ${p2(ldt.hour)}:${p2(ldt.minute)}:${p2(ldt.second)} GMT"
    }

    /** GB §9.2 INVITE Subject 头模板:`{senderId}:{ssrc},{receiverId}:0`。 */
    fun subject(senderId: String, ssrc: String, receiverId: String): String =
        "$senderId:$ssrc,$receiverId:0"

    fun randomBranch(): String {
        val alpha = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "z9hG4bK" + (1..16).map { alpha.random(Random) }.joinToString("")
    }

    fun randomTag(): String {
        val alpha = "abcdef0123456789"
        return (1..10).map { alpha.random(Random) }.joinToString("")
    }

    fun randomCallId(localIp: String): String {
        val alpha = "abcdef0123456789"
        val rand = (1..16).map { alpha.random(Random) }.joinToString("")
        return "$rand@$localIp"
    }
}
