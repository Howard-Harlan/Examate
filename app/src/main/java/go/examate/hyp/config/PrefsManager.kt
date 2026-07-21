package go.examate.hyp.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 统一管理 SharedPreferences,消除散落在各类的硬编码 key 字符串。
 *
 * 三个 Prefs 文件:
 * - PrefsConfig: 功能设置(防截屏、吸边、半透明)
 * - ApiConfig: API 厂商配置(JSON)
 * - Onboarding: 引导页完成标记
 *
 * Java 调用方式:PrefsManager.getInstance(context).isForbidCapture()
 */
class PrefsManager private constructor(context: Context) {

    private val prefsConfig: SharedPreferences =
        context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)
    private val apiConfig: SharedPreferences =
        context.getSharedPreferences(PREFS_API, Context.MODE_PRIVATE)
    private val onboarding: SharedPreferences =
        context.getSharedPreferences(PREFS_ONBOARDING, Context.MODE_PRIVATE)

    // ═══ PrefsConfig: 功能设置 ═══

    var isForbidCapture: Boolean
        get() = prefsConfig.getBoolean(KEY_FORBID_CAPTURE, true)
        set(value) = prefsConfig.edit().putBoolean(KEY_FORBID_CAPTURE, value).apply()

    var isEdgeAttach: Boolean
        get() = prefsConfig.getBoolean(KEY_EDGE_ATTACH, true)
        set(value) = prefsConfig.edit().putBoolean(KEY_EDGE_ATTACH, value).apply()

    var isTransparentFloat: Boolean
        get() = prefsConfig.getBoolean(KEY_TRANSPARENT_FLOAT, false)
        set(value) = prefsConfig.edit().putBoolean(KEY_TRANSPARENT_FLOAT, value).apply()

    /** 多AI验证(圆桌会议):开启后自动答题会同时请求多个AI并投票 */
    var isMultiAiVerify: Boolean
        get() = prefsConfig.getBoolean(KEY_MULTI_AI_VERIFY, false)
        set(value) = prefsConfig.edit().putBoolean(KEY_MULTI_AI_VERIFY, value).apply()

    /** 多AI验证:已选中的 provider 索引集合(逗号分隔,如 "0,2,3") */
    var multiAiVerifySelected: String
        get() = prefsConfig.getString(KEY_MULTI_AI_VERIFY_SELECTED, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_MULTI_AI_VERIFY_SELECTED, value).apply()

    /** 获取已选验证 provider 的索引列表(空表示全部参与) */
    fun getMultiAiVerifyIndices(): Set<Int> {
        val s = multiAiVerifySelected
        if (s.isEmpty()) return emptySet()
        return s.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    /** 设置已选验证 provider 的索引列表 */
    fun setMultiAiVerifyIndices(indices: Set<Int>) {
        multiAiVerifySelected = indices.sorted().joinToString(",")
    }

    /** 用户昵称(用于局域网聊天) */
    var nickname: String
        get() = prefsConfig.getString(KEY_NICKNAME, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_NICKNAME, value).apply()

    /** Agents 模式:开启后 AI 可调用工具(查成绩/课表等)并多步推理 */
    var isAgentsMode: Boolean
        get() = prefsConfig.getBoolean(KEY_AGENTS_MODE, false)
        set(value) = prefsConfig.edit().putBoolean(KEY_AGENTS_MODE, value).apply()

    /** 记忆功能开关:默认关闭,等待后续优化后再启用 */
    var isMemoryEnabled: Boolean
        get() = prefsConfig.getBoolean(KEY_MEMORY_ENABLED, false)
        set(value) = prefsConfig.edit().putBoolean(KEY_MEMORY_ENABLED, value).apply()

    /** 已绑定的学校名称(空表示未绑定) */
    var school: String
        get() = prefsConfig.getString(KEY_SCHOOL, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_SCHOOL, value).apply()

    /** 校园统一认证账号(学号/工号) */
    var campusUsername: String
        get() = prefsConfig.getString(KEY_CAMPUS_USERNAME, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_CAMPUS_USERNAME, value).apply()

    /** 学号(校园统一认证账号,与 [campusUsername] 同一存储,供 UI 使用) */
    var studentId: String
        get() = campusUsername
        set(value) { campusUsername = value }

    /** 校园统一认证密码(明文,后续可改用加密存储) */
    var campusPassword: String
        get() = prefsConfig.getString(KEY_CAMPUS_PASSWORD, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_CAMPUS_PASSWORD, value).apply()

    /** 记住校园服务登录:开启后下次进入自动用保存的账号密码登录 */
    var rememberCampusLogin: Boolean
        get() = prefsConfig.getBoolean(KEY_REMEMBER_CAMPUS_LOGIN, false)
        set(value) = prefsConfig.edit().putBoolean(KEY_REMEMBER_CAMPUS_LOGIN, value).apply()

    // ═══ 水卡（乐校通）登录凭据 ═══

    /** 乐校通水卡:登录账号(手机号) */
    var yxtAccount: String
        get() = prefsConfig.getString(KEY_YXT_ACCOUNT, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_YXT_ACCOUNT, value).apply()

    /** 乐校通水卡:登录密码(明文,与校园服务保持一致) */
    var yxtPassword: String
        get() = prefsConfig.getString(KEY_YXT_PASSWORD, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_YXT_PASSWORD, value).apply()

    /** 乐校通水卡:已选学校名(如"淮北师范大学") */
    var yxtSchoolName: String
        get() = prefsConfig.getString(KEY_YXT_SCHOOL_NAME, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_YXT_SCHOOL_NAME, value).apply()

    /** 乐校通水卡:已选学校 sysVersion(3=V3平台, 4=V4平台) */
    var yxtSchoolSysVersion: Int
        get() = prefsConfig.getInt(KEY_YXT_SCHOOL_SYS_VERSION, 0)
        set(value) = prefsConfig.edit().putInt(KEY_YXT_SCHOOL_SYS_VERSION, value).apply()

    /** 乐校通水卡:已选学校 schoolAlias(搜索结果中的别名) */
    var yxtSchoolAlias: String
        get() = prefsConfig.getString(KEY_YXT_SCHOOL_ALIAS, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_YXT_SCHOOL_ALIAS, value).apply()

    /** 清空乐校通水卡凭据(退出登录时调用) */
    fun clearYxtCredentials() {
        prefsConfig.edit()
            .remove(KEY_YXT_ACCOUNT)
            .remove(KEY_YXT_PASSWORD)
            .remove(KEY_YXT_SCHOOL_NAME)
            .remove(KEY_YXT_SCHOOL_SYS_VERSION)
            .remove(KEY_YXT_SCHOOL_ALIAS)
            .apply()
    }

    /**
     * 本地头像文件路径(用户从相册选择的头像,永久保存)。
     * 空表示未设置本地头像。
     */
    var localAvatarPath: String
        get() = prefsConfig.getString(KEY_LOCAL_AVATAR_PATH, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_LOCAL_AVATAR_PATH, value).apply()

    /**
     * Agent 头像文件路径(在 Agent 设置页选择,用于 Agents 聊天气泡)。
     * 空表示未设置 Agent 头像。
     */
    var agentAvatarPath: String
        get() = prefsConfig.getString(KEY_AGENT_AVATAR_PATH, "") ?: ""
        set(value) = prefsConfig.edit().putString(KEY_AGENT_AVATAR_PATH, value).apply()

    /** Agents 聊天头像开关:默认关闭,开启后 assistant 气泡左侧显示 Agent 头像 */
    var isAgentChatAvatarEnabled: Boolean
        get() = prefsConfig.getBoolean(KEY_AGENT_CHAT_AVATAR_ENABLED, false)
        set(value) = prefsConfig.edit().putBoolean(KEY_AGENT_CHAT_AVATAR_ENABLED, value).apply()

    /**
     * 用户信息栏(logo 栏)展示的字段集合
     *
     * 可选项:
     * - "nickname"  昵称
     * - "school"    已绑定学校
     *
     * 未来扩展(校园卡余额等)对接后追加。默认显示昵称和学校。
     */
    var userDisplayFields: Set<String>
        get() = prefsConfig.getStringSet(KEY_USER_DISPLAY_FIELDS, null)
            ?: DEFAULT_USER_DISPLAY_FIELDS
        set(value) = prefsConfig.edit().putStringSet(KEY_USER_DISPLAY_FIELDS, value).apply()

    /**
     * 悬浮菜单已启用的功能项(用户在设置页勾选)
     *
     * 方案B:实际菜单由【勾选 ∩ 已授权】动态决定。
     * 默认全部启用。退出项固定保留,不在此集合中。
     *
     * 功能 ID:
     * - "select"  框选(无障碍/截图,根据启动模式)
     * - "ask"     问问AI
     * - "auto"    自动答题(仅无障碍模式)
     * - "browse"  浏览
     */
    var floatingMenuItems: Set<String>
        get() = prefsConfig.getStringSet(KEY_FLOATING_MENU_ITEMS, null)
            ?: DEFAULT_FLOATING_MENU_ITEMS
        set(value) = prefsConfig.edit().putStringSet(KEY_FLOATING_MENU_ITEMS, value).apply()

    /** 监听功能设置变化(Service 热更新用) */
    fun registerSettingsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefsConfig.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterSettingsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefsConfig.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // ═══ ApiConfig: API 厂商配置 ═══

    var apiProvidersJson: String
        get() = apiConfig.getString(KEY_PROVIDERS, "") ?: ""
        set(value) = apiConfig.edit().putString(KEY_PROVIDERS, value).apply()

    // ═══ Onboarding: 引导页 ═══

    var isOnboardingCompleted: Boolean
        get() = onboarding.getBoolean(KEY_COMPLETED, false)
        set(value) = onboarding.edit().putBoolean(KEY_COMPLETED, value).apply()

    companion object {
        private const val PREFS_CONFIG = "PrefsConfig"
        private const val PREFS_API = "ApiConfig"
        private const val PREFS_ONBOARDING = "Onboarding"

        const val KEY_FORBID_CAPTURE = "forbid_capture"
        const val KEY_EDGE_ATTACH = "edge_attach"
        const val KEY_TRANSPARENT_FLOAT = "transparent_float"
        const val KEY_MULTI_AI_VERIFY = "multi_ai_verify"
        const val KEY_MULTI_AI_VERIFY_SELECTED = "multi_ai_verify_selected"
        const val KEY_NICKNAME = "nickname"
        const val KEY_AGENTS_MODE = "agents_mode"
        const val KEY_MEMORY_ENABLED = "memory_enabled"
        const val KEY_SCHOOL = "school"
        const val KEY_CAMPUS_USERNAME = "campus_username"
        const val KEY_CAMPUS_PASSWORD = "campus_password"
        const val KEY_REMEMBER_CAMPUS_LOGIN = "remember_campus_login"
        const val KEY_YXT_ACCOUNT = "yxt_account"
        const val KEY_YXT_PASSWORD = "yxt_password"
        const val KEY_YXT_SCHOOL_NAME = "yxt_school_name"
        const val KEY_YXT_SCHOOL_SYS_VERSION = "yxt_school_sys_version"
        const val KEY_YXT_SCHOOL_ALIAS = "yxt_school_alias"
        const val KEY_LOCAL_AVATAR_PATH = "local_avatar_path"
        const val KEY_AGENT_AVATAR_PATH = "agent_avatar_path"
        const val KEY_AGENT_CHAT_AVATAR_ENABLED = "agent_chat_avatar_enabled"
        const val KEY_USER_DISPLAY_FIELDS = "user_display_fields"
        const val KEY_FLOATING_MENU_ITEMS = "floating_menu_items"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_COMPLETED = "completed"

        /** 悬浮菜单默认启用的全部功能项(退出项固定保留,不在此集合中) */
        val DEFAULT_FLOATING_MENU_ITEMS: Set<String> = setOf("select", "ask", "auto", "browse", "chat")

        /** 用户信息栏默认展示字段 */
        val DEFAULT_USER_DISPLAY_FIELDS: Set<String> = setOf("nickname", "school")

        /** 已对接 API 的学校列表(目前仅淮北师范大学) */
        val SUPPORTED_SCHOOLS: List<String> = listOf("淮北师范大学")

        @Volatile
        private var instance: PrefsManager? = null

        @JvmStatic
        fun getInstance(context: Context): PrefsManager {
            return instance ?: synchronized(this) {
                instance ?: PrefsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
