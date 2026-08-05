package go.examate.hyp.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Toast 统一封装。
 *
 * 解决项目中 60+ 处直接 [Toast.makeText] 调用的不一致写法:
 *  - 自动切到主线程(可从任意线程调用,包括协程 IO 线程、Service 回调线程)
 *  - 统一短/长时长默认值
 *  - 支持 StringRes 与 String 两种文案
 *
 * 使用方式:
 *  - `ToastUtil.show(context, "已保存")`
 *  - `ToastUtil.show(context, R.string.saved)`
 *  - `ToastUtil.showLong(context, "建议开启...")`
 */
object ToastUtil {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 短Toast(默认) */
    fun show(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, message, duration).show()
        } else {
            mainHandler.post { Toast.makeText(context, message, duration).show() }
        }
    }

    /** 短Toast(字符串资源) */
    fun show(context: Context, @StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, resId, duration).show()
        } else {
            mainHandler.post { Toast.makeText(context, resId, duration).show() }
        }
    }

    /** 长 Toast */
    fun showLong(context: Context, message: String) =
        show(context, message, Toast.LENGTH_LONG)

    /** 长 Toast(字符串资源) */
    fun showLong(context: Context, @StringRes resId: Int) =
        show(context, resId, Toast.LENGTH_LONG)
}
