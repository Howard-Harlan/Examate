package go.examate.hyp.lepao

import android.graphics.Bitmap
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跑步上传公共流程执行器 — 合并 LePaoRunner 与 LePaoCampusRunner 的重复逻辑：
 * - 人脸验证（OSS占位图 + faceVerify）
 * - GPS 心跳上报
 * - OSS 文件上传（轨迹文件 + 预览图）
 * - 时间一致性等待
 */
internal object LePaoRunExecutor {

    /** OSS 上传结果 */
    data class OssUploadResult(val recordFileKey: String, val fileImgKey: String, val stsData: JSONObject? = null, val dateStr: String = "", val uuid: String = "", val shard: Int = 0)

    /**
     * 执行人脸验证 — 上传占位照片到OSS后调faceVerify
     * @return run_face_verify_id 或 null（失败不阻塞跑步）
     */
    fun performFaceVerify(api: LePaoApi, verifyType: Int, priorId: String?): String? {
        return try {
            val sts = api.getOssSts()
            if (sts.optInt("status") != 1) return null
            val stsData = sts.getJSONObject("data")
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val faceKey = "Public/Upload/pic/face_verify/$dateStr/${LePaoApi.randomOssShard()}/face_${System.currentTimeMillis()}.jpg"
            // 1x1 PNG 占位图（实际场景需要摄像头拍照）
            val placeholderPng = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00
            )
            if (!LePaoOss.upload(stsData, faceKey, placeholderPng, "image/png")) return null

            val result = api.faceVerify(faceKey, verifyType, priorId)
            if (result.optInt("status") == 1) {
                result.optJSONObject("data")?.optString("run_face_verify_id", "")?.ifEmpty { null }
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * GPS 心跳上报 — 按步长采样上报位置
     * @param stepDivisor 采样密度（pts.size / stepDivisor = 步长）
     * @param onMiddleCheck 可选的中途检查回调（在约50%进度时触发）
     */
    fun reportGpsHeartbeats(
        api: LePaoApi,
        pts: List<LePaoTrack.TrackPoint>,
        gameId: String,
        stepDivisor: Int = 10,
        onMiddleCheck: (() -> Unit)? = null
    ) {
        val step = maxOf(pts.size / stepDivisor, 1)
        val midPoint = pts.size / 2
        var middleTriggered = false
        var i = 0
        while (i < pts.size) {
            api.uploadLocation(pts[i].lat, pts[i].lng, gameId)
            if (onMiddleCheck != null && !middleTriggered && i >= midPoint) {
                onMiddleCheck()
                middleTriggered = true
            }
            Thread.sleep(200)
            i += step
        }
    }

    /**
     * 上传轨迹文件 + 预览图到 OSS
     * @return OssUploadResult（失败时 key 为空字符串）
     */
    fun uploadTrackAndPreview(
        api: LePaoApi,
        pts: List<LePaoTrack.TrackPoint>,
        customPreview: Bitmap?,
        onProgress: (String) -> Unit
    ): OssUploadResult {
        val stsResult = api.getOssSts()
        if (stsResult.optInt("status") != 1) return OssUploadResult("", "")

        val stsData = stsResult.getJSONObject("data")
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val uuid = java.util.UUID.randomUUID().toString()
        val shard = LePaoApi.randomOssShard()

        // 轨迹文件
        val recordFileKey = "Public/Upload/file/run_record/$dateStr/$shard/$uuid.txt"
        val encTrack = LePaoTrack.encryptTrack(pts)
        val trackOk = LePaoOss.upload(stsData, recordFileKey, encTrack.toByteArray(), "text/plain")
        onProgress("轨迹上传: $trackOk")

        // 预览图
        val fileImgKey = "Public/Upload/pic/run_photo_screenshotImage/$dateStr/$shard/${uuid}_img.png"
        val previewBmp = customPreview ?: LePaoTrack.genPreview(pts)
        val imgOk = LePaoOss.upload(stsData, fileImgKey, LePaoTrack.bitmapToPngBytes(previewBmp), "image/png")
        onProgress("预览图上传: $imgOk")

        return OssUploadResult(recordFileKey, fileImgKey, stsData, dateStr, uuid, shard)
    }

    /**
     * 时间一致性处理 — 不再阻塞等待，直接返回距离。
     * 调用方用 endTs=当前时间, startTs=endTs-trackDurationS 构造时间戳。
     */
    fun waitForTimeConsistency(
        authorizedAt: Long,
        trackDurationS: Int,
        targetSpeed: Float,
        distanceM: Float,
        onProgress: (String) -> Unit
    ): Float {
        return distanceM
    }

}
