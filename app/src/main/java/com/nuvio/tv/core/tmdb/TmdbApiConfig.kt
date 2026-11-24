package com.nuvio.tv.core.tmdb

import com.nuvio.tv.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Runtime-switchable TMDB API base URL.
 *
 * [TmdbSettingsDataStore] feeds the user's "TMDB proxy address" setting here; when it is
 * blank, the build-time default ([BuildConfig.TMDB_API_BASE_URL]) is used. Both the app's
 * TMDB Retrofit client and the plugin JS fetch bridge consult this singleton so the setting
 * applies without rebuilding the app.
 */
object TmdbApiConfig {
    private val defaultBaseUrl: String =
        BuildConfig.TMDB_API_BASE_URL.ifBlank { "https://api.themoviedb.org/3/" }

    @Volatile
    private var overrideBaseUrl: String = ""

    val effectiveBaseUrl: String
        get() = overrideBaseUrl.ifBlank { defaultBaseUrl }

    /**
     * Host portion of the effective base URL. Falls back to the build-time default when the
     * runtime override is blank or not a usable hostname (e.g. someone typed just "https" or
     * "https://https/3/"), so a bad value can never turn into a malformed "https://https/..."
     * request URL.
     */
    val host: String
        get() {
            val parsed = parseHost(effectiveBaseUrl)
            return parsed.ifBlank { parseHost(defaultBaseUrl) }
        }

    private fun parseHost(base: String): String = runCatching {
        val trimmed = base.trim()
        if (trimmed.isEmpty()) return@runCatching ""
        val normalized = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val h = java.net.URI(normalized).host.orEmpty()
        when {
            h.isBlank() -> ""
            h.equals("https", ignoreCase = true) -> ""
            h.equals("http", ignoreCase = true) -> ""
            h.contains('/') -> ""
            h.contains(':') -> ""
            else -> h
        }
    }.getOrNull() ?: ""

    /** Empty string resets to the build-time default. */
    fun update(baseUrl: String) {
        overrideBaseUrl = baseUrl.trim()
    }
}

/**
 * Rewrites api.themoviedb.org to [TmdbApiConfig.host] at request time, preserving the
 * `/3/...` path. This is how a runtime proxy setting takes effect without recreating the
 * Retrofit singleton. Requests to any other host pass through untouched.
 */
class TmdbHostRewriteInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val targetHost = TmdbApiConfig.host
        if (targetHost.isNotBlank() &&
            url.host.equals("api.themoviedb.org", ignoreCase = true) &&
            !targetHost.equals(url.host, ignoreCase = true)
        ) {
            val rewritten = url.newBuilder().host(targetHost).build()
            return chain.proceed(request.newBuilder().url(rewritten).build())
        }
        return chain.proceed(request)
    }
}
