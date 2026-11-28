package com.nuvio.tv.core.cctv

/**
 * 央视 h5e 加密流原生解密桥（MiTV4A 移植，源自 qianxuntudou-ops/MyTVB）。
 *
 * 央视网影视点播（getHttpVideoInfo 返回的 hls_h5e_url / asp/h5e/）TS 分片经
 * H5e 加密：官方 wasm 按 NALU 变换，密钥状态绑定播放器标签
 * player_container_player + pageHost。原生层用 wasm2c 转出的纯 C 完成
 * wasm 执行 + TS demux/remux，Java 侧整段进整段出，不依赖 WebAssembly。
 *
 * native 实现见 app/src/main/cpp/cctv_h5e/，产物 libcctv_h5e.so。
 * 只有 behaviorHints.h5eEncrypted=true 的 stream 才会触发 loadLibrary。
 *
 * 注意：external 方法名必须与 native 侧 JNI 符号
 * Java_com_nuvio_tv_core_cctv_CctvH5eDecryptor_* 精确一致，不要重命名。
 */
object CctvH5eDecryptor {

    init {
        System.loadLibrary("cctv_h5e")
    }

    /**
     * 解密一段点播 TS 分片（188 字节对齐的 TS 包序列）。
     * 原地复用输入缓冲，返回同一字节数组（native 已改写内容）；
     * 段内无加密 NAL 的老片原样透传；解密失败（wasm trap / 会话未就绪）返回 null。
     */
    external fun decryptVodTransportStream(transportStream: ByteArray): ByteArray?

    /** 直播入口保留但本项目不使用（点播专用），仅为与 native 符号对齐。 */
    external fun decryptTransportStream(transportStream: ByteArray): ByteArray?

    /** SPS 兼容模式：老盒子解码器对解密后 SPS 严格校验失败时开启。 */
    external fun setSpsCompatibilityMode(enabled: Boolean)

    /** 停止播放时取消进行中的解密（解密线程尽快返回）。 */
    external fun cancelPendingDecrypts()

    /** 释放调用线程的 wasm 上下文（native 另有 pthread key 析构兜底）。 */
    external fun releaseThreadContext()

    /** 最近一次解密返回 null 的 native 侧原因；仅同线程紧接着调用有意义。 */
    external fun lastDecryptFailureReason(): String?
}
