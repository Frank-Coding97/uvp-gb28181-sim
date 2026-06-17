package com.uvp.sim.snapshot

/**
 * JPEG 本地 cache。
 *
 * 用途:抓拍序列中先落盘再上传,失败保留供调试,成功后由 [gc] 清理。
 *
 * 存储路径(平台 actual 决定):
 *   - JVM:   `<tmp>/uvp-snapshots/<YYYY-MM-DD>/<id>.jpg`(测试用,真实写盘)
 *   - Android:`<filesDir>/snapshots/<YYYY-MM-DD>/<id>.jpg`(T6)
 *   - iOS:    stub no-op(M5+)
 *
 * GC 策略统一:
 *   - 删 7 天前文件
 *   - 总量 > 100MB 时按 mtime 由旧到新淘汰直到 ≤ 100MB
 */
expect class JpegLocalCache() {
    /** 写入并返回完整文件路径(供 NOTIFY StoragePath 使用) */
    suspend fun write(snapShotId: String, bytes: ByteArray): String

    /** 启动时调用一次,清理过期 / 超量文件 */
    suspend fun gc()
}
