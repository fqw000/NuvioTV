package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.nuvio.tv.core.network.TlsSupport
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [BitmapLoader] backed by the app's TLS-enabled OkHttp client.
 *
 * Media3's stock SimpleBitmapLoader fetches artwork through
 * DefaultHttpDataSource (HttpURLConnection) and therefore the platform trust
 * store, which on API 23 lacks the ISRG / GTS / Amazon roots, so artwork hosted
 * on those CDNs fails with "Trust anchor for certification path not found".
 * This implementation routes the fetch through TlsSupport instead.
 *
 * It also implements loadBitmapFromMetadata explicitly: the default method is
 * desugared to an abstract interface method at minSdk 23 and would otherwise
 * reach the JVM as an unimplemented method on older runtimes.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object TlsBitmapLoader : BitmapLoader {
    private const val TAG = "TlsBitmapLoader"
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "nuvio-tls-bitmap").apply { isDaemon = true }
    }
    private val listeningExecutor = MoreExecutors.listeningDecorator(executor)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { TlsSupport.attach(this) }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun supportsMimeType(mimeType: String): Boolean =
        mimeType.startsWith("image/", ignoreCase = true)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        listeningExecutor.submit(Callable {
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IllegalArgumentException("Failed to decode ${data.size} bitmap bytes")
        })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return if (uri.scheme == "file") {
            listeningExecutor.submit(Callable {
                val bytes = java.io.File(uri.path ?: "").readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalArgumentException("Failed to decode local bitmap: $uri")
            })
        } else {
            listeningExecutor.submit(Callable {
                val request = Request.Builder().url(uri.toString()).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} fetching $uri")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty body for $uri")
                    val bytes = body.bytes()
                    if (bytes.size > MAX_IMAGE_BYTES) {
                        throw IllegalStateException("Bitmap too large (${bytes.size} bytes): $uri")
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IllegalArgumentException("Failed to decode bitmap from $uri")
                }
            })
        }
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> {
        val uri = metadata.artworkUri
        return if (uri == null) {
            listeningExecutor.submit(Callable {
                throw IllegalArgumentException("MediaMetadata has no artworkUri")
            })
        } else {
            loadBitmap(uri)
        }
    }
}
