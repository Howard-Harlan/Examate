package go.examate.hyp.util

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import go.examate.hyp.IUserService
import go.examate.hyp.service.UserService
import rikka.shizuku.Shizuku

/**
 * Shizuku 用户服务管理器
 * 用于绑定/解绑通过 Shizuku 运行的特权服务
 */
class ShizukuServiceManager {

    private var userService: IUserService? = null

    private var serviceConnection: ServiceConnection? = null

    /**
     * 绑定 Shizuku 用户服务
     */
    fun bind(context: Context, callback: OnBindResultCallback) {
        // 如果服务已连接，直接返回现有实例
        userService?.let { service ->
            Log.d(TAG, "Shizuku service 已经连接")
            callback.onResult(true, null, service)
            return
        }

        try {
            // 构建服务参数
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, UserService::class.java.name)
            )
                .daemon(true)
                .processNameSuffix(context.packageName + ".shizuku.service")
                .debuggable(true)

            // 创建服务连接回调
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    userService = IUserService.Stub.asInterface(service)
                    Log.d(TAG, "Shizuku service 成功连接: ${name.className}")
                    callback.onResult(true, null, userService)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    userService = null
                    Log.d(TAG, "Shizuku service 断开: ${name.className}")
                }
            }
            serviceConnection = conn

            // 执行绑定
            Shizuku.bindUserService(args, conn)
        } catch (e: Exception) {
            Log.e(TAG, "绑定失败", e)
            callback.onResult(false, "绑定异常: ${e.message}", null)
        }
    }

    /**
     * 解绑 Shizuku 用户服务
     */
    fun unbind(context: Context) {
        try {
            serviceConnection?.let { conn ->
                Shizuku.unbindUserService(
                    Shizuku.UserServiceArgs(ComponentName(context, UserService::class.java.name)),
                    conn,
                    true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "解绑出错", e)
        } finally {
            // 清理状态
            userService = null
            serviceConnection = null
            Log.d(TAG, "Shizuku service 解绑")
        }
    }

    /**
     * 绑定结果回调接口
     */
    fun interface OnBindResultCallback {
        fun onResult(success: Boolean, errorMessage: String?, userService: IUserService?)
    }

    companion object {
        private const val TAG = "ShizukuServiceManager"
    }
}
