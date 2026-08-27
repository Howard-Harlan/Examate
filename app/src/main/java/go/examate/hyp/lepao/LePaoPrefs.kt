package go.examate.hyp.lepao

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/**
 * 步道乐跑配置持久化
 */
object LePaoPrefs {
    const val DEFAULT_SCHOOL_ID = "307"
    const val DEFAULT_TERM_ID = "12887"
    private const val PREFS = "lepao_config"
    private const val KEY_TOKEN = "token"
    private const val KEY_UID = "uid"
    private const val KEY_SCHOOL_ID = "school_id"
    private const val KEY_STUDENT_NUM = "student_num"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_NICKNAME = "nickname"

    private const val KEY_ROUTE_POINTS = "route_points"

    private const val KEY_STRAIGHT_SPEED = "straight_speed"
    private const val KEY_CURVE_SPEED = "curve_speed"
    private const val KEY_MAX_ACCEL = "max_accel"
    private const val KEY_GPS_INTERVAL = "gps_interval"

    private const val KEY_STEP_LENGTH = "step_length"
    private const val KEY_CADENCE = "cadence"
    private const val KEY_WEIGHT = "weight"
    private const val KEY_REFRESH_TOKEN = "refresh_token"

    /** 默认路线 — 相山校区第二运动场跑道轮廓（从真实轨迹提取12个关键点） */
    val DEFAULT_ROUTE_POINTS = listOf(
        33.9841804504395 to 116.808097839355,
        33.9839477539062 to 116.808151245117,
        33.9837074279785 to 116.808212280273,
        33.9834632873535 to 116.808441162109,
        33.9834671020508 to 116.808807373047,
        33.9837036132812 to 116.809028625488,
        33.9839248657227 to 116.809036254883,
        33.9842300415039 to 116.808952331543,
        33.984561920166 to 116.808891296387,
        33.9848327636719 to 116.808708190918,
        33.9848594665527 to 116.808250427246,
        33.9846076965332 to 116.80802154541,
    )

    fun save(context: Context, uid: String, token: String, schoolId: String, studentNum: String, nickname: String = "", refreshToken: String = "") {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UID, uid)
            .putString(KEY_TOKEN, token)
            .putString(KEY_SCHOOL_ID, schoolId)
            .putString(KEY_STUDENT_NUM, studentNum)
            .putString(KEY_NICKNAME, nickname)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    fun getRefreshToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REFRESH_TOKEN, "") ?: ""

    fun load(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("uid", prefs.getString(KEY_UID, ""))
            put("token", prefs.getString(KEY_TOKEN, ""))
            put("school_id", prefs.getString(KEY_SCHOOL_ID, DEFAULT_SCHOOL_ID))
            put("student_num", prefs.getString(KEY_STUDENT_NUM, ""))
            put("nickname", prefs.getString(KEY_NICKNAME, ""))
        }
    }

    fun isLoggedIn(context: Context): Boolean {
        val p = load(context)
        return p.optString("token", "").isNotEmpty() && p.optString("uid", "").isNotEmpty()
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }


    /** 路线点序列化为JSON保存 */
    fun saveRoutePoints(context: Context, pts: List<Pair<Double, Double>>) {
        val arr = org.json.JSONArray()
        pts.forEach { (lat, lng) ->
            arr.put(org.json.JSONArray().put(lat).put(lng))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ROUTE_POINTS, arr.toString()).apply()
    }

    fun loadRoutePoints(context: Context): List<Pair<Double, Double>> {
        val str = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROUTE_POINTS, null) ?: return DEFAULT_ROUTE_POINTS
        try {
            val arr = org.json.JSONArray(str)
            return (0 until arr.length()).mapNotNull { i ->
                val pt = arr.getJSONArray(i)
                Pair(pt.getDouble(0), pt.getDouble(1))
            }
        } catch (_: Exception) { return DEFAULT_ROUTE_POINTS }
    }

    fun saveRunConfig(context: Context, config: RunConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_STRAIGHT_SPEED, config.straightSpeed)
            .putFloat(KEY_CURVE_SPEED, config.curveSpeed)
            .putFloat(KEY_MAX_ACCEL, config.maxAccel)
            .putLong(KEY_GPS_INTERVAL, config.gpsIntervalMs)
            .putFloat(KEY_STEP_LENGTH, config.stepLengthM)
            .putInt(KEY_CADENCE, config.cadenceSpm)
            .putFloat(KEY_WEIGHT, config.weightKg)
            .apply()
    }

    fun loadRunConfig(context: Context): RunConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RunConfig(
            straightSpeed = p.getFloat(KEY_STRAIGHT_SPEED, 4.1f),
            curveSpeed = p.getFloat(KEY_CURVE_SPEED, 3.3f),
            maxAccel = p.getFloat(KEY_MAX_ACCEL, 0.4f),
            gpsIntervalMs = p.getLong(KEY_GPS_INTERVAL, 2000L),
            stepLengthM = p.getFloat(KEY_STEP_LENGTH, 1.5f),
            cadenceSpm = p.getInt(KEY_CADENCE, 160),
            weightKg = p.getFloat(KEY_WEIGHT, 65f)
        )
    }
}
