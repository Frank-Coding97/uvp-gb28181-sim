package com.uvp.sim.recording

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosRecordingThumbnailTest {

    @Test
    fun thumbnail_path_is_recording_id_jpg_next_to_mp4() {
        val helper = IosRecordingThumbnail(FakeSource(byteArrayOf(1)), RecordingWriter())

        val path = helper.thumbnailPathFor(
            recordingPath = "/tmp/recordings/device/20260702/202100.mp4",
            recordingId = "abc-123",
        )

        assertEquals("/tmp/recordings/device/20260702/abc-123.jpg", path)
    }

    @Test
    fun capture_writes_jpeg_and_returns_path() = runTest {
        val writer = RecordingWriter()
        val helper = IosRecordingThumbnail(FakeSource(byteArrayOf(1, 2, 3)), writer)

        val path = helper.captureForRecording("/tmp/a/b/clip.mp4", "clip-id")

        assertEquals("/tmp/a/b/clip-id.jpg", path)
        assertEquals("/tmp/a/b/clip-id.jpg", writer.path)
        assertContentEquals(byteArrayOf(1, 2, 3), writer.bytes)
    }

    @Test
    fun capture_returns_null_when_source_has_no_frame() = runTest {
        val writer = RecordingWriter()
        val helper = IosRecordingThumbnail(FakeSource(null), writer)

        val path = helper.captureForRecording("/tmp/a/b/clip.mp4", "clip-id")

        assertNull(path)
        assertNull(writer.path)
    }

    @Test
    fun capture_returns_null_when_write_fails() = runTest {
        val helper = IosRecordingThumbnail(FakeSource(byteArrayOf(1)), RecordingWriter(succeeds = false))

        val path = helper.captureForRecording("/tmp/a/b/clip.mp4", "clip-id")

        assertNull(path)
    }

    private class FakeSource(private val jpeg: ByteArray?) : RecordingThumbnailSource {
        override suspend fun takeJpeg(): ByteArray? = jpeg
    }

    private class RecordingWriter(private val succeeds: Boolean = true) : ThumbnailFileWriter {
        var path: String? = null
        var bytes: ByteArray? = null

        override fun write(path: String, bytes: ByteArray): Boolean {
            this.path = path
            this.bytes = bytes
            return succeeds
        }
    }
}
