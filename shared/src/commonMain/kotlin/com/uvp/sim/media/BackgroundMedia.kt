package com.uvp.sim.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * 当前直播媒体应发送的来源。
 *
 * [Inactive] 只能由没有活跃 INVITE 的调用方得到，不能据此发送 RTP。
 * [Placeholder] 表示平台仍持有原 dialog，但真实相机/麦克风已停止，媒体链应发送
 * [backgroundVideoFrame] 与 [backgroundAudioFrame]。
 * [AwaitingKeyFrame] 用于回到前台后：保持占位直到相机产出可独立解码的真实关键帧。
 */
internal enum class BackgroundMediaMode {
    Inactive,
    Live,
    Placeholder,
    AwaitingKeyFrame,
}

/**
 * 直播媒体的跨平台前后台状态机。
 *
 * Controller 不创建 SIP dialog，也不判断注册状态。Coordinator 只在真正存在的直播
 * INVITE 上调用 [onActiveInviteChanged] / [enterBackground] / [enterForeground]，从而保证
 * 单纯注册时不会开始 RTP 占位流。
 *
 * 生命周期回调与 Coordinator 的活跃流更新应由同一个串行 app/engine scope 调用；渲染和
 * 媒体发送端只读取 [mode]。
 */
internal class BackgroundMediaController {
    private val _mode = MutableStateFlow(BackgroundMediaMode.Inactive)
    val mode: StateFlow<BackgroundMediaMode> = _mode.asStateFlow()

    @Volatile
    private var appInBackground = false

    @Volatile
    private var activeInvite = false

    /**
     * 活跃直播 INVITE 的建立/结束通知。
     *
     * `active=true` 在前台首次建流时进入 [BackgroundMediaMode.Live]；如果回前台后已经
     * 在等待真实关键帧，重复的 active 通知不会绕过这道 gate。
     */
    fun onActiveInviteChanged(active: Boolean) {
        activeInvite = active
        _mode.value = when {
            !active -> BackgroundMediaMode.Inactive
            appInBackground -> BackgroundMediaMode.Placeholder
            _mode.value == BackgroundMediaMode.AwaitingKeyFrame -> BackgroundMediaMode.AwaitingKeyFrame
            else -> BackgroundMediaMode.Live
        }
    }

    /**
     * App 进入后台。调用方传入此刻是否仍有活跃直播 INVITE，而不是 SIP 注册状态。
     */
    fun enterBackground(hasActiveInvite: Boolean) {
        activeInvite = hasActiveInvite
        appInBackground = true
        _mode.value = if (hasActiveInvite) {
            BackgroundMediaMode.Placeholder
        } else {
            BackgroundMediaMode.Inactive
        }
    }

    /**
     * App 回到前台。仍有活跃直播时，先等待真实关键帧，避免解码器收到半个 GOP 后花屏。
     */
    fun enterForeground(hasActiveInvite: Boolean) {
        activeInvite = hasActiveInvite
        appInBackground = false
        _mode.value = if (hasActiveInvite) {
            BackgroundMediaMode.AwaitingKeyFrame
        } else {
            BackgroundMediaMode.Inactive
        }
    }

    /**
     * 真实相机编码器产出关键帧后调用。后台期间的迟到回调不得恢复真实画面。
     */
    fun onRealVideoKeyFrame() {
        if (activeInvite && !appInBackground && _mode.value == BackgroundMediaMode.AwaitingKeyFrame) {
            _mode.value = BackgroundMediaMode.Live
        }
    }
}

/**
 * 生成“视频画面暂不可用”占位视频关键帧。
 *
 * 资源是离线生成的 320x180 Annex-B poster，内含中文提示和英文辅助文本。它携带参数集和
 * IDR/IRAP，因此新加入平台的解码器也能立即显示；调用方仍需在实际媒体时钟上提供
 * [timestampUs]，以保持 RTP 时间戳连续。
 *
 * 每次都复制 NAL，避免下游 muxer / sender 的就地操作污染下一个占位帧。
 */
internal const val BACKGROUND_VIDEO_MESSAGE = "视频画面暂不可用"

internal fun backgroundVideoFrame(codec: VideoCodec, timestampUs: Long): H264Frame = H264Frame(
    nalUnits = when (codec) {
        VideoCodec.H264 -> H264_POSTER_NALS
        VideoCodec.H265 -> H265_POSTER_NALS
    }.map { it.copyOf() },
    timestampUs = timestampUs,
    isKeyFrame = true,
    codec = codec,
)

/**
 * 生成静音音频帧。G.711 每帧 20 ms；AAC 是一个 AAC-LC raw access unit，采样率由已协商
 * 的 AudioSpecificConfig 决定（当前配置仅允许 8 kHz / 16 kHz AAC）。
 */
internal fun backgroundAudioFrame(codec: AudioCodec, timestampUs: Long): AudioFrame = AudioFrame(
    payload = when (codec) {
        AudioCodec.G711A -> ByteArray(G711_SAMPLES_PER_FRAME) { G711_ALAW_SILENCE }
        AudioCodec.G711U -> ByteArray(G711_SAMPLES_PER_FRAME) { G711_ULAW_SILENCE }
        AudioCodec.AAC -> AAC_LC_SILENCE.copyOf()
    },
    timestampUs = timestampUs,
    codec = codec,
)

/**
 * 占位音频下一帧应推进的媒体时间。AAC-LC 每个 raw access unit 固定 1024 sample。
 */
internal fun backgroundAudioFrameDurationUs(
    codec: AudioCodec,
    sampleRateHz: Int = codec.sampleRateHz,
): Long = when (codec) {
    AudioCodec.G711A, AudioCodec.G711U -> G711_FRAME_DURATION_US
    AudioCodec.AAC -> {
        require(sampleRateHz > 0) { "AAC sample rate must be positive" }
        AAC_LC_SAMPLES_PER_FRAME * MICROS_PER_SECOND / sampleRateHz
    }
}

private const val MICROS_PER_SECOND = 1_000_000L
private const val G711_SAMPLES_PER_FRAME = 160
private const val G711_FRAME_DURATION_US = 20_000L
private const val AAC_LC_SAMPLES_PER_FRAME = 1_024L
private val G711_ALAW_SILENCE = 0xD5.toByte()
private val G711_ULAW_SILENCE = 0xFF.toByte()
private val AAC_LC_SILENCE = byteArrayOf(0x01, 0x18, 0x20, 0x07)
private val H264_POSTER_NALS = AnnexB.splitNals(
    decodeBase64(
        """
        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
        FaxWsVrFaxWsVrFaxWsVrFa3WK1usVrdYrW6xWt1itbrFa3WK1usV/D+h1hQEXR8CBmk4bcz+l6VjXabK7MhWMdvVdqAHUXM9D81waSpmzDt04CJGEMFXcVX
        BnF+m7N+eD2rcRW3/1sk4AEvEkRjkCONZW8LgF8yZoCE/ATiqxgTeRrtlk//tQqqx1OpW4F9/3jJzEhjroVj/JE2tQvqq26YBKXoy19AvKkFNj/+J9CCM8AA
        ObEDGJsw4QtltQGCDmnOuR794eSD4eEdqhLT3imOXmIT7WQBAjhSktr82Ey+wz/uwHO2E+E3KXCJKf3kg2AhzkvZbgK3rgwibzSgHf3/cPgrUs8NsssVrFa3
        WK1usVrdYrW6xWt1itbrFa3WK1itYrWK1itYrWK1itYrWK1itYrWK1itYrWK1it4reK1it4reK3itYrWK1itYrWK1itYrWK1itYrWK1itYrWK1itYrWK1itb
        rFa3WK1usVrdYrW6xWt1itbrFa3WK1usVrdYrW6xWt1itYrWK1usVrdYrW6xWt1itbrFa3WK1usVrFaxWsVrFaxWsVrFaxWsVrFaxWsVrFaxWsVrFaxWsVrF
        axWsVrFaxWsVrFaxWsVrFaxWsVrFaxWsVrFaxWsVrFaxWsVrErC3WJWFusSsLdYlYW6xKwt1iVhbrErC3WJWFusSsLdYlYW6xKwt1iVhbrErC3WJWFusSsLd
        YlYW6xKwt1iVhbrErC3WJWFv
        """
    )
)

private val H265_POSTER_NALS = AnnexB.splitNals(
    decodeBase64(
        """
        AAAAAUABDAH//wQIAAADAJ+oAAADAAA/ugJAAAAAAUIBAQQIAAADAJ+oAAADAAA/oAUCAWllupJG2vAWgIAAAAMAgAAADIQAAAABRAHBcrBiQAAAASgBr1MM
        AnLhk6WzFtj5D+f/9wV3yTvI0m3Nx7O7fCt2cDaKgUUQ2a6BlDd1Htc/sitdc06UpeoYoibEE8+Tf6pL0iB/I3Wa3ya3dYswEiSk0KUlc4iMwLNoAjazVKhW
        e3M29uXjvomkaDVzh/VZwLvsjZuXQu80JN6P3dtKbtrCGSTfIhmKN0bgTUG91fBCbb4QfrGlDO16YPRwJ9qbb21daRZglmH855GFMbErl8Av5tMewQMjTU/X
        qMkrYgDdLD5K/sZMzufex+Iekhhlk15oej8fgenKqBvB4dJ/WFc1XPfHflq9kftUlq8Dcr27OJ9DVzrwKpx/b9K7rQzf4CflasrpTCnhpjLhO9YISotd3nHT
        tBjc3aQ3USPDigK57X8FXZjBif8JJoaMTGBEWEBZTqEeQWF3aDQsP6lZdU3zayFUorv0hDZrD3Ni7zKfsL3zvaaDJ8dm9TX5H0yzvEGBYuh+f+qkmCkVgaRp
        sj4fHHhmVMtJbj/wWA6Zp03q9o44Y4mreqXFfz8TxrrejZZwb7CaoUOICfuxwK07pMdvYwhnIfQbssIks0ZGWD4ov1cj5KFJRRYHEXmaIF8h1myWa5REYqzc
        R5F/ykiAibyk3HT4i2IvWDrylfT98InTTxPuO8QISynsGxYF0aknTTagoGsF852Cm2QJ3hv+ZpVYA2pZz2V5JqzSoPy1vnVMRnkp2bz4YLb8SVavyYxzqxPN
        Xh+ZlOJ7o24lSeSTjBkOk+nVU8QctAHBPpXFen6gg4Of7+hRlP5WpOXgmfzNXy1QlVuamc++KJNuwk5lWVWI9sf37sXv/6yudpmmIeNQJxg+ONTgn3vym+uV
        ij2wWgAo6Pv6/mrk4ZE5ljFTs1vfARk0juQhlvnLx4GtC0CUfhLvzpJXCWFcq+NByjtdNuMZdAUQADGKP7arr5Jc3qNOBbJPrf/3TgUQkXMy/JlNqtRlardg
        NGc0opmR/KlqSwLnKBa0M2ZDiERvFKnKUviS7gzMIHpVhgnLWE2itQ0w0lSrAkDRW5t5adzSMHei8uEAH8AAAAmInW89bpCb5i6k5YyAJAqqHckJOCfl6b+h
        UfFYtm21Qh67hsJJJ78TmAGz4uCunGrnLwDe0yPM8ITVE8QfHXS54qg8kQOsMzK44Eo/snAfbJ21W45YrO5Tkqnt8Qci/EfanKeWnPx0m3L231kOqdHkxotU
        2HmuL93mm4JwgpN28sGhwboL190lcJ1H3o89cRDq1/ejSPhSXjSa73ib6C8L2LZVu5VFKhsr8jbmzthCf4GpZcqGGovord/hUl1IlED1WUVato96eqIvrB37
        lkzs0sWbO4OawxBhSnAW0yK0GCcZzWA+FX4Z/fR2gwzf8MMklm9uz0pAALep2CJnYs4zgwOlFrjyWD9XGtSNTofRrUbxEGjVftB+mzuiBNgvRR+LVvPRXb/k
        YGm9XVUsRsBTgmvT+JFFBTyaC9wPtfFdCzRql9AE3Px6o4sEnDORUk0sHnbY95lNnyVtbGJkureBjVwm83WorXigQpE0G8GjbttDrKdnnWHjsY8p26TdZcpH
        eZpHrZAEOXxCUBnaCybW3wU7ie4WpJpZgd2XRO3Ojtzg+6kFZncSLmREOWZOoIWIB1eIftvveI1OcpXHGCdrmaQpSWaACPqbyDit/aX4R88eK/fPjqXOeO0V
        lHN3O6Pitd5q0AFswJXWCsfbXxTXroipaB0ZMnU7JW30B5q8LHW+KfB6xmIxMfAs5bFXqE4+f7ADB8iTpEi9iPFXv7e4b8p5prwO/zTsIzwQWocxqSWT3/S3
        ASv+j6ZdJjnKoGreekqXOmLM2NKE1gGXqsEfJGtwo+XV3CagdG8u4pn0D/uLRi6kgz6Zk3HDpv+edl9o2sdBQ1Ltk/PLmhEcxA0EjfnpF6aT9GTynmopw73j
        D7k7negQyZcR4pGXYYIXSRahcGZpEuHew4pNrVpKxQq6cHUW64I5dWq13rh+ZsJZB+WarXYCJLLJX0ZNeC6Oyf/XsKxZimiWgbphE6j065lspLZTK5kctFP9
        fZGTkTWhsAI9x1a3DO+tL9S5Lnj5hPOw+TtUN+rYAitce9h8AGBZGgxZ5JBahwHQ3yK4KoWXFe741UZrtHXNnIW5Y6w+cgnXbixw7KFA0JTUkVAPSGxij8PK
        K8JU+iOdvNj5gm3dvs+72GP3q1IX+gz8Sszi4GpYtn7XPNfjtSRQWzDmlG6wZDHCvPSPktPkzl8fI5OHz/OhxWZhCAUHDgizkfWWWxwa/vx92jNfvKIA73be
        k8rQlC6xIG2ueXp5fcnFJCqdSfsGUURTWrAazv82QOgmPspEVshPn66BJhec57ZEUQeSE9Fz0uiW5zB2k3KwG0O7D7wOWJACVnAi+CHeFswOp4jVu1426q8u
        rQ70+kT6E4QDnKnmAoUeEStzLkv2LNYqDEh29NJdl5DbnS9jw2r0YcavLK/AKhNTHBA5AHoXGCoZXoq4DXDja5M8beqyjanrlKvNTN9Sxc2eobbQ/r4vcgR/
        7mPpUSYWg4wMtQ+w0RpBDvKb7O8nhvvwvpNJ59MzGXqzvebKoG9/nWPux2XUmbBPuaLIwzJOZseRQm6IalExYjgdQY3PctJ/SleLe09dRjbFY3J96kfFDpMO
        VIEKbakoLHoPPW/cEWU2d8CNuyJl2Jygr356i2iSN2LEm8S9kHcTs17JIC1hfCsfZwUacj1+aMPnjlNIda/pXMBTA3umn7F3YqGtZRznMlTm/wqA/V4GO6qR
        xmvTxW+XOuJKoxpvnIPNZjwX+ZLeze0xhWC319IXdslpLJ3mOIuVve0sfns4duBG1vtZLwzPZbhKxy8U4CNh0Nk3+cjIRaioKcXAlOzK8JFrY/Z5ANLYBC1f
        7H1358sCKJ33YBe82GUMRmYlURBRR9bSCZ8Au5wL1K5M2x6S3p6EbuCDO54daNzcXLz/ork2HgCUdELsx5Npfxurq4mFmHYmxiTOc27UtOPdvQtvhC45ZNnY
        LgcDJBkR3M+k4od+hWiiOda4KKE2KCahK41NFuv/biWPT4ngZSCgEe3eY7RsqtlBiHbPAeDkJ0pTP6lx/+WKa24f/hShv9UBbgXe2c5Bp6YP+YDJQrKe2X+G
        HB2/7DCQYvJIh5Vr3Z9FwWgUVaWUcWgdoaKEq52mRXVe6h3IHBUnIziNECnmt9fV5pa5chLCXpUWpCjiK/Mof2W9eGWR59k2H0N0QtJQvKoB4H3ZbfoKIC0D
        OqjlWUncwpgO963AM3+OTzijGfhC1jCKw8KjijqTuzUts0UU+lUtiq2XQRdIqiKNDDYWNSPJxnJf6/61zSJW74KJiJCNZH0B5hRDPept4L+q+llLDPzzb8L+
        u2XUr5LnGMtgLzwtnnaxYzg/ATwQQekK6ZcdYP50Os+nCWwzKKUeNTPqCn9WVwACA9VLF0Asc+qv65mxNop8dMKHeHYmCSjQGamVVn2FH45ZJKC8eef0zw/m
        9adPs3u7bJEatmuxg13A42R464AZWFDW5/Tiq4kkARZbget0GeL2seflxhPUIaVY594AORjqZ3D+rYSk9D/TlIp4jN3ZoheZph+nsrfdvqnrbqGNK8/UQA7i
        dLfydPBgLKyEGHvoUXKGC79dSkIAZ3JZVvWMe3Rba0S/Z5rAN3SAEkWiZL3k9MaXzDmkGZgBB42d369Z5lvnrzzg3/X1oxzIpeBea0h7fkwmOwX4aws8Vs8K
        jgKKmJ8sVdSdNvDQSOb8bf0QhtHfqEkFItrtOX9mW3/jcMMbnVUmcABZeCnBwwyb9AbzcNbZ4kRUobtnfo69n3nKWGfAzH9v6jUT4IY++eUYLIlqvNMIM/Tq
        3XKl2xwZubP9Hy7uZQqAxUvtqexekPRBzL8FfNc+dyaa1e3NIVd8aQNuMT3dDV1YcLTfo8/rX3zdtYxeXJjb0IO4AzFPvvepYvq7NRyw9zPNidVAdLYfn5Z0
        GuUZqXfhg/PJK6bC6HpRmTx8KlFX7vtlPsQ8QQujKzQGdhsDJJe/0oQr5AxCI//ehLlauHcAt4GAs0Y4V8cLJ54WgsFTgh1TF1RZqhrrPqM55HKV7Nyjs007
        Y8hgQrd6pfhEjirWUQv4hN/BLah2xk4ckpoqdpudTPzL6GiJ+kIMQ/RbME1EW9vyWAKlwDhY8EN0rUkiH9F/XttaEAoW62K1j0mtBbthSy9jgAAAFAAADgib
        HL0jPXwPyr7tlaJHFViuEsaoUL0k7/J3QhkjU3jcsr35ISnxJlLXXyT1dZtxnTitHwLsEPVXq4kmDR9CJ3nLISzluBiqA67yV9+Ck521SUSWHwUFKR7hOs8L
        LFVc0Uuh778f5GLWW/nOPIRpOYNI4tHRAqvE402/kvhrpFpBMU9E2XSdogJ6nB3j9yrAA+6mr8f2EO6NB9KXnDFH/KYu23jo/kCU8mlCIegVRSKpyu2eL7+u
        lPuENaG/liGcgL77f/5b3hMo9gTlPWR4Qvl6JiHTbipH0iaRgxOMUrYPgXwH7oqTEZOpGOTPDHciR9xzW2xDPW+IorErvxK71A5wifyST1SxZYr30XgAyufX
        BzijrziIIbXW0S3/O0upblQgV3B9/thE9/A3jAmSuRJ400HpfElJCXTNA+sz64Iy4e8EkyfXir3lPppy093cmm56hZEKb02xQeqteQ4hzbSZqCWLHFb21mFj
        UmecZKegX/X46aeZfAZArR1sNm+LBWkkP3T024Q1/vmW7eHTc0/NMsD1InBsymWnkn6uEeWKYYy80N8iZUR1HnAc8LsIw8x/XRkmKsVysQDLZ42MoySK93lX
        OJn1U4YMHLjnPXprsAFxl8hYo3ALdB8QUpeS07LtODkhfXWJC4roYoBQZHeKHNX6S5sr7WvBJHmz14tjFF8YnGphuzWoO965yHW5UE1CBhRkIAu85sNPfAkS
        nTCs0IK3e6yWwZW0JfLjy5AhaB+IAVwNjO77QaJQJGJvs4hHjm5dU5LDjq5JxcR2ZY/OB7dNdXVsWnWzGEWgXSUG78C5tmHyF8esnJjMkFBNOHnfTuj5GFrg
        lpAuq0i/ctWfmgX+tgmDN0ERaF97bg3x58g10rddr72YQhI0IlwzLeTdwKd9p93zyTnAqwZwvq0CBTrtr3EeRYoKesEYxZ3RMkbon32ZpQHGxIDZyMR2IyJH
        l2GO4LjnHpMaq1EFewk7rqV+hyIENb+T+SewbpYkFOlVmWxX8AzuM6r76KEW2hQFF2f9MGw0q+2x4Dxw4K8jecnGcWH+1XM5u7G4J5YwQtriaqAOAHZW1XxJ
        OhfWxFf/dxwW5mO7Q8GgNiGziqgzj0Me7bdCGSwcKMWOot78Q/i3Ov+mOKblJGdOso6hIXuoTaGh9ILazWRpUQyWZjdpV8LJS/1Y2Menx6xLvchRrV5OyC95
        +anDdhE1xR6Ot9skkNGLv0QNWqFO7ClmoPoywSUVsTa7xoVaBV17yaz0DUjdN+BkV6Mp/V7gwBI4i2RSJJxTAItc43jKFaazags8pun7TqnzppdSY8TKAkfW
        HipAm7HUKSdIyeGPCZZoHFtLz2xZ2kH4wKxUICReacNkHFrxVy+a7O9ggS9S6hS9zu+dXd2B71ZBrdOE9gRUOfWyGt9yEPYK+tZsyg4yp9eubE19H3zHj/yj
        vYy4+XBGetCk5zwiJVPG09iEoBoNAyl1zBdmDk+XO6g/u7t8bJn12MMXqT/Y9x+nyhBIHX1Q0OwqmjfxqSLcx7ANQ/4hnts9YnwZAstaUUeE/im7B1vqpMBH
        tLFAnhd3FgG2AwAb1w2D7Dc00rXlw0bpOfxJuzCoEaoF32UM1vJ8zff4hm2DQoOLmZJnifPtckilweOQrfZBHrrdgpWikDn1pnRFTXzMfXPZjXgSjz4giNaW
        OBaqrBuVAFyshAm8UHtAn9qTPxaYfXsFbJm+pG51RP6EasvjRMHXcKS70Wlcz5+rqXpiR5CvbNkHbY8IYfKgRT72HVh/D+BVuosuVV6KBrhl8RZ9lb5B6mQ7
        MYkO6zeB1lljBWANsM8+a60zSChnRZrGKHfkVL3wap/Ha7gI2VNofAgFuQshI+OcI6c6zgf4DHcGdnh6MbRf0hgna8UiGZ+rXVBit3YKqYSnIaCQBGiF5dnC
        pg9SyAAAAwAAAwAAAwAAAwAPCCEpVA2G2/Pkbnyqo7iJUYSHq/qx4bymSAHYEdyjtr54JY3FXn9P9rbVUTeEOy9FPM4bog361moJfCxEzVoMa21M+7ors86Q
        2oaRlThcuxYbtMCLqOiTohhpvViwDWDpUZhocueCRh0opjodwvwADdyAE8PndhAzGBRr/d8i7jwr8xyD59dNqTSOTbr7gCVZyYtOI2Dm21U7U6UUITQ9iGz6
        dzNu5A4o3hAELnEGFohsb5729kZSlzWYheSW/ydxkC8quTNnPfzeaNpQFxKD4IhG66yOer4epBMvQ9Q+G8EwkL7IPWyJxyCCyiea5hhNkGZB4GvwIR67b3Sm
        scTZbzXvO9jlQwZtpxK1QwztOvhNkhk4PbER/MrzhnVZ8fXvMYsCW+5jEzWE6d7iu+QaG4iykzKyDy2HMoLEzSqdS/EpWeNjbsPYn+TJTO1rfsM4fGW+lX1Q
        HC+YI+1XlwAAAwAAAwAAAwAAAwAAAwAQUDKunDQ43sFRnnG97lQAAglkAgQjSBNkKQIo46C63rBTxRA4FjBpwA==
        """
    )
)

private fun decodeBase64(text: String): ByteArray {
    val result = ArrayList<Byte>(text.length * 3 / 4)
    var bits = 0
    var buffered = 0
    for (char in text) {
        val value = when (char) {
            in 'A'..'Z' -> char.code - 'A'.code
            in 'a'..'z' -> char.code - 'a'.code + 26
            in '0'..'9' -> char.code - '0'.code + 52
            '+' -> 62
            '/' -> 63
            '=' -> break
            else -> {
                if (char.isWhitespace()) continue
                error("invalid Base64 character")
            }
        }
        buffered = (buffered shl 6) or value
        bits += 6
        while (bits >= 8) {
            bits -= 8
            result += (buffered shr bits).toByte()
            buffered = buffered and ((1 shl bits) - 1)
        }
    }
    return result.toByteArray()
}
