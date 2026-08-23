package go.examate.hyp.music

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 音乐模块共享 HTTP 客户端。
 *
 * 所有音乐相关 API 应复用此 client(或通过 [derived] 派生自定义超时),
 * 以共享连接池/调度器,避免每个 API 各建 OkHttpClient 浪费内存与连接。
 */
object MusicHttp {
    private val base by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 默认客户端(connect 12s / read 15s)。 */
    val client: OkHttpClient get() = base

    /** 派生自定义超时的客户端(共享连接池与调度器)。 */
    fun derived(connectTimeout: Long = 12, readTimeout: Long = 15, writeTimeout: Long = 10): OkHttpClient =
        base.newBuilder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            .build()
}