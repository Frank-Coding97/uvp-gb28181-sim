package com.uvp.sim.recording

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.test.runTest
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFArrayRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreMedia.kCMSampleAttachmentKey_NotSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [IosMp4DemuxSource] that don't need a real MP4:
 *
 * - `isSyncFromAttachments`: synthetic CFArray[CFDictionary] with NotSync =
 *   true / false / absent, verifies the parser returns the correct sync flag.
 *   Prior impl hardcoded `true` for every sample; these tests pin the actual
 *   parse behavior.
 * - `seekTo` without an open asset: falls back to `firstFramePtsUs` and does
 *   NOT arm a pending seek that would later blow up `frames()`.
 *
 * Full MP4 round-trip (writer → open → seekTo → frames) is a real-device
 * validation item (simulator AVAssetWriter can be flaky for H.264). The
 * seekTo return-value contract with PlaybackEngine is already covered by
 * [Mp4DemuxSourceSeekTest] in commonTest via SeekableFake.
 */
@OptIn(ExperimentalForeignApi::class)
class IosMp4DemuxSourceSeekTest {

    @Test
    fun isSync_true_when_NotSync_key_is_absent() {
        val emptyDict = newMutableDict()
        val array = newArrayOf(listOf(emptyDict))
        try {
            assertTrue(
                IosMp4DemuxSource.isSyncFromAttachments(array!!),
                "no NotSync entry must be treated as sync (IDR)",
            )
        } finally {
            CFRelease(emptyDict)
            CFRelease(array)
        }
    }

    @Test
    fun isSync_false_when_NotSync_is_true() {
        val dict = dictWithNotSync(true)
        val array = newArrayOf(listOf(dict))
        try {
            assertFalse(
                IosMp4DemuxSource.isSyncFromAttachments(array!!),
                "NotSync = kCFBooleanTrue must produce isSync = false (P/B frame)",
            )
        } finally {
            CFRelease(dict)
            CFRelease(array)
        }
    }

    @Test
    fun isSync_true_when_NotSync_is_false() {
        val dict = dictWithNotSync(false)
        val array = newArrayOf(listOf(dict))
        try {
            assertTrue(
                IosMp4DemuxSource.isSyncFromAttachments(array!!),
                "NotSync = kCFBooleanFalse must produce isSync = true (IDR)",
            )
        } finally {
            CFRelease(dict)
            CFRelease(array)
        }
    }

    @Test
    fun isSync_true_when_attachments_array_is_empty() {
        val array = newArrayOf(emptyList())
        try {
            assertTrue(
                IosMp4DemuxSource.isSyncFromAttachments(array!!),
                "empty attachments must fall through to sync",
            )
        } finally {
            CFRelease(array)
        }
    }

    @Test
    fun seekTo_without_open_returns_firstFramePts_and_does_not_arm_pendingSeek() = runTest {
        val demux = IosMp4DemuxSource("/nonexistent/does-not-exist.mp4")
        val pts = demux.seekTo(1_000_000L)
        assertEquals(0L, pts, "no asset → seekTo returns firstFramePtsUs (default 0)")
        assertNull(demux.pendingSeekUs, "no pending seek should be armed pre-open()")
    }

    @Test
    fun close_clears_pendingSeek() = runTest {
        val demux = IosMp4DemuxSource("/nonexistent/does-not-exist.mp4")
        demux.close()
        assertNull(demux.pendingSeekUs)
    }

    // -----------------------------------------------------------------
    // CF helpers — construct throwaway attachments arrays for the parser
    // -----------------------------------------------------------------

    private fun newMutableDict(): CFDictionaryRef? = CFDictionaryCreateMutable(
        allocator = kCFAllocatorDefault,
        capacity = 0,
        keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
        valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
    )

    private fun dictWithNotSync(notSync: Boolean): CFDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            allocator = kCFAllocatorDefault,
            capacity = 1,
            keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
            valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: return null
        val boolRef = if (notSync) kCFBooleanTrue else kCFBooleanFalse
        val keyPtr: COpaquePointer? = kCMSampleAttachmentKey_NotSync?.reinterpret()
        val valuePtr: COpaquePointer? = boolRef?.reinterpret()
        CFDictionarySetValue(
            theDict = dict,
            key = keyPtr,
            value = valuePtr,
        )
        return dict
    }

    private fun newArrayOf(dicts: List<CFDictionaryRef?>): CFArrayRef? {
        val array = CFArrayCreateMutable(
            allocator = kCFAllocatorDefault,
            capacity = dicts.size.toLong(),
            callBacks = kCFTypeArrayCallBacks.ptr,
        ) ?: return null
        dicts.forEach { d ->
            if (d != null) {
                val entry: COpaquePointer = d.reinterpret()
                CFArrayAppendValue(array, entry)
            }
        }
        return array
    }
}
