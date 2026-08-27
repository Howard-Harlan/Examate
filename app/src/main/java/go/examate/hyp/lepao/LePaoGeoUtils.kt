package go.examate.hyp.lepao

import kotlin.math.*

/** 地理计算与瓦片坐标转换工具 */
internal object LePaoGeoUtils {

    /** 直接从经纬度列表计算总距离(米) */
    fun calcGeoDistance(pts: List<Pair<Double, Double>>): Float {
        if (pts.size < 2) return 0f
        var dist = 0.0
        for (i in 0 until pts.size - 1) {
            val dLat = (pts[i + 1].first - pts[i].first) * 111320.0
            val dLng = (pts[i + 1].second - pts[i].second) * 111320.0 * cos(Math.toRadians(pts[i].first))
            dist += sqrt(dLat * dLat + dLng * dLng)
        }
        return dist.toFloat()
    }


    // ═══ 瓦片坐标转换 ═══

    fun lngToTileX(lng: Double, zoom: Int): Double =
        (lng + 180.0) / 360.0 * (1 shl zoom)

    fun latToTileY(lat: Double, zoom: Int): Double {
        val rad = Math.toRadians(lat)
        return ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl zoom))
    }

    fun tileXToLng(x: Double, zoom: Int): Double =
        x / (1 shl zoom) * 360.0 - 180.0

    fun tileYToLat(y: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl zoom)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }
}
