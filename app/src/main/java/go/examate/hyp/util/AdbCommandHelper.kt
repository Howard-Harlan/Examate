package go.examate.hyp.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.RemoteException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import go.examate.hyp.ExecResult
import go.examate.hyp.IUserService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdbCommandHelper @Throws(UserNotAuthenticatedException::class)
constructor(private val context: Context) {

    private var serviceManager: ShizukuServiceManager? = null
    private var userService: IUserService? = null

    init {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                serviceManager = ShizukuServiceManager()
            } else {
                Log.d("TAG", "SecureCounter: Shizuku未授权")
                throw UserNotAuthenticatedException()
            }
            if (!bindUserService(context)) {
                throw UserNotAuthenticatedException()
            }
        } else {
            Log.d("TAG", "SecureCounter: Shizuku未启动")
            throw UserNotAuthenticatedException()
        }
    }

    @Throws(RemoteException::class, UserNotAuthenticatedException::class)
    fun GetOuterSecureWindowCount(): Int {
        if (!ensureUserService()) {
            throw UserNotAuthenticatedException()
        }
        val service = requireNotNull(userService)
        val result = service.exec("dumpsys window windows")
        return StringCounter.countLinesStartingAndContaining(result.stdout)
    }

    fun TryExecuteCommand(cmd: String): ExecResult? {
        return try {
            if (!ensureUserService()) return null
            userService?.exec(cmd)
        } catch (e: RemoteException) {
            e.printStackTrace()
            null
        }
    }

    private fun ensureUserService(): Boolean {
        return userService != null || bindUserService(context)
    }

    fun bindUserService(context: Context): Boolean { // 绑定时需要传入context获取包名
        val mgr = serviceManager ?: return false
        val latch = CountDownLatch(1)
        val bindSuccess = booleanArrayOf(false)
        mgr.bind(context) { success, _, service ->
            if (success) {
                userService = service
                bindSuccess[0] = true
                Log.d("TAG", "Shizuku用户服务绑定成功")
            } else {
                Log.d("TAG", "Shizuku用户服务绑定失败")
            }
            latch.countDown()
        }
        return try {
            if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.d("TAG", "Shizuku用户服务绑定超时")
                return false
            }
            bindSuccess[0]
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    companion object {
        private const val BIND_TIMEOUT_SECONDS = 5L
    }
}
