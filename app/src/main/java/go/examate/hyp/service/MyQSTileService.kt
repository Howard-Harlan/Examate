package go.examate.hyp.service

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.PowerManager
import android.provider.Settings
import android.security.keystore.UserNotAuthenticatedException
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

import go.examate.hyp.R
import go.examate.hyp.util.AdbCommandHelper

import rikka.shizuku.Shizuku

/*
ADB授权
adb shell "pm grant go.examate.hyp android.permission.WRITE_SECURE_SETTINGS; appops set go.examate.hyp ACCESS_RESTRICTED_SETTINGS allow"
ADB夺权
adb shell "pm revoke go.examate.hyp android.permission.WRITE_SECURE_SETTINGS; appops set go.examate.hyp ACCESS_RESTRICTED_SETTINGS deny"
*/
class MyQSTileService : TileService() {

    // 注意:字段名不能叫 qsTile,否则其生成的 getter getQsTile() 会与父类 TileService.getQsTile() 冲突
    private var tile: Tile? = null

    private var commandHelper: AdbCommandHelper? = null

    override fun onStartListening() {
        super.onStartListening()
        tile = getQsTile()
        val t = tile ?: return
        t.state = if (UnifiedFloatingService.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        t.label = "AI助手"
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val qsTile = tile ?: return
        if (qsTile.state == Tile.STATE_ACTIVE) {
            if (DisableAccessibleService()) {
                stopService(Intent(this, UnifiedFloatingService::class.java))
                qsTile.state = Tile.STATE_INACTIVE // 快捷开关
            } else {
                qsTile.label = "权限不足"
            }
        } else if (qsTile.state == Tile.STATE_INACTIVE) {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    qsTile.icon = Icon.createWithResource(this, R.drawable.ic_wait)
                    val state = qsTile.state
                    qsTile.state = Tile.STATE_UNAVAILABLE
                    qsTile.label = "请稍等"
                    qsTile.updateTile()
                    qsTile.state = state
                    if (CheckPermission()) { //检查权限
                        // 启动
                        startService(Intent(this, UnifiedFloatingService::class.java).apply {
                            putExtra(UnifiedFloatingService.EXTRA_MODE, "accessibility")
                        })
                        qsTile.label = getString(R.string.app_name)
                        qsTile.icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
                        qsTile.state = Tile.STATE_ACTIVE // 快捷开关
                    } else {
                        qsTile.icon = Icon.createWithResource(this, R.drawable.ic_refresh)
                        qsTile.label = "请重试"
                    }
                } else {
                    startService(Intent(this, UnifiedFloatingService::class.java).apply {
                        putExtra(UnifiedFloatingService.EXTRA_MODE, "accessibility")
                    })
                    qsTile.icon = Icon.createWithResource(this, R.drawable.ic_error)
                    qsTile.label = "权限不足"
                }
            } else {
                startService(Intent(this, UnifiedFloatingService::class.java).apply {
                    putExtra(UnifiedFloatingService.EXTRA_MODE, "accessibility")
                })
                qsTile.icon = Icon.createWithResource(this, R.drawable.ic_error)
                qsTile.label = "未安装"
            }
        }
        qsTile.updateTile()
    }

    private fun CheckPermission(): Boolean {
        //尝试授权
        if (!Settings.canDrawOverlays(this)) { // 授权悬浮窗
            if (InitializeAdbCommandHelper())
                commandHelper?.TryExecuteCommand("appops set " + packageName + " SYSTEM_ALERT_WINDOW allow")
            else
                return false
        }
        if (!canWriteSecureSettings()) { // 写入安全设置
            if (InitializeAdbCommandHelper())
                commandHelper?.TryExecuteCommand("pm grant " + packageName + " android.permission.WRITE_SECURE_SETTINGS")
            else
                return false
        }
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) { // 写入电池优化
            if (InitializeAdbCommandHelper())
                commandHelper?.TryExecuteCommand("dumpsys deviceidle whitelist +" + packageName)
            else
                return false
        }
        if (MyAccessibilityService.getMyAccessibilityServiceInstance() == null) { // 启动无障碍
            if (!EnableAccessibleService()) {
                commandHelper?.TryExecuteCommand("pm grant " + packageName + " android.permission.WRITE_SECURE_SETTINGS")
            } else {
                Toast.makeText(this, "无障碍已开启,请再次点击磁贴启动搜题", Toast.LENGTH_LONG).show()
            }
        }
        //检查权限
        val granted1 = Settings.canDrawOverlays(this)
        val granted2 = canWriteSecureSettings()
        val granted3 = powerManager.isIgnoringBatteryOptimizations(packageName)
        val granted4 = MyAccessibilityService.getMyAccessibilityServiceInstance() == null
        Log.d("TAG", "CheckPermission: $granted1$granted2$granted3$granted4")
        return granted1
    }

    private fun InitializeAdbCommandHelper(): Boolean {
        return try {
            if (commandHelper == null) {
                commandHelper = AdbCommandHelper(this)
                Log.d("TAG", "InitializeAdbCommandHelper: 实例化helper")
                false
            } else {
                Log.d("TAG", "InitializeAdbCommandHelper: helper已存在")
                true
            }
        } catch (e: UserNotAuthenticatedException) {
            false
        }
    }

    private fun DisableAccessibleService(): Boolean {
        return try {
            // 无障碍服务名
            val serviceName = "$packageName/go.examate.hyp.service.MyAccessibilityService"
            // 读取当前已启用的服务列表
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            // 避免重复添加，检查是否已不包含
            if (enabledServices != null && enabledServices.contains(serviceName)) {
                // 分割为数组
                val services = enabledServices.split(":")
                val newServices = StringBuilder()

                // 重建列表，排除当前服务
                for (service in services) {
                    if (service != serviceName) {
                        if (newServices.isNotEmpty()) {
                            newServices.append(":")
                        }
                        newServices.append(service)
                    }
                }
                // 写回设置
                Settings.Secure.putString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newServices.toString()
                )
            }
            true
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        }
    }

    private fun EnableAccessibleService(): Boolean {
        return try {
            // 开启全局无障碍总开关
            val name = Settings.Secure.ACCESSIBILITY_ENABLED
            Settings.Secure.putInt(contentResolver, name, 1)
            // 无障碍服务名
            val serviceName = "$packageName/go.examate.hyp.service.MyAccessibilityService"
            // 读取当前已启用的服务列表
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            // 避免重复添加，检查是否已包含
            if (enabledServices == null || !enabledServices.contains(serviceName)) {
                // 追加服务到现有列表（不同服务用冒号分隔）
                val newEnabledServices = if (enabledServices == null)
                    serviceName
                else
                    "$enabledServices:$serviceName"

                // 写入设置
                Settings.Secure.putString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newEnabledServices
                )
            }
            true
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        }
    }

    private fun canWriteSecureSettings(): Boolean {
        return try {
            // 尝试实际操作验证权限
            Settings.Global.getString(contentResolver, "adb_enabled")
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
