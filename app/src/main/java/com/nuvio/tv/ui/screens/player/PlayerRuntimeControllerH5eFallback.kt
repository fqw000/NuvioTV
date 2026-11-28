package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CctvH5eFallback"

/** 进程级单实例播放器，同一时刻只允许一次在途回退。 */
private val h5eFallbackInFlight = AtomicBoolean(false)

/**
 * 央视 h5e 原生解密确定性失败（wasm trap 同段三连）时的自动回退。
 *
 * CctvH5eDataSource 在 fatalDecryptListener 里把失败分片 URI 传到这里；本方法把当前
 * addon 安装链接里的 decryptProxy 翻成 true，重新请求同一 videoId 的 stream，选中同名
 * 档位的代理版 URL 后切过去重播。代理流不再标记 h5eEncrypted，工厂不会再注入原生解密，
 * 因此不会形成回退环。
 */
internal fun PlayerRuntimeController.handleFatalH5eDecrypt(failedUri: String) {
    if (!currentH5eEncrypted) return
    if (!h5eFallbackInFlight.compareAndSet(false, true)) return

    scope.launch {
        try {
            val fallback = resolveH5eProxyStream()
            if (fallback == null) {
                Log.w(TAG, "h5e 回退：未找到代理版 stream uri=$failedUri")
            } else {
                Log.i(TAG, "h5e 回退到解密代理 name=${fallback.name} url=${fallback.getStreamUrl()}")
                switchToSourceStream(fallback)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "h5e 回退异常 uri=$failedUri", t)
        } finally {
            h5eFallbackInFlight.set(false)
        }
    }
}

private suspend fun PlayerRuntimeController.resolveH5eProxyStream(): Stream? {
    val addonName = navigationArgs.addonName ?: return null
    val videoId = navigationArgs.videoId ?: navigationArgs.contentId ?: return null
    val type = navigationArgs.contentType ?: "series"
    val wantedName = navigationArgs.streamName

    val installed = addonRepository.getInstalledAddons().first()
    val addon = installed.firstOrNull { it.name == addonName || it.displayName == addonName }
        ?: return null

    val proxyBaseUrl = withDecryptProxyEnabled(addon.baseUrl) ?: return null
    val proxyAddon = addon.copy(baseUrl = proxyBaseUrl)

    val result = streamRepository.getStreamsFromAddon(proxyAddon, type, videoId)
    val streams = when (result) {
        is NetworkResult.Success -> result.data
        else -> return null
    }

    // 代理版与当前档位同名（addon 只改写 URL，不改 name）；代理流已不再标 h5eEncrypted。
    return streams.firstOrNull { stream ->
        stream.behaviorHints?.h5eEncrypted != true &&
            stream.name != null &&
            (wantedName == null || stream.name == wantedName)
    }
}

/**
 * 把 addon baseUrl 里 URL-encoded JSON cfg 的 decryptProxy 翻成 true。
 *
 * addon 安装链接形如 http://host/<encodeURIComponent(JSON)>/manifest.json；canonicalizeUrl
 * 去掉 /manifest.json 后，baseUrl 的末段路径即该 cfg。cfg 为紧凑 JSON（无空格、无 `!'()*`），
 * Java 的 URLEncoder 与 JS 的 encodeURIComponent 在此字符集上结果一致。
 */
private fun withDecryptProxyEnabled(baseUrl: String): String? {
    return try {
        val queryStart = baseUrl.indexOf('?')
        val path = if (queryStart >= 0) baseUrl.substring(0, queryStart) else baseUrl
        val query = if (queryStart >= 0) baseUrl.substring(queryStart) else ""
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        val cfgJson = URLDecoder.decode(segments.last(), "UTF-8")
        val cfg = JSONObject(cfgJson)
        cfg.put("decryptProxy", true)
        val encoded = URLEncoder.encode(cfg.toString(), "UTF-8")
        val newPath = "/" + (segments.dropLast(1) + encoded).joinToString("/")
        newPath + query
    } catch (_: Exception) {
        null
    }
}
