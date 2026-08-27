package go.examate.hyp.lepao

/**
 * 跑步配置模型 - 所有可调参数
 * （已删除未使用的 TrackAnalysis 与 LePaoTrackGenerator 死代码）
 */
data class RunConfig(
    // ===== 路线 =====
    val routePoints: List<Pair<Double, Double>> = emptyList(),  // 手绘路线点(经纬度)，路线即全程

    // ===== 速度 =====
    val straightSpeed: Float = 4.1f,      // 直道速度 m/s (较快跑 ~4'30"/km)
    val curveSpeed: Float = 3.3f,         // 弯道速度 m/s
    val maxAccel: Float = 0.4f,           // 最大加速度 m/s²
    val warmupTime: Int = 10,             // 起步加速时间 s
    val cooldownTime: Int = 8,            // 结束减速时间 s

    // ===== 数据点 =====
    val gpsIntervalMs: Long = 2000L,      // GPS采样间隔 ms (50-70点/400m)

    // ===== 步频 =====
    val stepLengthM: Float = 1.5f,        // 跑步步长 m (真实数据1.41~1.71)
    val cadenceSpm: Int = 160,            // 步频 steps/min (真实145~173)

    // ===== 身体数据 =====
    val weightKg: Float = 65f,            // 体重 kg（卡路里计算）
)
