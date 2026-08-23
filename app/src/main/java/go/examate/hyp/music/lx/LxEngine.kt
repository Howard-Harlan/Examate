package go.examate.hyp.music.lx

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import okhttp3.Call
import okhttp3.Callback
import go.examate.hyp.music.MusicHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * LX 音源运行时 — 对齐落雪音乐(lx-music-mobile)官方底层架构:
 *
 *  - 独立 HandlerThread("LxJs") 承载 QuickJSContext,所有 JS 操作只在该线程发生
 *    (官方 JavaScriptThread + JsHandler 同款模型)。
 *  - 全异步事件循环:JS 发起的 HTTP 请求经 OkHttp enqueue 异步执行,完成后把
 *    response 以消息形式 post 回 JS 线程 callJS('response', ...),JS 永不阻塞
 *    (官方即此模型;旧版同步泵送 pumpDrain 已废弃)。
 *  - setTimeout 由 JS 线程 Handler.postDelayed 真实计时,到期 callJS('__set_timeout__')。
 *  - 引擎侧动作(musicUrl/lyric/pic):调用方(IO 线程)post 请求后用 CountDownLatch
 *    等待 JS 线程回传的 response,25s 超时。
 *
 * 请求体处理对齐官方 request.js:form → urlencoded body + Content-Type 补全;
 * 响应 body 自动 JSON.parse;headers 缺失时补默认 UA + Accept。
 */
class LxEngine private constructor(
    private val context: Context,
    private val script: String
) : Closeable {

    /** 脚本 send(inited) 声明的平台→音质 */
    val declaredSources = mutableMapOf<String, List<String>>()

    /** 最近一次错误(JS 异常/初始化失败消息) */
    var lastError: String = ""
        private set

    private var key = ""
    private var ctx: QuickJSContext? = null
    private val thread = HandlerThread("LxJs").apply { start() }
    private lateinit var handler: Handler

    private val http = MusicHttp.derived(15, 20)

    /** 引擎发起的 action 请求 → JS 回传的响应(requestKey → 闭锁+结果) */
    private class PendingAction(val latch: CountDownLatch) {
        @Volatile var status = false
        @Volatile var result: JSONObject? = null
        @Volatile var errorMessage = ""
    }
    private val pendingActions = HashMap<String, PendingAction>()

    /** 本次 action 期间是否出现过 404+HTML 响应(服务端拒签特征:脚本文件不完整/已过期) */
    @Volatile private var sawHtml404 = false

    /** 初始化完成信号(脚本 send inited / 初始化失败) */
    private val initLatch = CountDownLatch(1)
    @Volatile private var initFailed = false

    companion object {
        private const val TAG = "LxEngine"
        private const val MSG_CALL = 2
        private const val MSG_DESTROY = 3
        private const val ACTION_TIMEOUT_MS = 25_000L
        private const val INIT_WAIT_MS = 30_000L
        // 默认 UA 与落雪 request.js defaultHeaders 完全一致(脚本依赖该指纹)
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36"

        @Volatile private var inited = false
        @Volatile private var initError: String? = null

        private fun ensureInit() {
            if (!inited) synchronized(this) {
                if (!inited) runCatching { QuickJSLoader.init(); inited = true }
                    .onFailure {
                        initError = it.message ?: it.javaClass.simpleName
                        Log.e(TAG, "QuickJSLoader init fail", it)
                    }
            }
            initError?.let { throw RuntimeException("QuickJS 初始化失败: $it") }
        }

        /** 创建并加载脚本(在 JS 线程完成);失败抛异常(带具体原因),由调用方捕获 */
        fun createAndLoad(context: Context, script: String): LxEngine {
            ensureInit()
            val engine = LxEngine(context, script)
            engine.loadOnJsThread()
            return engine
        }
    }

    // ════════════════════════════════════════════
    //  初始化(JS 线程)
    // ════════════════════════════════════════════

    /** 在 JS 线程同步执行初始化,完成后返回(成功)或抛异常(失败) */
    private fun loadOnJsThread() {
        key = UUID.randomUUID().toString()
        var initException: Throwable? = null
        val done = CountDownLatch(1)
        handler = Handler(thread.looper) { msg ->
            when (msg.what) {
                MSG_CALL -> {
                    val arr = msg.obj as Array<*>
                    callJS(arr[0] as String, arr[1] as? String)
                    true
                }
                MSG_DESTROY -> {
                    runCatching { ctx?.destroy() }
                    ctx = null
                    true
                }
                else -> false
            }
        }
        runOnJs {
            try {
                initJsEnv()
            } catch (e: Throwable) {
                initException = e
            } finally {
                done.countDown()
            }
        }
        done.await(30, TimeUnit.SECONDS)
        initException?.let {
            close()
            throw it
        }
        // 等待脚本 send(inited) 声明源(对齐官方:无超时死等;这里给 30s 上限防挂死)
        val initedInTime = initLatch.await(INIT_WAIT_MS, TimeUnit.MILLISECONDS)
        if (initFailed) {
            val err = lastError.ifBlank { "音源脚本初始化失败" }
            close()
            throw RuntimeException(err)
        }
        if (!initedInTime) {
            close()
            throw RuntimeException("音源脚本未完成初始化(未回传 inited) — 脚本可能仅支持特定宿主或已失效${consoleErrHint()}")
        }
        Log.d(TAG, "init: 完成, declaredSources=${declaredSources.keys}")
    }

    /** 初始化失败时附带的脚本 console.error 摘要(无则空串) */
    private fun consoleErrHint(): String {
        val e = recentConsoleErrors()
        return if (e.isNotBlank()) ";脚本日志: $e" else ""
    }

    /** 创建 QuickJS 环境 + 注入原生桥 + 执行 preload 与音源脚本(仅 JS 线程调用) */
    private fun initJsEnv() {
        val jsCtx = QuickJSContext.create()
        ctx = jsCtx
        setupConsole(jsCtx)
        createNativeBridge(jsCtx)
        val preload = runCatching {
            context.assets.open("script/user-api-preload.js").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (preload.isNullOrBlank()) throw RuntimeException("user-api-preload.js 缺失")
        jsCtx.evaluate(preload)
        val header = parseScriptHeader(script)
        jsCtx.globalObject.getJSFunction("lx_setup").call(
            key, "lx-source",
            header["name"] ?: "", header["description"] ?: "",
            header["version"] ?: "", header["author"] ?: "", header["homepage"] ?: "",
            script
        )
        Log.d(TAG, "init: lx_setup 完成, 执行音源脚本 len=${script.length}")
        try {
            jsCtx.evaluate(script)
        } catch (e: Throwable) {
            val err = cleanJsError(e.message ?: e.javaClass.simpleName)
            // 对齐官方 QuickJS.loadScript:先回传 __run_error__,若脚本已完成 inited 则保留引擎
            // (官方同语义:if (inited) return "");仅当尚未初始化时才视为致命错误
            runCatching { callJS("__run_error__", null) }
            if (initLatch.count > 0L) {
                lastError = err
                Log.w(TAG, "init: 音源脚本执行异常: $err", e)
                throw RuntimeException("音源脚本执行异常: $err")
            }
            lastError = err
            Log.w(TAG, "init: 脚本执行异常但已完成初始化,保留引擎: $err")
        }
    }

    // ════════════════════════════════════════════
    //  原生桥(对齐落雪 QuickJS.java createEnvObj)
    // ════════════════════════════════════════════

    private fun createNativeBridge(jsCtx: QuickJSContext) {
        val g = jsCtx.globalObject
        // 主桥:脚本通过 __lx_native__(key, action, dataJson) 与原生通信(preload 内定义 __lx_native__)
        g.setProperty("__lx_native_call__", JSCallFunction { args ->
            if (args.getOrNull(0)?.toString() != key) return@JSCallFunction null
            val action = args.getOrNull(1)?.toString() ?: ""
            val data = args.getOrNull(2)?.toString()?.let { runCatching { JSONObject(it) }.getOrNull() }
            handleNativeAction(action, data)
            null
        })
        // utils 桥(落雪协议)
        g.setProperty("__lx_native_call__utils_str2b64", JSCallFunction { a -> LxCrypto.str2b64(a.strOr(0)) })
        g.setProperty("__lx_native_call__utils_b642buf", JSCallFunction { a -> LxCrypto.b64ToBufJson(a.strOr(0)) })
        g.setProperty("__lx_native_call__utils_str2md5", JSCallFunction { a -> LxCrypto.md5UrlDecoded(a.strOr(0)) })
        g.setProperty("__lx_native_call__utils_aes_encrypt", JSCallFunction { a ->
            LxCrypto.aesEncryptB64(a.strOr(0), a.strOr(1), a.strOr(2), a.strOr(3))
        })
        g.setProperty("__lx_native_call__utils_rsa_encrypt", JSCallFunction { a ->
            LxCrypto.rsaEncryptB64(a.strOr(0), a.strOr(1), a.strOr(2))
        })
        // setTimeout 桥:JS 线程真实计时(对齐官方 timeoutHandler.postDelayed)
        g.setProperty("__lx_native_call__set_timeout", JSCallFunction { a ->
            val id = a.getOrNull(0)?.toString() ?: ""
            val ms = a.intOr(1).coerceAtLeast(0)
            handler.postDelayed({ callJS("__set_timeout__", id) }, ms.toLong())
            null
        })
    }

    /** console 转发到 logcat(在 preload freeze 之前定义);error 记入环形缓存供失败诊断 */
    private val consoleErrors = ArrayDeque<String>()
    private fun setupConsole(jsCtx: QuickJSContext) {
        val console = jsCtx.createNewJSObject()
        console.setProperty("log", JSCallFunction { a -> Log.d("LxScript", a.joinToString(" ") { it?.toString() ?: "null" }); null })
        console.setProperty("info", JSCallFunction { a -> Log.i("LxScript", a.joinToString(" ") { it?.toString() ?: "null" }); null })
        console.setProperty("warn", JSCallFunction { a -> Log.w("LxScript", a.joinToString(" ") { it?.toString() ?: "null" }); null })
        console.setProperty("error", JSCallFunction { a ->
            val msg = a.joinToString(" ") { it?.toString() ?: "null" }
            Log.e("LxScript", msg)
            synchronized(consoleErrors) {
                consoleErrors.addLast(msg.take(200))
                while (consoleErrors.size > 5) consoleErrors.removeFirst()
            }
            null
        })
        jsCtx.globalObject.setProperty("console", console)
    }

    /** 最近脚本 console.error 摘要(解析失败时附在错误信息里,便于定位脚本内部问题) */
    private fun recentConsoleErrors(): String = synchronized(consoleErrors) {
        consoleErrors.lastOrNull().orEmpty()
    }

    // ════════════════════════════════════════════
    //  事件分发(仅 JS 线程调用 — 由 __lx_native_call__ 触发)
    // ════════════════════════════════════════════

    private fun handleNativeAction(action: String, data: JSONObject?) {
        when (action) {
            "request" -> if (data != null) dispatchScriptRequest(data)
            // JS 回传的 action 响应(引擎发起 musicUrl/lyric/pic → preload handleRequest → 脚本 → response)
            "response" -> {
                val requestKey = data?.optString("requestKey") ?: return
                val pending = synchronized(pendingActions) { pendingActions.remove(requestKey) } ?: return
                pending.status = data.optBoolean("status")
                pending.result = data.optJSONObject("result")
                pending.errorMessage = data.optString("errorMessage").orEmpty()
                Log.d(TAG, "response: key=$requestKey status=${pending.status} err=${pending.errorMessage}")
                pending.latch.countDown()
            }
            "init" -> {
                // 脚本 send(inited) 回调:info.sources 声明支持平台(对齐落雪 handleStateChange)
                val statusOk = data?.optBoolean("status") ?: false
                val info = data?.optJSONObject("info")
                val sources = info?.optJSONObject("sources")
                if (sources != null) {
                    declaredSources.clear()
                    for (src in listOf("wy", "kg", "kw", "tx", "mg", "bili")) {
                        val s = sources.optJSONObject(src) ?: continue
                        val qualitys = mutableListOf<String>()
                        val qArr = s.optJSONArray("qualitys")
                        if (qArr != null) for (i in 0 until qArr.length()) qualitys.add(qArr.optString(i))
                        declaredSources[src] = qualitys
                        Log.d(TAG, "inited: $src qualitys=$qualitys")
                    }
                } else if (!statusOk) {
                    initFailed = true
                    lastError = data?.optString("errorMessage").orEmpty()
                    Log.w(TAG, "init 回调失败: $lastError")
                }
                initLatch.countDown()
            }
            "showUpdateAlert" -> Log.d(TAG, "updateAlert: ${data?.optString("log")}")
            "cancelRequest" -> {}   // OkHttp 异步请求不做中途取消(官方同语义:仅从映射表移除)
            else -> Log.d(TAG, "native action: $action")
        }
    }

    /**
     * JS 发起的 HTTP 请求 — OkHttp 异步执行,完成后 post 回 JS 线程回传 response。
     * 请求体处理对齐官方 handleRequestData:form → urlencoded body + Content-Type 补全;
     * POST 无 Content-Type 时默认 application/json;body 为对象时 JSON.stringify。
     */
    private fun dispatchScriptRequest(req: JSONObject) {
        val requestKey = req.optString("requestKey")
        // URL 直传(对齐官方:不做 base64 猜测,脚本传什么用什么)
        val url = req.optString("url")
        val options = req.optJSONObject("options") ?: JSONObject()
        var method = options.optString("method", "get").uppercase()
        val headersJson = options.optJSONObject("headers") ?: JSONObject()
        val binary = options.optBoolean("binary")   // 脚本请求二进制(对齐官方 fetchData 的 blob 分支)
        // 脚本指定的超时(对齐官方 fetchData timeout,上限 60s);缺省用客户端默认
        val timeoutMs = options.optLong("timeout", 0L).let { if (it in 1..60_000) it else 0L }
        var bodyStr = options.optString("body", "")
        val bodyObj = options.optJSONObject("body")
        val form = options.optJSONObject("form") ?: options.optJSONObject("formData")
        if (method == "POST" && !hasHeader(headersJson, "Content-Type")) {
            if (form != null) {
                headersJson.put("Content-Type", "application/x-www-form-urlencoded")
                val keys = form.keys()
                val parts = mutableListOf<String>()
                while (keys.hasNext()) {
                    val k = keys.next()
                    parts.add("${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(form.optString(k), "UTF-8")}")
                }
                bodyStr = parts.joinToString("&")
            } else {
                headersJson.put("Content-Type", "application/json")
                if (bodyObj != null) bodyStr = bodyObj.toString()
            }
        } else if (bodyObj != null && bodyStr.isBlank()) {
            bodyStr = bodyObj.toString()
        }
        // 对齐官方 handleRequestData:默认 Accept: application/json
        if (!hasHeader(headersJson, "Accept")) headersJson.put("Accept", "application/json")

        Log.d(TAG, "doRequest: $method $url")
        val builder = Request.Builder().url(url)
        if (!hasHeader(headersJson, "User-Agent")) {
            builder.header("User-Agent", DEFAULT_UA)
        }
        val hkeys = headersJson.keys()
        while (hkeys.hasNext()) {
            val k = hkeys.next()
            val v = when (val raw = headersJson.opt(k)) {
                is org.json.JSONArray -> (0 until raw.length()).joinToString(",") { raw.optString(it) }
                else -> raw?.toString() ?: ""
            }
            // 净化头值:部分脚本会算出含控制字符的头(如把非 hex 串当 hex 解码),
            // nginx 对此直接 400;只保留可打印 ASCII,给脚本一个到达后端的机会
            builder.header(k, sanitizeHeaderValue(v))
        }
        when (method) {
            "POST" -> builder.post(bodyStr.toRequestBody())
            "PUT" -> builder.put(bodyStr.toRequestBody())
            "DELETE" -> builder.delete(if (bodyStr.isBlank()) null else bodyStr.toRequestBody())
            else -> builder.get()
        }

        // 脚本指定超时时派生专属客户端(连接+读取均按脚本要求,避免默认 20s 切断慢接口)
        val callClient = if (timeoutMs > 0) {
            http.newBuilder()
                .connectTimeout(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs + 10_000, TimeUnit.MILLISECONDS)
                .build()
        } else http
        callClient.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val respJson = JSONObject()
                respJson.put("requestKey", requestKey)
                respJson.put("error", e.message ?: "网络请求失败")
                respJson.put("response", org.json.JSONObject.NULL)
                postCallJS("response", respJson.toString())
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "doRequest 响应: ${response.code} ${response.request.url}")
                // 服务端拒签特征:404 + HTML 页(如 nginx/MusicDownloader 错误页) —
                // 脚本 sign 含脚本全文 md5,文件差一个字节都会被拒
                if (response.code == 404) {
                    val ct = response.header("Content-Type").orEmpty()
                    if (ct.contains("html", true) || !ct.contains("json", true)) sawHtml404 = true
                }
                val respJson = runCatching {
                    val headersObj = JSONObject()
                    for (name in response.headers.names()) {
                        headersObj.put(name.lowercase(), response.headers.values(name).joinToString(","))
                    }
                    // binary 请求:body 以字节数组回传(脚本经 utils.buffer 处理,
                    // 对齐官方 blobToBuffer 语义);普通请求自动 JSON.parse
                    val parsedBody: Any = if (binary) {
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        val arr = org.json.JSONArray()
                        if (bytes.size <= 8 * 1024 * 1024) {
                            for (b in bytes) arr.put(b.toInt() and 0xFF)
                        }
                        arr
                    } else {
                        val bodyText = response.body?.string() ?: ""
                        // 非 JSON 响应(如 nginx 400/404 的 HTML)记日志,便于定位脚本层失败
                        val parsedBody: Any = runCatching { org.json.JSONObject(bodyText) }.getOrElse {
                            runCatching { org.json.JSONArray(bodyText) }.getOrElse {
                                Log.w(TAG, "doRequest 非 JSON 响应(${response.code}): ${bodyText.take(160).replace(Regex("\\s+"), " ")}")
                                bodyText
                            }
                        }
                        parsedBody
                    }
                    val responseObj = JSONObject()
                    responseObj.put("statusCode", response.code)
                    responseObj.put("statusMessage", response.message)
                    responseObj.put("headers", headersObj)
                    responseObj.put("body", parsedBody)
                    responseObj.put("url", response.request.url.toString())
                    responseObj.put("ok", response.isSuccessful)
                    response.close()
                    JSONObject().apply {
                        put("requestKey", requestKey)
                        put("error", org.json.JSONObject.NULL)
                        put("response", responseObj)
                    }
                }.getOrElse { e ->
                    JSONObject().apply {
                        put("requestKey", requestKey)
                        put("error", e.message ?: "响应处理失败")
                        put("response", org.json.JSONObject.NULL)
                    }
                }
                postCallJS("response", respJson.toString())
            }
        })
    }

    private fun hasHeader(headers: JSONObject, name: String): Boolean {
        val keys = headers.keys()
        while (keys.hasNext()) if (keys.next().equals(name, ignoreCase = true)) return true
        return false
    }

    /** 头值净化:仅保留可打印 ASCII(0x21..0x7E)与空格;控制字符/非 ASCII 一律剔除 */
    private fun sanitizeHeaderValue(v: String): String =
        v.filter { it == ' ' || (it.code in 0x21..0x7E) }.trim()

    // ════════════════════════════════════════════
    //  JS 线程消息投递
    // ════════════════════════════════════════════

    private fun runOnJs(r: () -> Unit) {
        handler.post(r)
    }

    /** 向 JS 发事件(经消息队列投递,保证只在 JS 线程执行) */
    private fun postCallJS(action: String, dataJson: String?) {
        if (Looper.myLooper() == thread.looper) {
            callJS(action, dataJson)
        } else {
            handler.obtainMessage(MSG_CALL, arrayOf<Any?>(action, dataJson)).sendToTarget()
        }
    }

    /** 调 preload 定义的 __lx_native__(仅 JS 线程调用) */
    private fun callJS(action: String, dataJson: String?) {
        runCatching {
            ctx?.globalObject?.getJSFunction("__lx_native__")?.call(key, action, dataJson)
        }.onFailure { Log.w(TAG, "callJS($action) fail", it) }
    }

    // ════════════════════════════════════════════
    //  动作解析:musicUrl / lyric / pic
    // ════════════════════════════════════════════

    /**
     * 构造与落雪 toOldMusicInfo 等价的 musicInfo(脚本 handler 依赖这些字段构造请求):
     *   name/singer/source/songmid/interval/albumName/img/albumId +
     *   分平台:kg→hash、tx→strMediaMid/albumMid、mg→copyrightId
     */
    private fun buildMusicInfo(source: String, song: LxSong): JSONObject {
        val info = JSONObject()
        info.put("name", song.name)
        info.put("singer", song.artist)
        info.put("source", source)
        info.put("songmid", song.songmid)
        info.put("songId", song.songmid)
        info.put("interval", durationToInterval(song.durationMs))
        info.put("albumName", "")
        info.put("img", song.picUrl)
        info.put("albumId", song.albumId)
        when (source) {
            "kg" -> info.put("hash", song.songmid)
            "tx" -> {
                info.put("strMediaMid", song.songmid)
                info.put("albumMid", song.albumId)
            }
            "mg" -> info.put("copyrightId", song.songmid)
        }
        return info
    }

    /** durationMs → 落雪 interval 字符串("m:ss") */
    private fun durationToInterval(durationMs: Long): String {
        if (durationMs <= 0) return ""
        val totalSec = durationMs / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /**
     * 通用 action 调用 — 落雪事件模型(异步事件循环版):
     *  注册 requestKey → postCallJS('request', payload) → JS 线程执行 preload handleRequest
     *  → 脚本 handler(可发起多次异步 HTTP,全部经事件循环回传)→ nativeCall('response')
     *  → handleNativeAction 解锁闭锁 → 调用方取结果。
     */
    private fun runAction(source: String, action: String, quality: String, musicInfo: JSONObject): String {
        lastError = ""
        sawHtml404 = false
        val requestKey = "lx_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
        return try {
            val data = JSONObject()
            data.put("source", source)
            data.put("action", action)
            val info = JSONObject()
            if (quality.isNotBlank()) info.put("type", quality)
            info.put("musicInfo", musicInfo)
            data.put("info", info)
            val payload = JSONObject().put("requestKey", requestKey).put("data", data)

            val pending = PendingAction(CountDownLatch(1))
            synchronized(pendingActions) { pendingActions[requestKey] = pending }
            postCallJS("request", payload.toString())
            Log.d(TAG, "$action 发起: source=$source name=${musicInfo.optString("name")} songmid=${musicInfo.optString("songmid")} quality=$quality")

            val finished = pending.latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            synchronized(pendingActions) { pendingActions.remove(requestKey) }
            when {
                !finished -> {
                    lastError = "音源未在预期时间内返回(可能脚本未支持该 action 或网络超时)"
                    Log.w(TAG, "$action timeout: $requestKey")
                    ""
                }
                pending.status -> {
                    val result = extractResult(pending.result, action)
                    Log.d(TAG, "$action resolved: '$result'")
                    result
                }
                else -> {
                    lastError = humanizeError(pending.errorMessage)
                    // 服务端拒签(404+HTML)时给出明确诊断:脚本文件不完整/已过期是最常见原因
                    if (sawHtml404 && action == "musicUrl") {
                        lastError = "服务端拒绝该脚本(sign 校验不过):脚本文件可能下载不完整或已过期,请删除后重新导入"
                    } else {
                        // 脚本错误信息缺失/笼统时附上其 console.error 摘要,避免用户只看到"解析失败"
                        val consoleErr = recentConsoleErrors()
                        val generic = lastError.isBlank() || lastError == "音源解析失败" ||
                            lastError == "failed" || pending.errorMessage.isBlank()
                        if (consoleErr.isNotBlank() && generic) {
                            lastError = "脚本内部错误: $consoleErr"
                        } else if (lastError == "音源解析失败") {
                            lastError = "音源解析失败(脚本未给出原因,可查看 logcat 标签 LxScript/LxEngine)"
                        }
                    }
                    Log.w(TAG, "$action 失败: $lastError (raw=${pending.errorMessage})")
                    ""
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "$action fail", e)
            lastError = cleanJsError(e.message ?: e.javaClass.simpleName)
            ""
        }
    }

    /** 把 preload 返回的英文错误转成用户可读的中文提示 */
    private fun humanizeError(raw: String): String = when {
        raw.contains("eval is not available") || raw.contains("Dynamic code execution is not allowed") ->
            "该脚本依赖动态代码(eval/Function)解密运行,出于安全本 App 与落雪移动版均禁止 — 请改用不需解密的音源脚本"
        raw.contains("Request event is not defined") ->
            "音源脚本未成功注册(脚本含反调试/环境校验,仅支持官方宿主),请更换音源或使用官方源"
        raw.contains("Script is inited") -> "音源脚本已初始化过,无需重复注册"
        raw.contains("failed") && raw.contains("url") -> "音源未返回有效播放地址"
        raw.isBlank() -> "音源解析失败"
        else -> raw
    }

    /** 从 JS 响应 result 中提取目标值(musicUrl→data.url / lyric→data.lyric / pic→data 字符串) */
    private fun extractResult(result: JSONObject?, action: String): String {
        if (result == null) return ""
        val data = result.opt("data")
        return when (action) {
            "musicUrl" -> (data as? JSONObject)?.optString("url").orEmpty()
            "lyric" -> (data as? JSONObject)?.optString("lyric").orEmpty()
            "pic" -> data?.toString().orEmpty()
            else -> ""
        }
    }

    fun resolveMusicUrl(source: String, song: LxSong, quality: String): String =
        runAction(source, "musicUrl", quality, buildMusicInfo(source, song))

    fun resolveLyric(source: String, song: LxSong): String =
        runAction(source, "lyric", "", buildMusicInfo(source, song))

    fun resolvePic(source: String, song: LxSong): String =
        runAction(source, "pic", "", buildMusicInfo(source, song))

    /** 引擎是否健康(初始化成功);池复用判断用 */
    fun isHealthy(): Boolean = ctx != null && thread.isAlive

    override fun close() {
        runCatching { handler.post { runCatching { ctx?.destroy() }; ctx = null } }
        runCatching { thread.quitSafely() }
    }

    private fun parseScriptHeader(script: String): Map<String, String> {
        val header = mutableMapOf<String, String>()
        val regex = Regex("@(\\w+)\\s+(.+)")
        script.lineSequence().take(1000).forEach { line ->
            regex.find(line)?.let { m ->
                val key = m.groupValues[1]
                val value = m.groupValues[2].trim()
                if (key in listOf("name", "description", "version", "author", "homepage")) {
                    header[key] = value
                }
            }
        }
        return header
    }
}

private fun Array<Any?>.strOr(i: Int): String = getOrNull(i)?.toString() ?: ""
private fun Array<Any?>.intOr(i: Int): Int = when (val v = getOrNull(i)) {
    is Int -> v; is Long -> v.toInt(); is Double -> v.toInt()
    is String -> v.toIntOrNull() ?: 0; else -> 0
}

/** 清理 QuickJS 错误消息中的栈帧噪音 */
private fun cleanJsError(raw: String): String {
    var s = raw.trim()
    s = s.replace(Regex("\\s+at\\s+<anonymous>\\([^)]*\\)"), "")
    s = s.replace(Regex("\\s+at\\s+\\S+\\(unknown\\.js[^)]*\\)"), "")
    s = s.replace(Regex("\\s+at\\s+\\d+:\\d+"), "")
    s = s.replace(Regex("\\s+at\\s+<eval>\\([^)]*\\)"), "")
    return s.trim().ifBlank { raw.trim() }
}
