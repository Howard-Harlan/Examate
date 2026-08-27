package go.examate.hyp.lepao

import android.graphics.Bitmap
import org.json.JSONObject
import kotlin.random.Random

/**
 * 自由跑完整上传流程（含timeStr/传感器文件/动态IP）
 * 公共流程委托给 LePaoRunExecutor，仅保留自由跑特有逻辑。
 */
class LePaoRunner(private val api: LePaoApi) {

    /** 上传完整自由跑记录 */
    fun uploadRun(
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
        val br = api.beforeRun()
        if (br.optInt("status") != 1) return br
        val brData = br.optJSONObject("data") ?: JSONObject()
        val gameId = brData.optString("game_id", "691")
        val authorizedAt = System.currentTimeMillis() / 1000

        val faceConfig = brData.optJSONObject("run_face_verify_config") ?: JSONObject()
        val faceStartStatus = faceConfig.optInt("start_status", 0)
        val faceStopStatus = faceConfig.optInt("stop_status", 0)

        var faceVerifyId: String? = null
        if (faceStartStatus == 1) {
            onProgress("开始人脸验证...")
            faceVerifyId = LePaoRunExecutor.performFaceVerify(api, 1, null)
        }

        onProgress("同步服务端时间...")
        var timeStr = ""
        try {
            val tsResp = api.getTimestamp(gameId)
            if (tsResp.optInt("status") == 1) {
                timeStr = tsResp.optJSONObject("data")?.optString("str", "") ?: ""
            }
        } catch (_: Exception) { }
        if (timeStr.isEmpty()) return JSONObject().put("status", 0).put("info", "获取timeStr失败")

        // ===== Phase 2: 生成轨迹 =====
        onProgress("生成轨迹...")
        val pts = LePaoTrack.generateRoute(waypoints, targetSpeed, intervalS, warmupS, cooldownS)
        var distanceM = LePaoTrack.calcDistance(pts)
        val trackDurationS = pts.lastOrNull()?.t?.toInt() ?: 60

        // ===== Phase 3: GPS心跳上报 =====
        onProgress("上传GPS位置...")
        LePaoRunExecutor.reportGpsHeartbeats(api, pts, gameId, stepDivisor = 10)

        // 时间一致性等待
        distanceM = LePaoRunExecutor.waitForTimeConsistency(authorizedAt, trackDurationS, targetSpeed, distanceM, onProgress)

        // ===== Phase 4: OSS文件上传 =====
        onProgress("上传OSS文件...")
        val ossResult = LePaoRunExecutor.uploadTrackAndPreview(api, pts, customPreview, onProgress)

        // ===== Phase 5: 提交跑步记录 =====
        onProgress("提交跑步记录...")
        val endTs = System.currentTimeMillis() / 1000
        val startTs = endTs - trackDurationS
        val steps = maxOf((distanceM / stepLengthM).toInt(), 1)
        // 步数加 ±2% 随机波动，每次提交不同
        val stepsWithJitter = (steps * (1.0 + (Random.nextDouble() - 0.5) * 0.04)).toInt().coerceAtLeast(1)

        val result = api.stopFreeRun(
            distanceM = distanceM,
            durationS = trackDurationS,
            stepNum = stepsWithJitter,
            startTs = startTs,
            endTs = endTs,
            recordFile = ossResult.recordFileKey,
            fileImg = ossResult.fileImgKey,
            timeStr = timeStr
        )

        if (result.optInt("status") == 1 && faceStopStatus == 1 && faceVerifyId == null) {
            onProgress("结束人脸验证...")
            LePaoRunExecutor.performFaceVerify(api, 1, null)
        }

        return result
    }
}
