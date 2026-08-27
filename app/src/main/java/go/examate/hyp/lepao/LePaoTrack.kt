package go.examate.hyp.lepao


import android.graphics.Bitmap
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * 步道乐跑轨迹生成 + OSS上传 + 预览图
 */
object LePaoTrack {

    /** GPS轨迹点: (lat, lng, speed, timeSec) */
    data class TrackPoint(
        val lat: Double,
        val lng: Double,
        val speed: Float,
        val t: Float
    )

    /**
     * 通过路线点生成平滑轨迹（路线即全程，不再乘圈数）
     * @param waypoints 路线拐点列表
     * @param targetSpeed 平均速度 m/s
     * @param intervalS 采样间隔秒
     * @param warmupS 起步加速时间
     * @param cooldownS 结束减速时间
     */
    fun generateRoute(
        waypoints: List<Pair<Double, Double>>,
        targetSpeed: Float = 3.0f,
        intervalS: Float = 1.0f,
        warmupS: Float = 10f,
        cooldownS: Float = 8f
    ): List<TrackPoint> {
        val pts = mutableListOf<TrackPoint>()
        var t = 0f
        val rng = java.util.Random(System.currentTimeMillis())

        // 先计算总距离以确定 warmup/cooldown 边界
        var totalDistM = 0.0
        for (i in 0 until waypoints.size - 1) {
            val (lat0, lng0) = waypoints[i]
            val (lat1, lng1) = waypoints[i + 1]
            totalDistM += sqrt((lat1 - lat0).pow(2) + (lng1 - lng0).pow(2)) * 111320.0
        }
        val totalTimeS = (totalDistM / targetSpeed).toFloat()
        val warmupEnd = warmupS
        val cooldownStart = totalTimeS - cooldownS

        for (i in 0 until waypoints.size - 1) {
            val (lat0, lng0) = waypoints[i]
            val (lat1, lng1) = waypoints[i + 1]
            val segDistM = sqrt((lat1 - lat0).pow(2) + (lng1 - lng0).pow(2)) * 111320.0
            val nSteps = max((segDistM / (targetSpeed * intervalS)).toInt(), 2)

            // 每段包含终点(frac=1.0)，非首段跳过起点避免重复
            val startJ = if (i == 0) 0 else 1
            for (j in startJ..nSteps) {
                val frac = j.toFloat() / nSteps
                val e = frac * frac * (3 - 2 * frac)
                val jitterLat = (rng.nextDouble() - 0.5) * 4e-5
                val jitterLng = (rng.nextDouble() - 0.5) * 4e-5
                val lat = lat0 + (lat1 - lat0) * e + jitterLat
                val lng = lng0 + (lng1 - lng0) * e + jitterLng

                // 速度模型：双频正弦波（慢波90s±8% + 快波30s±4%）+ 小噪声
                var speed = targetSpeed * (1f + 0.08f * sin(t / 15f) + 0.04f * sin(t / 5f))
                speed += (rng.nextGaussian() * 0.03).toFloat()
                // 1%概率短暂减速（障碍/红绿灯）
                if (rng.nextDouble() < 0.01f) speed *= 0.7f + rng.nextFloat() * 0.15f

                // warmup：ease-in-out 乘法因子（0.3→1.0），保留轻微波动
                if (t < warmupEnd) {
                    val w = t / warmupEnd
                    speed *= 0.3f + 0.7f * (w * w * (3 - 2 * w))
                }
                // cooldown：干净减速到停（1.0→0.3），不叠加任何噪声/波动
                if (t > cooldownStart) {
                    val c = ((t - cooldownStart) / cooldownS).coerceIn(0f, 1f)
                    val smoothC = c * c * (3 - 2 * c)
                    speed = targetSpeed * (1f - 0.7f * smoothC)
                }

                speed = speed.coerceIn(0.5f, 5.0f)
                pts.add(TrackPoint(lat, lng, (speed * 100).toInt() / 100f, t))
                t += intervalS
            }
        }

        // 缩放时间使总时间精确等于 totalDistM/targetSpeed，消除 intervalS 取整误差
        val actualTotalT = pts.lastOrNull()?.t ?: 0f
        if (actualTotalT > 0f && totalTimeS > 0f) {
            val scale = totalTimeS / actualTotalT
            for (i in pts.indices) {
                val p = pts[i]
                pts[i] = TrackPoint(p.lat, p.lng, p.speed, p.t * scale)
            }
        }

        return pts
    }


    /** 精确计算总距离(米) */
    fun calcDistance(pts: List<TrackPoint>): Float {
        var total = 0.0
        for (i in 1 until pts.size) {
            val dLat = (pts[i].lat - pts[i - 1].lat) * 111320
            val dLng = (pts[i].lng - pts[i - 1].lng) * 111320 *
                    cos(Math.toRadians(pts[i].lat))
            total += sqrt(dLat.pow(2) + dLng.pow(2))
        }
        return total.toFloat()
    }

    /**
     * 加密轨迹文件（匹配真实数据格式）
     */
    fun encryptTrack(pts: List<TrackPoint>): String {
        val arr = JSONArray()
        var cumM = 0.0

        for (i in pts.indices) {
            val p = pts[i]
            val obj = JSONObject()
            obj.put("o", p.lng)
            obj.put("a", p.lat)
            obj.put("s", p.speed)
            if (i > 0) {
                val prev = pts[i - 1]
                val dLatM = (p.lat - prev.lat) * 111320
                val dLngM = (p.lng - prev.lng) * 111320 * cos(Math.toRadians(p.lat))
                cumM += sqrt(dLatM.pow(2) + dLngM.pow(2))
            }
            if (i == pts.size / 2 && pts.size > 20) {
                obj.put("p", 1)
            }
            if (i == pts.size - 1 && pts.size > 1) {
                val prev = pts[pts.size - 2]
                val bearing = atan2((p.lng - prev.lng) * cos(Math.toRadians(p.lat)), p.lat - prev.lat)
                obj.put("b", bearing)
                obj.put("c", cumM.toInt())
            }
            arr.put(obj)
        }

        val plaintext = arr.toString()
        return LePaoCrypto.aesEncrypt(
            plaintext.toByteArray(), LePaoCrypto.KEY_TRK, LePaoCrypto.IV_TRK
        )
    }

    /**
     * 生成跑步预览图（高德风格）
     */
    fun genPreview(pts: List<TrackPoint>, width: Int = 720, height: Int = 960): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.rgb(230, 234, 238))

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
        }

        // 街区色块
        paint.color = android.graphics.Color.rgb(222, 226, 230)
        for (i in 0..15) {
            val bx = (i * 47) % (width - 60)
            val by = (i * 73) % (height - 60)
            canvas.drawRect(bx.toFloat(), by.toFloat(),
                (bx + 50).toFloat(), (by + 40).toFloat(), paint)
        }
        // 绿地
        paint.color = android.graphics.Color.rgb(200, 228, 200)
        for (i in 0..3) {
            val bx = ((i * 137) % (width - 100)).toFloat()
            val by = ((i * 211) % (height - 100)).toFloat()
            canvas.drawRoundRect(android.graphics.RectF(bx, by, bx + 80, by + 60),
                10f, 10f, paint)
        }

        // 道路白线
        paint.color = android.graphics.Color.WHITE
        paint.strokeWidth = 6f
        for (i in 1..3) {
            val y = height * i / 4f
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
        for (i in 1..4) {
            val x = width * i / 5f
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        }

        // 路线轨迹
        if (pts.isNotEmpty()) {
            val lats = pts.map { it.lat }
            val lngs = pts.map { it.lng }
            val pad = 80f
            val latR = (lats.max() - lats.min()).takeIf { it > 0 } ?: 0.001
            val lngR = (lngs.max() - lngs.min()).takeIf { it > 0 } ?: 0.001

            fun toX(lng: Double) =
                pad + ((lng - lngs.min()) / lngR * (width - 2 * pad)).toFloat()
            fun toY(lat: Double) =
                pad + ((1 - (lat - lats.min()) / latR) * (height - 2 * pad)).toFloat()

            val path = android.graphics.Path()
            path.moveTo(toX(pts[0].lng), toY(pts[0].lat))
            for (i in 1 until pts.size) {
                path.lineTo(toX(pts[i].lng), toY(pts[i].lat))
            }

            // 白色描边
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 10f
            paint.color = android.graphics.Color.WHITE
            canvas.drawPath(path, paint)
            // 蓝色路线
            paint.strokeWidth = 7f
            paint.color = android.graphics.Color.rgb(64, 158, 255)
            canvas.drawPath(path, paint)

            // 起终点标记
            paint.style = android.graphics.Paint.Style.FILL
            paint.strokeWidth = 3f
            // 起点 绿色
            paint.color = android.graphics.Color.rgb(76, 175, 80)
            canvas.drawCircle(toX(pts[0].lng), toY(pts[0].lat), 14f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.style = android.graphics.Paint.Style.STROKE
            canvas.drawCircle(toX(pts[0].lng), toY(pts[0].lat), 14f, paint)
            // 终点 红色
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = android.graphics.Color.rgb(244, 67, 54)
            canvas.drawCircle(toX(pts.last().lng), toY(pts.last().lat), 14f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.style = android.graphics.Paint.Style.STROKE
            canvas.drawCircle(toX(pts.last().lng), toY(pts.last().lat), 14f, paint)
        }

        return bmp
    }

    fun bitmapToPngBytes(bmp: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, stream)
        return stream.toByteArray()
    }

    /**
     * 从 OSS 下载加密轨迹文件并解析为路线拐点列表
     * @param recordFileKey OSS 路径 (Public/Upload/file/run_record/...txt)
     * @param targetCount 目标拐点数（下采样密度），路线导入用20，详情展示用更大值
     * @return 路线拐点列表 (lat, lng)，失败返回 null
     */
    private val sharedClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun downloadAndParseTrack(recordFileKey: String, targetCount: Int = 20): List<Pair<Double, Double>>? {
        val url = if (recordFileKey.startsWith("http")) recordFileKey
                  else "https://lptiyu-data.oss-cn-hangzhou.aliyuncs.com/$recordFileKey"
        return downloadTrackFromUrl(url, targetCount)
    }

    fun downloadTrackFromUrl(url: String, targetCount: Int = 20): List<Pair<Double, Double>>? {
        try {

            val req = okhttp3.Request.Builder().url(url).get().build()
            val resp = sharedClient.newCall(req).execute()
            if (resp.code != 200) {
                Log.e("LePaoTrack", "download track failed: ${resp.code}")
                return null
            }
            val encrypted = resp.body?.bytes() ?: return null
            val decrypted = LePaoCrypto.aesDecrypt(
                String(encrypted), LePaoCrypto.KEY_TRK, LePaoCrypto.IV_TRK
            )
            val jsonStr = String(decrypted)
            val arr = JSONArray(jsonStr)

            // 提取经纬度，下采样为路线拐点（每 N 个点取一个，保持形状）
            val allPts = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val lat = obj.optDouble("a", 0.0)
                val lng = obj.optDouble("o", 0.0)
                if (lat != 0.0 && lng != 0.0) allPts.add(lat to lng)
            }
            if (allPts.size < 2) return null

            // 下采样：目标拐点数，保持路线形状
            val step = (allPts.size / targetCount).coerceAtLeast(1)
            val sampled = mutableListOf<Pair<Double, Double>>()
            for (i in allPts.indices step step) sampled.add(allPts[i])
            if (sampled.last() != allPts.last()) sampled.add(allPts.last())
            return sampled
        } catch (e: Exception) {
            Log.e("LePaoTrack", "parse track error", e)
            return null
        }
    }
}




