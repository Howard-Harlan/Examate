package go.examate.hyp.lepao

import kotlin.random.Random

/**
 * 配速计算器 — 基于真实轨迹数据校准
 * 真实数据(10次校园跑): 均速3.75~4.51 m/s, 配速4'02"~4'27"/km, 步长1.41~1.71m, 步频145~173spm
 * 安全区间: pace 180~600 秒/km (max_pace下限180, min_pace上限600)
 */
internal object LePaoPaceCalculator {

    /** 配速区间定义: index → (名称, 区间描述) */
    val PACE_OPTIONS = listOf(
        Triple(0, "快跑", "3'45\"~4'15\"/km"),
        Triple(1, "较快", "4'15\"~4'45\"/km"),
        Triple(2, "普通", "4'45\"~5'15\"/km"),
        Triple(3, "慢跑", "5'15\"~5'45\"/km")
    )

    data class PaceResult(
        val straightSpeed: Float,
        val curveSpeed: Float,
        val maxAccel: Float,
        val stepLengthM: Float,
        val cadenceSpm: Int,
        val gpsIntervalMs: Long
    )

    /**
     * 根据选择的配速区间与路线距离，计算合理的跑步参数
     * @param paceLevel 0=快跑 1=较快 2=普通 3=慢跑
     * @param distanceKm 路线距离(km)
     */
    fun calculate(paceLevel: Int, distanceKm: Float): PaceResult {
        val distKm = distanceKm.coerceAtLeast(0.3f)

        // 目标配速(秒/km) — 基于真实数据校准
        val targetPaceSecPerKm = when (paceLevel) {
            0 -> Random.nextInt(225, 255)  // 3'45"~4'15" (真实快跑区间)
            1 -> Random.nextInt(255, 285)  // 4'15"~4'45"
            2 -> Random.nextInt(285, 315)  // 4'45"~5'15"
            else -> Random.nextInt(315, 345) // 5'15"~5'45"
        }

        val durationSec = (distKm * targetPaceSecPerKm).toInt().coerceIn(120, 2400)
        val avgSpeed = distKm * 1000f / durationSec

        // 直道/弯道速度比：快跑弯道减速更明显，慢跑更均匀
        val straightRatio = when (paceLevel) {
            0 -> 1.12f  // 快跑：直道+12%，弯道-12%
            1 -> 1.10f  // 较快：直道+10%
            2 -> 1.08f  // 普通：直道+8%
            else -> 1.05f // 慢跑：直道+5%
        }
        val straight = (avgSpeed * straightRatio).coerceIn(2.5f, 5.0f)
        val curve = (avgSpeed * (2f - straightRatio)).coerceIn(2.0f, 4.5f)

        // 加速度
        val accel = when (paceLevel) {
            0 -> Random.nextFloat() * 0.3f + 0.5f   // 0.5~0.8
            1 -> Random.nextFloat() * 0.25f + 0.35f  // 0.35~0.6
            else -> Random.nextFloat() * 0.2f + 0.2f  // 0.2~0.4
        }

        // 跑步步长：真实数据1.41~1.71m，随速度增加而增大
        val stepLen = when (paceLevel) {
            0 -> Random.nextFloat() * 0.15f + 1.55f   // 1.55~1.70m
            1 -> Random.nextFloat() * 0.15f + 1.45f   // 1.45~1.60m
            2 -> Random.nextFloat() * 0.15f + 1.35f   // 1.35~1.50m
            else -> Random.nextFloat() * 0.15f + 1.25f  // 1.25~1.40m
        }

        // 步频 = 速度 / 步长 * 60，真实范围145~173
        val cadence = ((avgSpeed / stepLen) * 60).toInt().coerceIn(145, 180)

        return PaceResult(
            straightSpeed = Math.round(straight * 10f) / 10f,
            curveSpeed = Math.round(curve * 10f) / 10f,
            maxAccel = Math.round(accel * 10f) / 10f,
            stepLengthM = Math.round(stepLen * 100f) / 100f,
            cadenceSpm = cadence,
            gpsIntervalMs = 2000L
        )
    }
}
