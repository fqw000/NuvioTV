package com.nuvio.tv.core.cctv

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 央视 h5e 加密流解密 DataSource（MiTV4A 移植，源自 qianxuntudou-ops/MyTVB）。
 *
 * 包装上游 HTTP DataSource：open() 内整段读入响应体，识别 TS 传输流
 * （首字节 0x47 同步字 + 188 字节对齐）则经 [CctvH5eDecryptor] 原生解密后回放；
 * m3u8 清单（文本）与非 TS 资源原样透传，无需按 URL 后缀分流。
 * 分片级解密在 native 侧毫秒级完成，read() 只从内存缓冲出。
 *
 * 本类只在 behaviorHints.h5eEncrypted=true 的 stream 上被工厂注入，
 * 普通流完全不经过它——这就是"默认关闭"的语义。
 */
class CctvH5eDataSource(
    private val upstream: DataSource
) : DataSource {

    companion object {
        private const val TAG = "CctvH5eDataSource"
        private const val TS_PACKET_SIZE = 188
        // 同一分片解密失败容忍次数：超过说明该段确定性解不出（wasm trap），
        // 抛非 IOException 让播放器作为 source error 尽快上抛，避免无限快速重试。
        private const val TRAP_FAIL_LIMIT = 3
        private val trapFailCounts = HashMap<String, Int>()
        private val decryptOkCount = java.util.concurrent.atomic.AtomicInteger()

        /** 换集/换会话时清零。 */
        fun resetTrapFailures() {
            synchronized(trapFailCounts) { trapFailCounts.clear() }
            decryptOkCount.set(0)
        }

        /** HLS 解密链工厂：包装上游 HTTP 工厂（Referer/UA 等 header 由上游配置）。 */
        class Factory(
            private val upstreamFactory: DataSource.Factory,
            var fatalDecryptListener: ((String) -> Unit)? = null
        ) : DataSource.Factory {
            override fun createDataSource(): DataSource =
                CctvH5eDataSource(upstreamFactory.createDataSource()).apply {
                    onFatalDecrypt = fatalDecryptListener
                }
        }
    }

    private val transferListeners = ArrayList<TransferListener>(2)
    private var decryptedBuffer: ByteArray? = null
    private var bufferOffset = 0
    private var openedUri: Uri? = null
    private var onFatalDecrypt: ((String) -> Unit)? = null
    // 上游 close() 后会清空响应头；HlsParameterSetInitDataSource 在 open 返回后读取，
    // 故在 close 前快照一份，避免靠 Content-Type 识别的播放列表拿到空头。
    private var cachedResponseHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        openedUri = dataSpec.uri
        upstream.open(dataSpec)

        // 整段读入（HLS 分片响应有 Content-Length；流式兜底读到 EOF）
        val output = ByteArrayOutputStream()
        val scratch = ByteArray(64 * 1024)
        while (true) {
            val n = upstream.read(scratch, 0, scratch.size)
            if (n == C.RESULT_END_OF_INPUT) break
            if (n < 0) break
            output.write(scratch, 0, n)
        }
        var bytes = output.toByteArray()
        cachedResponseHeaders = upstream.getResponseHeaders()
        upstream.close()

        if (looksLikeTransportStream(bytes)) {
            val started = android.os.SystemClock.elapsedRealtime()
            val decrypted = try {
                // 点播专用入口：player_container_player tag + NAL25 门控，
                // 未加密老剧段原样透传。
                CctvH5eDecryptor.decryptVodTransportStream(bytes)
            } catch (t: Throwable) {
                Log.e(TAG, "H5e 解密异常 uri=${dataSpec.uri.lastPathSegment}", t)
                throw IOException("H5e decrypt failed: ${t.message}", t)
            }
            if (decrypted == null) {
                val key = dataSpec.uri.toString()
                // 不用 Map.merge（Java 8 默认方法），避免依赖核心库脱糖，确保 API 23 直接可用。
                val fails = synchronized(trapFailCounts) {
                    val next = (trapFailCounts[key] ?: 0) + 1
                    trapFailCounts[key] = next
                    next
                }
                val reason = runCatching { CctvH5eDecryptor.lastDecryptFailureReason() }.getOrNull()
                Log.e(TAG, "H5e 解密返回空（trap/会话未就绪）uri=${dataSpec.uri.lastPathSegment} " +
                    "fail#$fails ok=${decryptOkCount.get()} native=${reason ?: "unknown"}")
                if (fails >= TRAP_FAIL_LIMIT) {
                    // 确定性失败：抛非 IOException 让 media3 作为 source error 尽快上抛，
                    // 并通知宿主回退，避免无限快速重试。
                    onFatalDecrypt?.invoke(key)
                    throw RuntimeException("H5e decrypt rejected segment repeatedly: $key")
                }
                throw H5eDecryptException("H5e decrypt rejected segment")
            }
            val cost = android.os.SystemClock.elapsedRealtime() - started
            Log.i(TAG, "H5e 分片解密 ok#${decryptOkCount.incrementAndGet()} " +
                "size=${decrypted.size} costMs=$cost")
            bytes = decrypted
        }
        decryptedBuffer = bytes
        bufferOffset = 0
        return bytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = decryptedBuffer ?: return C.RESULT_END_OF_INPUT
        if (bufferOffset >= data.size) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length, data.size - bufferOffset)
        System.arraycopy(data, bufferOffset, buffer, offset, toRead)
        bufferOffset += toRead
        return toRead
    }

    override fun getUri(): Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> = cachedResponseHeaders

    override fun close() {
        decryptedBuffer = null
        bufferOffset = 0
        openedUri = null
        runCatching { upstream.close() }
    }

    private fun looksLikeTransportStream(bytes: ByteArray): Boolean {
        if (bytes.size < TS_PACKET_SIZE) return false
        if (bytes.size % TS_PACKET_SIZE != 0) return false
        if (bytes[0] != 0x47.toByte()) return false
        if (bytes.size >= TS_PACKET_SIZE * 2 && bytes[TS_PACKET_SIZE] != 0x47.toByte()) return false
        return true
    }
}

/** 确定性解密失败：抛非 IOException 让 media3 作为 source error 尽快上抛。 */
class H5eDecryptException(message: String) : IOException(message)
