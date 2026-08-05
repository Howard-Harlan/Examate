package go.examate.hyp.media.legado

import android.util.Base64
import android.util.Log
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.android.QuickJSLoader
import java.security.MessageDigest

/**
 * QuickJS 封装 — 执行 legado 书源内嵌 JS(@js:/<js>)。
 *
 * 沙箱要点:
 *  - 每次业务批次(一次搜索/一次取正文)创建一个 QuickJSContext,批内复用,批结束 close() 释放。
 *  - 只向 JS 注入受控的 `java` 对象(见 JsExtensions)与 baseUrl/result/key/page 等绑定,
 *    不暴露文件系统/反射/任意系统调用。
 *  - JS 异常被捕获为空结果,不使 App 崩溃。
 *
 * 线程:QuickJS 单线程,由 wrapper 内部 synchronized 保证;调用方在 Dispatchers.IO 串行使用。
 */
class JsEngine private constructor() : JsEngineApi, java.io.Closeable {

    companion object {
        private const val TAG = "JsEngine"

        @Volatile private var inited = false

        /** 首次创建时懒加载 so(不在 Application 冷启动加载,减小主进程负担) */
        private fun ensureInit() {
            if (!inited) {
                synchronized(this) {
                    if (!inited) {
                        try { QuickJSLoader.init(); inited = true }
                        catch (e: Throwable) { Log.e(TAG, "QuickJSLoader init fail", e) }
                    }
                }
            }
        }

        /** 创建引擎;so 加载失败返回 null(上层回退"跳过JS源") */
        fun createOrNull(extensions: JsExtensions): JsEngine? {
            ensureInit()
            if (!inited) return null
            return try { JsEngine().apply { attachJava(extensions) } } catch (e: Throwable) {
                Log.e(TAG, "create context fail", e); null
            }
        }
    }

    private val context: QuickJSContext = QuickJSContext.create()

    /** 把 legado `java.*` 方法挂到全局 java 对象 */
    private fun attachJava(ext: JsExtensions) {
        val global = context.globalObject
        val java = context.createNewJSObject()
        java.setProperty("ajax", JSCallFunction { args -> ext.ajax(args.strOr(0)) })
        java.setProperty("get", JSCallFunction { args -> ext.getVar(args.strOr(0)) })   // legado 常见:java.get(key)
        java.setProperty("put", JSCallFunction { args -> ext.put(args.strOr(0), args.strOr(1)) })
        java.setProperty("base64Encode", JSCallFunction { args -> ext.base64Encode(args.strOr(0)) })
        java.setProperty("base64Decode", JSCallFunction { args -> ext.base64Decode(args.strOr(0)) })
        java.setProperty("md5Encode", JSCallFunction { args -> ext.md5Encode(args.strOr(0)) })
        java.setProperty("encodeURI", JSCallFunction { args -> ext.encodeURI(args.strOr(0)) })
        java.setProperty("timeFormat", JSCallFunction { args -> ext.timeFormat(args.longOr(0)) })
        java.setProperty("log", JSCallFunction { args -> ext.log(args.strOr(0)) })
        java.setProperty("toast", JSCallFunction { args -> ext.log(args.strOr(0)) })
        global.setProperty("java", java)
        java.release()
    }

    /**
     * 执行脚本,返回字符串结果。
     * bindings 里的 baseUrl/result/key/page 等注入为全局变量,供源脚本使用。
     */
    override fun eval(script: String, bindings: Map<String, Any?>): String {
        return try {
            // 注入绑定变量(字符串/数字)
            val global = context.globalObject
            bindings.forEach { (k, v) ->
                when (v) {
                    null -> {}
                    is String -> global.setProperty(k, v)
                    is Int -> global.setProperty(k, v)
                    is Long -> global.setProperty(k, v.toInt())
                    is Double -> global.setProperty(k, v)
                    is Boolean -> global.setProperty(k, v)
                    else -> global.setProperty(k, v.toString())
                }
            }
            val ret = context.evaluate(script)
            stringify(ret)
        } catch (e: Throwable) {
            Log.w(TAG, "eval fail: ${script.take(80)}", e); ""
        }
    }

    /** JS 返回值 → 字符串(数组转换行,对象转 JSON 文本) */
    private fun stringify(ret: Any?): String = when (ret) {
        null -> ""
        is String -> ret
        is Boolean, is Int, is Long, is Double -> ret.toString()
        else -> ret.toString()
    }

    override fun close() {
        try { context.destroy() } catch (_: Throwable) {}
    }
}

/** JSCallFunction 参数取值辅助 */
private fun Array<Any?>.strOr(i: Int): String = (getOrNull(i)?.toString()) ?: ""
private fun Array<Any?>.longOr(i: Int): Long = when (val v = getOrNull(i)) {
    is Long -> v; is Int -> v.toLong(); is Double -> v.toLong()
    is String -> v.toLongOrNull() ?: 0L; else -> 0L
}

/**
 * legado 内置 JS 方法实现(挂到 JS 的 `java` 对象上)。
 * 阶段2 MVP 集合,覆盖书源高频用法;未实现的方法在 JS 侧为 undefined,由源脚本容错。
 */
class JsExtensions(
    private val source: BookSource,
    private val ctx: RuleContext
) {
    companion object { private const val TAG = "JsExtensions" }

    /** GET 抓取,返回响应体 */
    fun ajax(url: String): String = try {
        val abs = NetworkUtils.absoluteUrl(ctx.baseUrl, url)
        LegadoHttp.fetch(AnalyzeUrl.UrlRequest(abs, headers = mapOf("User-Agent" to UA))).body
    } catch (e: Exception) { Log.w(TAG, "ajax fail: $url", e); "" }

    /** java.get(key):读取 @put 存的变量(与 HTTP get 靠语义区分,书源多用于读变量) */
    fun getVar(key: String): String = ctx.variables[key] ?: ""

    fun put(key: String, value: String): String { ctx.variables[key] = value; return value }

    fun base64Encode(s: String): String =
        try { Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP) } catch (_: Exception) { "" }

    fun base64Decode(s: String): String =
        try { String(Base64.decode(s, Base64.NO_WRAP)) } catch (_: Exception) { "" }

    fun md5Encode(s: String): String = try {
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "" }

    fun encodeURI(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8") } catch (_: Exception) { s }

    fun timeFormat(ts: Long): String =
        try { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts)) } catch (_: Exception) { "" }

    fun log(msg: String): String { Log.i(TAG, msg); return msg }
}

private const val UA =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
