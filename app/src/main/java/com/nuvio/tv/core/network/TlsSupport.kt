package com.nuvio.tv.core.network

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Adds modern public root CAs to the platform trust store at runtime.
 *
 * Android 6.0.1 (API 23) ships a 2015-era system trust store that predates
 * Let's Encrypt's ISRG Root X1/X2, the 2025 "Generation Y" ISRG Root YR/YE and
 * the Google Trust Services / Amazon roots issued afterwards, so HTTPS to those
 * endpoints fails with "unable to find valid certification path to requested
 * target". Network Security Config's `<trust-anchors>` only takes effect on API
 * 24+, which is why the XML-only approach cannot fix API 23. This object builds
 * an [X509TrustManager] from a single KeyStore that contains BOTH the platform
 * anchors and the bundled roots, so Conscrypt's path builder can resolve
 * cross-signed chains (e.g. the new Let's Encrypt YR2 intermediate cross-signed
 * to ISRG Root X1) and exposes it for every OkHttp client in the app.
 */
object TlsSupport {
    private const val TAG = "TlsSupport"

    /** Raw resources bundled with the APK. Keep in sync with network_security_config.xml. */
    private val bundledCaResources = intArrayOf(
        R.raw.isrgrootx1,
        R.raw.isrg_root_x2,
        R.raw.isrg_root_yr,
        R.raw.isrg_root_ye,
        R.raw.gtsr1,
        R.raw.gtsr2,
        R.raw.gtsr3,
        R.raw.gtsr4,
        R.raw.amazon_root_ca1,
        R.raw.amazon_root_ca2,
        R.raw.amazon_root_ca3,
        R.raw.amazon_root_ca4
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedTrustManager: X509TrustManager? = null

    @Volatile
    private var cachedSocketFactory: SSLSocketFactory? = null

    /** Must be called from [android.app.Application] before any OkHttp client is built. */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun trustManager(): X509TrustManager {
        cachedTrustManager?.let { return it }
        synchronized(this) {
            cachedTrustManager?.let { return it }
            val context = appContext ?: return systemTrustManager()
            val manager = buildCombinedTrustManager(context)
            cachedTrustManager = manager
            return manager
        }
    }

    fun sslSocketFactory(): SSLSocketFactory {
        cachedSocketFactory?.let { return it }
        synchronized(this) {
            cachedSocketFactory?.let { return it }
            val factory = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager()), SecureRandom())
            }.socketFactory
            cachedSocketFactory = factory
            return factory
        }
    }

    /**
     * Attaches the combined trust manager to an OkHttp client. Falls back to the
     * platform stack only when the Application has not called [init] yet (JVM unit
     * tests). A warning is logged so a missing/too-late [init] call is visible.
     */
    fun attach(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (appContext == null) {
            Log.w(TAG, "attach() called before init(); using platform trust store only")
            return builder
        }
        return builder.sslSocketFactory(sslSocketFactory(), trustManager())
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun buildCombinedTrustManager(context: Context): X509TrustManager {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }

        // 1. Copy the platform CA store so existing anchors keep working.
        var index = 0
        try {
            val systemStore = KeyStore.getInstance("AndroidCAStore")
            systemStore.load(null, null)
            val aliases = systemStore.aliases()
            while (aliases.hasMoreElements()) {
                val cert = systemStore.getCertificate(aliases.nextElement())
                if (cert is X509Certificate) {
                    keyStore.setCertificateEntry("system_ca_${index++}", cert)
                }
            }
            Log.i(TAG, "Copied $index system CA anchors")
        } catch (failure: Exception) {
            Log.w(TAG, "Failed to copy system CA store: ${failure.message}")
        }

        // 2. Add the bundled roots on top.
        val certificateFactory = CertificateFactory.getInstance("X.509")
        var bundledIndex = 0
        for (resourceId in bundledCaResources) {
            try {
                context.resources.openRawResource(resourceId).use { input ->
                    val certificate = certificateFactory.generateCertificate(input)
                    keyStore.setCertificateEntry("bundled_ca_${bundledIndex++}", certificate)
                }
            } catch (failure: Exception) {
                Log.w(TAG, "Failed to load bundled CA resource $resourceId: ${failure.message}")
            }
        }

        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
