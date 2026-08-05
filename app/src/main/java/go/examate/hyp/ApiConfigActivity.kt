package go.examate.hyp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import go.examate.hyp.util.ToastUtil
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import go.examate.hyp.config.MyConfig
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.screens.ApiConfigScreen
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.util.ModelSyncUtil

class ApiConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialProviders = MyConfig.loadProviders(this)

        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    ApiConfigScreen(
                        initialProviders = initialProviders,
                        onBack = { finish() },
                        onSave = { providers ->
                            MyConfig.saveProviders(this@ApiConfigActivity, providers)
                            ToastUtil.show(this@ApiConfigActivity, "已保存")
                            finish()
                        },
                        onOpenTutorial = {
                            val intent = Intent(this@ApiConfigActivity, WebViewActivity::class.java).apply {
                                putExtra(WebViewActivity.EXTRA_URL, "https://docs.qq.com/aio/DZXdrQVFKcEpXVHhH")
                                putExtra(WebViewActivity.EXTRA_TITLE, "使用教程")
                            }
                            startActivity(intent)
                        },
                        onOpenUrl = { url ->
                            val intent = Intent(this@ApiConfigActivity, WebViewActivity::class.java).apply {
                                putExtra(WebViewActivity.EXTRA_URL, url)
                                putExtra(WebViewActivity.EXTRA_TITLE, "获取 API Key")
                            }
                            startActivity(intent)
                        },
                        onFetchModels = { url, key, onSuccess, onError ->
                            ModelSyncUtil.fetchModels(url, key, object : ModelSyncUtil.SyncCallback {
                                override fun onSuccess(modelIds: List<String>) {
                                    runOnUiThread { onSuccess(modelIds) }
                                }

                                override fun onError(message: String) {
                                    runOnUiThread { onError(message) }
                                }
                            })
                        },
                        onToast = { msg ->
                            ToastUtil.show(this@ApiConfigActivity, msg)
                        }
                    )
                }
            }
        }
    }
}
