package go.examate.hyp.music.lx

import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * LX 音源运行时 — 在 QuickJS 中搭建 globalThis.lx,运行用户导入的 LX 脚本。
 *
 * 提供:EVENT_NAMES / on / send / request(桥接 OkHttp) / utils(crypto+buffer) / env / version /
 *      currentScriptInfo。脚本经 on(request, handler) 注册 musicUrl 解析器,经 send(inited, {sources})
 *      声明支持的平台+音质。
 *
 * ⚠️ 说明:LX 处理器为异步(返回 Promise),本引擎用 async IIFE + 事件循环泵送获取结果。
 * crypto 签名与 Promise 泵送的具体行为需真机对脚本迭代校准(阶段2)。
 */
class LxEngine private constructor(private val context: QuickJSContext) : Closeable {

    /** 脚本 send(inited) 声明的平台→音质(如 wy→[128k,320k,flac]) */
    val declaredSources = mutableMapOf<String, List<String>>()

    /** 最近一次解析错误(JS 抛出的异常消息;空表示无错误) */
    var lastError: String = ""
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    companion object {
        private const val TAG = "LxEngine"
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

        /** 创建并加载脚本;失败时抛出异常(带具体原因),由调用方捕获 */
        fun createAndLoad(script: String): LxEngine {
            ensureInit()
            val ctx = QuickJSContext.create()
            val engine = LxEngine(ctx)
            engine.setupLxGlobal(script)
            ctx.evaluate(script)
            engine.pump(20)   // 让 inited/on 注册的同步逻辑执行
            return engine
        }

        /**
         * 解析脚本头部注释,提取 @name/@description/@version/@author/@homepage。
         * LX 脚本头部格式:
         *   /**
         *    * @name 野花🌷
         *    * @version 1
         *    */
         * 野花脚本用 currentScriptInfo.rawScript 做完整性校验(计算 MD5 与 vinfo.m 比对),
         * 缺少 rawScript 或字段不匹配会导致脚本抛出"服务器异常"。
         */
        private fun parseScriptHeader(script: String): Map<String, String> {
            val header = mutableMapOf<String, String>()
            // 匹配 @key value 格式(在多行注释中)
            val regex = Regex("@(\\w+)\\s+(.+)")
            // 只扫描前 1000 行,避免脚本过长
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

    /** 搭建 globalThis.lx */
    private fun setupLxGlobal(script: String) {
        val g = context.globalObject
        val lx = context.createNewJSObject()

        // EVENT_NAMES
        val events = context.createNewJSObject()
        events.setProperty("request", "request")
        events.setProperty("inited", "inited")
        events.setProperty("updateAlert", "updateAlert")
        lx.setProperty("EVENT_NAMES", events)

        lx.setProperty("env", "mobile")
        lx.setProperty("version", "2.0.0")

        // currentScriptInfo:解析脚本头部 @name/@version 等注释,设置 rawScript
        // 野花脚本用 rawScript 做完整性校验,缺少会抛出"服务器异常"
        val header = parseScriptHeader(script)
        val info = context.createNewJSObject()
        info.setProperty("name", header["name"] ?: "")
        info.setProperty("description", header["description"] ?: "")
        info.setProperty("version", header["version"] ?: "")
        info.setProperty("author", header["author"] ?: "")
        info.setProperty("homepage", header["homepage"] ?: "")
        info.setProperty("rawScript", script)
        lx.setProperty("currentScriptInfo", info)

        // on(name, handler):request 处理器存全局供后续调用
        lx.setProperty("on", JSCallFunction { args ->
            val name = args.getOrNull(0)?.toString()
            val fn = args.getOrNull(1)
            if (name == "request" && fn is JSFunction) g.setProperty("__lx_handler", fn)
            null
        })

        // send(name, data):捕获 inited 声明的 sources
        lx.setProperty("send", JSCallFunction { args ->
            val name = args.getOrNull(0)?.toString()
            val data = args.getOrNull(1)
            if (name == "inited" && data is JSObject) captureInited(data)
            null
        })

        // ── HTTP 请求桥接 ──
        // LX 脚本标准:lx.request(url, options) 返回 Promise,
        // 脚本内用 await lx.request(url, options) 获取响应。
        // 响应对象:{ statusCode, body, headers, url, ok }
        //
        // headers 透传:JS 侧把 headers 对象 JSON.stringify 后传给 native,
        // native 用 org.json.JSONObject 解析(避免 QuickJS JSObject 遍历 keys 的 API 缺失)
        lx.setProperty("__nativeRequest", JSCallFunction { args ->
            val url = args.getOrNull(0)?.toString().orEmpty()
            val method = args.getOrNull(1)?.toString()?.uppercase() ?: "GET"
            val body = args.getOrNull(2)?.toString()
            val headersJson = args.getOrNull(3)?.toString()
            val formJson = args.getOrNull(4)?.toString()
            val formDataJson = args.getOrNull(5)?.toString()
            // 部分 LX 脚本(如六音)将 URL 进行 Base64 编码,需自动解码
            val decodedUrl = decodeBase64Url(url)
            Log.d(TAG, "__nativeRequest: $method $decodedUrl bodyLen=${body?.length ?: 0} form=${formJson?.take(80) ?: "null"}")
            val result = doRequestSync(decodedUrl, method, body, headersJson, formJson, formDataJson)
            val respBody = result.getProperty("body") as? String ?: ""
            Log.d(TAG, "__nativeRequest done: ${result.getProperty("statusCode")} url=$decodedUrl bodyLen=${respBody.length} body=${respBody.take(500)}")
            result
        })

        // ⚠️ 必须在 context.evaluate(包装 lx.request) 之前把 lx 挂到 globalObject,
        // 否则 JS 中引用 lx 会报 "'lx' is not defined"
        g.setProperty("lx", lx)
        // 兼容部分脚本用 window/globalThis.lx
        g.setProperty("window", g)

        // console 桩(脚本调试用,输出到 logcat)
        val console = context.createNewJSObject()
        console.setProperty("log", JSCallFunction { a ->
            Log.d("LxScript", a.joinToString(" ") { it?.toString() ?: "null" }); null
        })
        console.setProperty("error", JSCallFunction { a ->
            Log.e("LxScript", a.joinToString(" ") { it?.toString() ?: "null" }); null
        })
        console.setProperty("warn", JSCallFunction { a ->
            Log.w("LxScript", a.joinToString(" ") { it?.toString() ?: "null" }); null
        })
        g.setProperty("console", console)

        // setTimeout/clearTimeout 桩(部分脚本引用,立即执行不延迟)
        context.evaluate(
            "globalThis.setTimeout=function(fn){if(typeof fn==='function')try{fn()}catch(e){}return 0};" +
            "globalThis.clearTimeout=function(){};"
        )

        // Buffer 桩(部分脚本直接用 Buffer.from 而非 lx.utils.buffer.from)
        context.evaluate(
            "globalThis.Buffer={from:function(s){return s},toString:function(s){return s},bufToString:function(s){return s}};"
        )

        // JS 包装:lx.request(url, options[, callback]) — 同时支持 Promise 和 callback 模式
        //
        // LX 脚本两种标准用法:
        //   1. Promise模式(主流): const resp = await lx.request(url, options)
        //   2. Callback模式(旧脚本): lx.request(url, options, (err, resp, body) => {})
        //
        // __nativeRequest 是同步 OkHttp 调用,所以两种模式都可以同步拿到结果:
        //   - callback: 同步调用 callback(null, resp, body)
        //   - Promise:  返回 Promise.resolve(resp),await 后通过微任务泵送拿到 resp
        //
        // 关键修复:之前只支持 callback 模式,返回 function(){}。
        //   野花脚本用 await lx.request(url, options),await 得到空函数而非响应对象,
        //   访问 resp.body 得到 undefined → 脚本抛出"服务器异常"。
        //   现在无 callback 时返回 Promise,脚本 await 即可正确拿到 resp。
        //
        // 响应体 body 自动 JSON.parse:成功为对象(脚本可直接 .vinfo/.url 访问),失败保留字符串。
        // 用纯 JS 对象重新组装 resp,避免宿主 JSObject 属性修改不生效。
        context.evaluate(
            "(function(){" +
            "lx.request=function(url,options,callback){" +
            "options=options||{};" +
            "var m=options.method||'GET';" +
            "var b=options.body||'';" +
            "var h=options.headers?JSON.stringify(options.headers):'';" +
            "var f=options.form?JSON.stringify(options.form):'';" +
            "var fd=options.formData?JSON.stringify(options.formData):'';" +
            // 核心请求逻辑:同步调 __nativeRequest,组装纯 JS resp 对象
            "function doRequest(){" +
            "var r=lx.__nativeRequest(url,m,b,h,f,fd);" +
            "var body=r.body;" +
            "try{body=JSON.parse(body)}catch(e){}" +
            "var resp={statusCode:r.statusCode,body:body,headers:r.headers,url:r.url,ok:r.ok};" +
            "console.log('lx.request ok: '+url+' code='+resp.statusCode+' bodyType='+(typeof body)+' bodyKeys='+(body&&typeof body==='object'?Object.keys(body).slice(0,10).join(','):'N/A'));" +
            "return resp" +
            "}" +
            // callback 模式:同步调 callback,返回 cancel 函数
            "if(typeof callback==='function'){" +
            "try{var resp=doRequest();callback(null,resp,resp.body)}" +
            "catch(e){console.log('lx.request err: '+url+' '+(e&&e.message?e.message:e));callback(e.message||String(e),null,null)}" +
            "return function(){}" +
            "}" +
            // Promise 模式:返回 Promise,resolve(resp) 需微任务泵送
            "return new Promise(function(resolve,reject){" +
            "try{resolve(doRequest())}" +
            "catch(e){console.log('lx.request err: '+url+' '+(e&&e.message?e.message:e));reject(e)}" +
            "})" +
            "};" +
            "})();"
        )

        // utils.crypto + buffer
        // 官方文档参数顺序:aesEncrypt(buffer, mode, key, iv) / aesDecrypt(buffer, mode, key, iv)
        // LxCrypto 签名: aesEncrypt(data, key, iv, mode) — 需要映射参数
        val utils = context.createNewJSObject()
        val crypto = context.createNewJSObject()
        crypto.setProperty("md5", JSCallFunction { a -> LxCrypto.md5(a.strOr(0)) })
        crypto.setProperty("randomBytes", JSCallFunction { a -> LxCrypto.randomBytesHex(a.intOr(0)) })
        // 映射: JS(buffer,mode,key,iv) → Kotlin(data,key,iv,mode)
        crypto.setProperty("aesEncrypt", JSCallFunction { a ->
            LxCrypto.aesEncrypt(a.strOr(0), a.strOr(2), a.strOr(3), a.strOr(1))
        })
        crypto.setProperty("aesDecrypt", JSCallFunction { a ->
            LxCrypto.aesDecrypt(a.strOr(0), a.strOr(2), a.strOr(3), a.strOr(1))
        })
        crypto.setProperty("rsaEncrypt", JSCallFunction { a -> LxCrypto.rsaEncrypt(a.strOr(0), a.strOr(1)) })
        utils.setProperty("crypto", crypto)
        // buffer: from(str, format) 返回字符串(字符串本身有 length 属性)
        // crypto 函数用 strOr() 取字符串,兼容简单脚本
        val buffer = context.createNewJSObject()
        buffer.setProperty("from", JSCallFunction { a -> a.strOr(0) })
        buffer.setProperty("bufToString", JSCallFunction { a -> a.strOr(0) })
        utils.setProperty("buffer", buffer)
        lx.setProperty("utils", utils)
    }

    private fun captureInited(data: JSObject) {
        runCatching {
            val sources = data.getProperty("sources") as? JSObject ?: return
            // sources 结构:{ wy: { name, type, actions:[...], qualitys:[...] }, ... }
            for (key in listOf("wy", "kg", "kw", "tx", "mg")) {
                val s = sources.getProperty(key) as? JSObject ?: continue
                // 解析 qualitys 数组
                val qualitys = mutableListOf<String>()
                val qArr = s.getProperty("qualitys")
                if (qArr is com.whl.quickjs.wrapper.JSArray) {
                    for (i in 0 until qArr.length()) {
                        qualitys.add(qArr.get(i).toString())
                    }
                }
                declaredSources[key] = qualitys
                Log.d(TAG, "inited: $key qualitys=$qualitys")
            }
        }.onFailure { Log.w(TAG, "captureInited fail", it) }
    }

    /**
     * 同步 HTTP 请求(供 JS 侧 __nativeRequest 调用,返回 JSObject 给 callback)。
     *
     * @param url 请求地址
     * @param method HTTP 方法(GET/POST/PUT/DELETE)
     * @param body 请求体(POST/PUT 用)
     * @param headersJson headers 的 JSON 字符串(JS 侧 JSON.stringify 转换)
     * @param formJson form 的 JSON 字符串(表单提交)
     * @return JSObject {statusCode, body, headers}(供 callback(null, resp))
     *
     * headers 透传:解析 JSON 字符串为 org.json.JSONObject,逐项添加到 Request.Builder。
     * 关键:之前这里返回 null 导致反爬校验失败 → 取流失败,现在正确透传 UA/Referer/Cookie。
     */
    private fun doRequestSync(
        url: String, method: String, body: String?,
        headersJson: String?, formJson: String?, formDataJson: String? = null
    ): JSObject {
        val builder = Request.Builder().url(url)
        // 透传 headers
        // 关键:header 值可能是数组(如野花的 tag: ["3a52","7a958b",...]),
        // Node.js/LX音乐会把数组用逗号拼接成 "3a52,7a958b,...",
        // 不能序列化成 JSON 字符串 '["3a52","7a958b",...]',否则服务器校验失败返回 404。
        // 同时 OkHttp 拒绝包含换行符的 header 值,需清理。
        val headerLog = StringBuilder()
        if (!headersJson.isNullOrEmpty() && headersJson != "null") {
            runCatching {
                val headers = org.json.JSONObject(headersJson)
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = when (val raw = headers.opt(k)) {
                        is org.json.JSONArray -> (0 until raw.length()).joinToString(",") { raw.optString(it) }
                        else -> raw?.toString() ?: ""
                    }
                    val cleaned = v.replace("\n", "").replace("\r", "").trim()
                    builder.header(k, cleaned)
                    headerLog.append("$k=$cleaned; ")
                }
            }.onFailure { Log.w(TAG, "parse headers fail: $headersJson", it) }
        }
        Log.d(TAG, "__nativeRequest headers: $headerLog")
        // multipart/form-data 文件上传(优先级最高)
        if (!formDataJson.isNullOrEmpty() && formDataJson != "null") {
            runCatching {
                val form = org.json.JSONObject(formDataJson)
                val multipart = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                val keys = form.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = form.opt(k)?.toString() ?: ""
                    val file = java.io.File(v)
                    if (file.exists() && file.isFile) {
                        multipart.addFormDataPart(k, file.name,
                            file.asRequestBody("application/octet-stream".toMediaType()))
                    } else {
                        multipart.addFormDataPart(k, v)
                    }
                }
                builder.post(multipart.build())
            }.onFailure { Log.w(TAG, "parse formData fail: $formDataJson", it) }
        } else if (!formJson.isNullOrEmpty() && formJson != "null") {
            runCatching {
                val form = org.json.JSONObject(formJson)
                val formBody = okhttp3.FormBody.Builder()
                val keys = form.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    formBody.add(k, form.optString(k))
                }
                builder.post(formBody.build())
            }
        } else when (method) {
            "POST" -> builder.post((body ?: "").toRequestBody())
            "PUT" -> builder.put((body ?: "").toRequestBody())
            "DELETE" -> builder.delete((body ?: "").toRequestBody())
            else -> builder.get()
        }
        val resp = http.newCall(builder.build()).execute()
        val respObj = context.createNewJSObject()
        respObj.setProperty("statusCode", resp.code)
        respObj.setProperty("body", resp.body?.string() ?: "")
        respObj.setProperty("url", resp.request.url.toString())
        respObj.setProperty("ok", resp.code in 200..299)
        // 回填 response headers(部分脚本读取 Set-Cookie)
        val respHeaders = context.createNewJSObject()
        resp.headers.forEach { respHeaders.setProperty(it.first, it.second) }
        respObj.setProperty("headers", respHeaders)
        resp.close()
        return respObj
    }

    /**
     * 解析播放 URL:调用脚本注册的 request 处理器(musicUrl 动作)。
     *
     * LX 脚本约定:处理器签名 (arg) => Promise<{url}|string>
     *  - arg: { source, action:'musicUrl', info: { type:quality, musicInfo:{ songmid } } }
     *  - 返回: { url: 'https://...' } 或直接字符串
     *
     * 本引擎用 async IIFE 包装,通过 pump 驱动 QuickJS 微任务队列,
     * 等待 Promise 落定后从 globalThis.__lx_url 取结果。
     * 失败时把 JS 异常写入 lastError 供 UI 显示。
     *
     * @return 播放直链;失败返回空(此时查 lastError)
     */
    fun resolveMusicUrl(source: String, songmid: String, quality: String): String {
        lastError = ""
        // 检查脚本是否注册了 request 处理器
        if (context.globalObject.getProperty("__lx_handler") == null) {
            lastError = "脚本未注册 request 处理器(缺少 lx.on(EVENT_NAMES.request, ...))"
            return ""
        }
        return try {
            val arg = context.createNewJSObject()
            arg.setProperty("source", source)
            arg.setProperty("action", "musicUrl")
            val infoObj = context.createNewJSObject()
            val musicInfo = context.createNewJSObject()
            // LX 标准字段 songmid;同时兼容不同脚本用的 songId/hash 字段名
            musicInfo.setProperty("songmid", songmid)
            musicInfo.setProperty("songId", songmid)
            musicInfo.setProperty("hash", songmid)
            infoObj.setProperty("type", quality)
            infoObj.setProperty("musicInfo", musicInfo)
            arg.setProperty("info", infoObj)
            context.globalObject.setProperty("__lx_arg", arg)

            // 重置结果槽位,跑 async IIFE
            // __lx_url: null=未完成, string=已完成(可能空)
            // __lx_err: null=未完成, string=已完成(含完整错误信息)
            context.evaluate(
                "globalThis.__lx_url=null;globalThis.__lx_err=null;" +
                    "(async()=>{try{const r=await globalThis.__lx_handler(globalThis.__lx_arg);" +
                    "globalThis.__lx_url=(r&&r.url)?r.url:(typeof r==='string'?r:'')}" +
                    "catch(e){" +
                    "var msg=e&&(e.message||e);" +
                    "var stack=e&&e.stack?('\\nstack:\\n'+e.stack):'';" +
                    "var info='';" +
                    "try{info=JSON.stringify({keys:Object.keys(e||{}),typeof:typeof e,val:String(e)})}catch(_){}" +
                    "globalThis.__lx_err=String(msg)+stack+info" +
                    "}})();"
            )
            // 泵送事件循环等待 Promise 落定(带上限,避免无限循环)
            var url = ""
            var waitCount = 0
            while (waitCount < 200) {
                pump(1)
                waitCount++
                // 优先检查错误槽位
                val err = context.globalObject.getProperty("__lx_err")
                if (err is String && err.isNotEmpty()) {
                    // 去掉 QuickJS 注入的噪音:<anonymous>(unknown.js) 等
                    lastError = cleanJsError(err)
                    Log.w(TAG, "resolveMusicUrl JS error: $lastError (pump=$waitCount)")
                    return ""
                }
                // 检查 URL 槽位(null=未完成, string=已完成)
                val u = context.globalObject.getProperty("__lx_url")
                if (u is String) {
                    url = u
                    Log.d(TAG, "resolveMusicUrl resolved: '$url' (pump=$waitCount)")
                    break
                }
                // 没结果也没错误,继续泵送
            }
            if (url.isEmpty() && lastError.isEmpty()) {
                lastError = if (waitCount >= 200)
                    "Promise 未在预期时间内落定(可能网络请求超时或脚本卡死)"
                else "音源未返回播放 URL"
            }
            url
        } catch (e: Throwable) {
            Log.w(TAG, "resolveMusicUrl fail", e)
            lastError = cleanJsError(e.message ?: e.javaClass.simpleName)
            ""
        }
    }

    /**
     * 解析歌词:调用脚本注册的 request 处理器(lyric 动作)。
     *
     * LX 脚本约定:处理器签名 (arg) => Promise<{lyric}|string>
     *  - arg: { source, action:'lyric', info: { musicInfo: { songmid } } }
     *  - 返回: { lyric: 'LRC文本' } 或直接字符串
     *
     * @return LRC 歌词文本;空字符串表示无歌词或脚本不支持
     */
    fun resolveLyric(source: String, songmid: String): String {
        if (context.globalObject.getProperty("__lx_handler") == null) return ""
        return try {
            val arg = context.createNewJSObject()
            arg.setProperty("source", source)
            arg.setProperty("action", "lyric")
            val infoObj = context.createNewJSObject()
            val musicInfo = context.createNewJSObject()
            musicInfo.setProperty("songmid", songmid)
            musicInfo.setProperty("songId", songmid)
            musicInfo.setProperty("hash", songmid)
            infoObj.setProperty("musicInfo", musicInfo)
            arg.setProperty("info", infoObj)
            context.globalObject.setProperty("__lx_arg", arg)

            context.evaluate(
                "globalThis.__lx_lyric=null;globalThis.__lx_err=null;" +
                    "(async()=>{try{const r=await globalThis.__lx_handler(globalThis.__lx_arg);" +
                    "globalThis.__lx_lyric=(r&&r.lyric)?r.lyric:(typeof r==='string'?r:'')" +
                    "}catch(e){globalThis.__lx_err=String(e&&(e.message||e))}})();"
            )
            var lyric = ""
            var waitCount = 0
            while (waitCount < 200) {
                pump(1)
                waitCount++
                val err = context.globalObject.getProperty("__lx_err")
                if (err is String && err.isNotEmpty()) {
                    Log.w(TAG, "resolveLyric JS error: $err (pump=$waitCount)")
                    return ""
                }
                val l = context.globalObject.getProperty("__lx_lyric")
                if (l is String) {
                    lyric = l
                    Log.d(TAG, "resolveLyric resolved: len=${lyric.length} (pump=$waitCount)")
                    break
                }
            }
            lyric
        } catch (e: Throwable) {
            Log.w(TAG, "resolveLyric fail", e)
            ""
        }
    }

    /** 泵送 QuickJS 微任务队列(wrapper 在 evaluate 后内部执行 pending jobs,故用空 evaluate 驱动) */
    private fun pump(times: Int) {
        repeat(times) { runCatching { context.evaluate("void 0;") } }
    }

    override fun close() {
        runCatching { context.destroy() }
    }
}

private fun Array<Any?>.strOr(i: Int): String = getOrNull(i)?.toString() ?: ""
private fun Array<Any?>.intOr(i: Int): Int = when (val v = getOrNull(i)) {
    is Int -> v; is Long -> v.toInt(); is Double -> v.toInt()
    is String -> v.toIntOrNull() ?: 0; else -> 0
}

/**
 * 检测并解码 Base64 编码的 URL。
 * 部分 LX 脚本(如六音)将 URL 进行 Base64 编码(如 aHR0cDov...),
 * 解码后是 http://... 开头的标准 URL。
 * 判断标准:不以 http 开头,且 Base64 解码后以 http 开头。
 */
private fun decodeBase64Url(url: String): String {
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    // 尝试 Base64 解码(URL safe + 标准)
    return runCatching {
        val cleanBase64 = url.trim().replace("-", "+").replace("_", "/")
        // 补齐 padding
        val padded = when (cleanBase64.length % 4) {
            2 -> "$cleanBase64=="
            3 -> "$cleanBase64="
            else -> cleanBase64
        }
        val decoded = android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        val str = String(decoded, Charsets.UTF_8)
        if (str.startsWith("http://") || str.startsWith("https://")) {
            android.util.Log.d("LxEngine", "decodeBase64Url: $url → $str")
            str
        } else url
    }.getOrDefault(url)
}

/**
 * 清理 QuickJS 抛出的错误消息,去掉注入的噪音(文件名/行号/栈帧),
 * 保留用户可读的错误主体。
 * 例:"it is not defined at <anonymous>(unknown.js:1:23)" → "it is not defined"
 */
private fun cleanJsError(raw: String): String {
    var s = raw.trim()
    // 去掉 " at <anonymous>(unknown.js...)" / " at :1:23" 等 JS 栈帧尾巴
    s = s.replace(Regex("\\s+at\\s+<anonymous>\\([^)]*\\)"), "")
    s = s.replace(Regex("\\s+at\\s+\\S+\\(unknown\\.js[^)]*\\)"), "")
    s = s.replace(Regex("\\s+at\\s+\\d+:\\d+"), "")
    s = s.replace(Regex("\\s+at\\s+<eval>\\([^)]*\\)"), "")
    // 去掉尾部多余空格
    return s.trim().ifBlank { raw.trim() }
}
