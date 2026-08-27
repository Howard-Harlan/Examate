package go.examate.hyp.lepao

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 校园跑(定向乐跑)完整上传流程
 * 公共流程委托给 LePaoRunExecutor，保留校园跑特有逻辑（打卡点/传感器/设备检查）。
 */
class LePaoCampusRunner(private val api: LePaoApi) {

    fun uploadCampusRun(
        waypoints: List<Pair<Double, Double>>,
        targetSpeed: Float = 3.0f,
        stepLengthM: Float = 1.5f,
        customPreview: Bitmap? = null,
        warmupS: Float = 10f,
        cooldownS: Float = 8f,
        intervalS: Float = 2.0f,

        onProgress: (String) -> Unit = {}
    ): JSONObject {
        // ===== Phase 1: 授权准备 =====
        onProgress("获取跑步配置...")
        val br = api.beforeRun(runType = "1")
        if (br.optInt("status") != 1) return br
        val brData = br.optJSONObject("data") ?: JSONObject()
        val gameId = brData.optString("game_id", "691")
        val authorizedAt = System.currentTimeMillis() / 1000

        val isCheckDevice = brData.optInt("is_check_device", 0) == 1
        val gyroscopeType = brData.optInt("gyroscope_type", 0)
        val faceConfig = brData.optJSONObject("run_face_verify_config") ?: JSONObject()
        val faceStartStatus = faceConfig.optInt("start_status", 0)
        val faceMiddleStatus = faceConfig.optInt("middle_status", 0)
        val faceStopStatus = faceConfig.optInt("stop_status", 0)
        val faceVerifyEnabled = faceStartStatus == 1 || faceMiddleStatus == 1 || faceStopStatus == 1

        onProgress("配置: face=$faceVerifyEnabled device=$isCheckDevice gyro=$gyroscopeType")

        // 开始前设备检查
        if (isCheckDevice) {
            onProgress("设备指纹检查...")
            try { api.checkUserDevice() } catch (_: Exception) { }
        }

        // 开始前人脸验证
        var faceVerifyId: String? = null
        if (faceStartStatus == 1) {
            onProgress("开始人脸验证...")
            faceVerifyId = LePaoRunExecutor.performFaceVerify(api, 1, null)
        }

        onProgress("同步服务端时间...")
        val tsResp = api.getTimestamp(gameId)
        if (tsResp.optInt("status") != 1) return tsResp
        val timeStr = tsResp.optJSONObject("data")?.optString("str", "") ?: ""
        if (timeStr.isEmpty()) return JSONObject().put("status", 0).put("info", "获取timeStr失败")

        // 获取并注册打卡点 — 从全部点中随机选 point_num 个
        val runLineInfo = brData.optJSONObject("run_line_info")
        val allCheckpoints = runLineInfo?.optJSONArray("point_list") ?: JSONArray()
        val pointNum = runLineInfo?.optInt("point_num", 2) ?: 2
        val selectedCheckpoints = JSONArray()
        val checkpointIds = mutableListOf<Long>()
        if (allCheckpoints.length() > 0) {
            val indices = (0 until allCheckpoints.length()).toMutableList().shuffled().take(pointNum).sorted()
            for (idx in indices) {
                val cp = allCheckpoints.getJSONObject(idx)
                selectedCheckpoints.put(cp)
                checkpointIds.add(cp.optLong("id", 0))
            }
        }
        onProgress("选中 ${selectedCheckpoints.length()}/${allCheckpoints.length()} 个打卡点")
        if (checkpointIds.isNotEmpty()) api.registerCheckpoints(checkpointIds)

        // ===== Phase 2: 生成轨迹 =====
        onProgress("生成轨迹...")
        val pts = LePaoTrack.generateRoute(waypoints, targetSpeed, intervalS, warmupS, cooldownS)
        var distanceM = LePaoTrack.calcDistance(pts)
        val trackDurationS = pts.lastOrNull()?.t?.toInt() ?: 60

        val logData = buildLogData(selectedCheckpoints, pts, authorizedAt + 10)
        onProgress("构造打卡数据: ${logData.length()} 条记录")

        // ===== Phase 3: GPS心跳上报 + 中途人脸验证 =====
        onProgress("上报GPS位置...")
        LePaoRunExecutor.reportGpsHeartbeats(api, pts, gameId, stepDivisor = 8) {
            if (faceMiddleStatus == 1 && faceVerifyId == null) {
                onProgress("中途人脸验证...")
                faceVerifyId = LePaoRunExecutor.performFaceVerify(api, 2, faceVerifyId)
            }
        }

        // 时间一致性等待
        distanceM = LePaoRunExecutor.waitForTimeConsistency(authorizedAt, trackDurationS, targetSpeed, distanceM, onProgress)

        // ===== Phase 4: OSS文件上传 =====
        onProgress("上传OSS文件...")
        val ossResult = LePaoRunExecutor.uploadTrackAndPreview(api, pts, customPreview, onProgress)
        var sensorFileKey = ""

        // 传感器文件（校园跑特有，复用同一 STS 凭证）
        if (ossResult.stsData != null) {
            sensorFileKey = "Public/Upload/file/run_gyroscope/${ossResult.dateStr}/${ossResult.shard}/${ossResult.uuid}_sensor.txt"
            val sensorJson = generateSensorJson(pts)
            val sensorEnc = LePaoCrypto.aesEncrypt(sensorJson.toByteArray(), LePaoCrypto.KEY_TRK, LePaoCrypto.IV_TRK)
            val sensorOk = LePaoOss.upload(ossResult.stsData, sensorFileKey, sensorEnc.toByteArray(), "text/plain")
            onProgress("传感器上传: $sensorOk")
        }

        // ===== Phase 5: 提交stopRunV278 =====
        onProgress("提交校园跑记录...")
        val endTs = System.currentTimeMillis() / 1000
        val startTs = endTs - trackDurationS
        val steps = maxOf((distanceM / stepLengthM).toInt(), 1)
        // 步数加 ±2% 随机波动，每次提交不同
        val stepsWithJitter = (steps * (1.0 + (Random.nextDouble() - 0.5) * 0.04)).toInt().coerceAtLeast(1)

        val result = api.stopCampusRun(
            distanceM = distanceM,
            durationS = trackDurationS,
            stepNum = stepsWithJitter,
            startTs = startTs,
            endTs = endTs,
            recordFile = ossResult.recordFileKey,
            fileImg = ossResult.fileImgKey,
            timeStr = timeStr,
            logData = logData.toString(),
            termId = brData.optString("term_id", LePaoPrefs.DEFAULT_TERM_ID),
            gameId = gameId,
            passTotal = selectedCheckpoints.length(),
            passEffectiveNum = selectedCheckpoints.length(),
            targetSpeed = targetSpeed
        )

        // ===== Phase 6: 提交后处理 =====
        if (result.optInt("status") == 1) {
            val recordId = result.optJSONObject("data")?.optString("record_id") ?: ""

            if (gyroscopeType > 0 && sensorFileKey.isNotEmpty() && recordId.isNotEmpty()) {
                try {
                    api.uploadGyroscopeRef(recordId, sensorFileKey)
                    onProgress("传感器关联: OK")
                } catch (_: Exception) { }
            }

            if (faceStopStatus == 1 && faceVerifyId == null) {
                onProgress("结束人脸验证...")
                faceVerifyId = LePaoRunExecutor.performFaceVerify(api, 1, null)
            }
        }

        return result
    }

    /**
     * 构造log_data — 模拟跑步经过打卡点
     * 注意字段名 longtitude 是原始拼写错误！
     */
    private fun buildLogData(
        checkpointList: JSONArray,
        routePts: List<LePaoTrack.TrackPoint>,
        startTimeSec: Long
    ): JSONArray {
        val result = JSONArray()
        var lastIdx = 0

        // 预计算累计距离数组，避免 O(n²)
        val cumDist = FloatArray(routePts.size)
        for (i in 1 until routePts.size) {
            val dLat = (routePts[i].lat - routePts[i - 1].lat) * 111320.0
            val dLng = (routePts[i].lng - routePts[i - 1].lng) * 111320.0 * cos(Math.toRadians(routePts[i].lat))
            cumDist[i] = cumDist[i - 1] + sqrt(dLat * dLat + dLng * dLng).toFloat()
        }

        for (i in 0 until checkpointList.length()) {
            val cp = checkpointList.getJSONObject(i)
            val cpId = cp.optLong("id", 0)
            val jingwei = cp.optString("jingwei", "")
            val parts = jingwei.split(",")
            if (parts.size != 2) continue

            val cpLat = parts[0].toDoubleOrNull() ?: continue
            val cpLng = parts[1].toDoubleOrNull() ?: continue

            var nearestIdx = -1
            var nearestDist = Double.MAX_VALUE
            for (j in lastIdx until routePts.size) {
                val d = sqrt((routePts[j].lat - cpLat).pow2 + (routePts[j].lng - cpLng).pow2) * 111320.0
                if (d < nearestDist) { nearestDist = d; nearestIdx = j }
                if (nearestDist < 30.0 && j > lastIdx + 5) break
            }

            if (nearestIdx >= 0 && nearestDist < 60.0) {
                val cumDistM = cumDist[nearestIdx]
                val entry = JSONObject()
                entry.put("point_id", cpId)
                entry.put("time", startTimeSec + routePts[nearestIdx].t.toLong())
                entry.put("latitude", cpLat)
                entry.put("longtitude", cpLng)
                entry.put("distance", cumDistM.toDouble())
                result.put(entry)
                lastIdx = nearestIdx
            }
        }
        return result
    }

    /** 生成传感器JSON数据(>50点) — 正弦波模拟跑步摆动+高斯噪声 */
    private fun generateSensorJson(pts: List<LePaoTrack.TrackPoint>): String {
        val arr = JSONArray()
        if (pts.isEmpty()) return arr.toString()
        val rng = java.util.Random(System.currentTimeMillis())
        val baseTs = System.currentTimeMillis()
        val lastPt = pts.last()
        for ((i, pt) in pts.withIndex()) {
            if (i % 3 != 0 && arr.length() > 50) continue
            val obj = JSONObject()
            obj.put("n", String.format("%.6f", pt.lng))
            obj.put("a", String.format("%.6f", pt.lat))
            obj.put("t", baseTs + i * 1000L)
            // 加速度计：正弦波模拟跑步周期性摆动 + 高斯噪声
            obj.put("ax", String.format("%.2f", 0.3 * sin(i * 0.31) + rng.nextGaussian() * 0.05))
            obj.put("ay", String.format("%.2f", 9.8 + 0.4 * sin(i * 0.62) + rng.nextGaussian() * 0.03))
            obj.put("az", String.format("%.2f", 0.2 * cos(i * 0.31) + rng.nextGaussian() * 0.04))
            // 陀螺仪：小幅度周期性 + 噪声
            obj.put("gx", String.format("%.2f", 0.12 * sin(i * 0.35) + rng.nextGaussian() * 0.02))
            obj.put("gy", String.format("%.2f", 0.08 * cos(i * 0.38) + rng.nextGaussian() * 0.02))
            obj.put("gz", String.format("%.2f", 0.07 * sin(i * 0.46) + rng.nextGaussian() * 0.02))
            arr.put(obj)
        }
        while (arr.length() <= 50) {
            val i = arr.length()
            val obj = JSONObject()
            obj.put("n", String.format("%.6f", lastPt.lng))
            obj.put("a", String.format("%.6f", lastPt.lat))
            obj.put("t", baseTs + i * 1000L)
            obj.put("ax", String.format("%.2f", 0.3 * sin(i * 0.31) + rng.nextGaussian() * 0.05))
            obj.put("ay", String.format("%.2f", 9.8 + 0.4 * sin(i * 0.62) + rng.nextGaussian() * 0.03))
            obj.put("az", String.format("%.2f", 0.2 * cos(i * 0.31) + rng.nextGaussian() * 0.04))
            obj.put("gx", String.format("%.2f", 0.12 * sin(i * 0.35) + rng.nextGaussian() * 0.02))
            obj.put("gy", String.format("%.2f", 0.08 * cos(i * 0.38) + rng.nextGaussian() * 0.02))
            obj.put("gz", String.format("%.2f", 0.07 * sin(i * 0.46) + rng.nextGaussian() * 0.02))
            arr.put(obj)
        }
        return arr.toString()
    }

    private val Double.pow2 get() = this * this
}
