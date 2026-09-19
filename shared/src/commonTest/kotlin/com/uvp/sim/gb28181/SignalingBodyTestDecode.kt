package com.uvp.sim.gb28181

/**
 * 单测侧读「引擎真发出去的报文体」的**唯一正确姿势**。
 *
 * ⛔ 别再写 `body.decodeToString()` 了。那是 UTF-8 硬解,而从 I-1 起出站会按**有效国标版本**
 * 真转码(见 [SignalingCharset.of]:2016 → GB2312/GBK、2022 → GB18030)。拿 UTF-8 去解 GB 字节,
 * 中文一定乱码,断言就变成「Expected value to be true」这种看不出所以然的红 —— 踩过一次了。
 *
 * 这里按报文**自己声明的**编码解,顺带还是一道「声明必须与字节一致」的守卫:
 * 若哪个 builder 声明了 GB18030 却写了 UTF-8 字节(正是 I-1 要根除的老病灶),
 * 这里就会解出乱码、下游的中文断言照样红。
 *
 * 之所以能不给 fallback 也能用:引擎产出的报文体一定带 XML 声明,声明分支必然命中。
 * 真遇到无声明的(手搓 body 之类),再显式传 [SignalingCharset]。
 */
fun ByteArray.decodeSignalingTestBody(
    fallback: SignalingCharset = SignalingCharset.UTF8,
): String = decodeSignalingBody(this, fallback)
