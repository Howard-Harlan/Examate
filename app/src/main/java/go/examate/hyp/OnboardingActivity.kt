package go.examate.hyp

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import go.examate.hyp.config.MyConfig
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.screens.OnboardingScreen
import go.examate.hyp.ui.theme.ExamateTheme

class OnboardingActivity : ComponentActivity() {

    private val PREFS_NAME = "Onboarding"
    private val KEY_COMPLETED = "completed"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已完成引导且已配置 API → 直接进入主界面
        if (isOnboardingCompleted() && hasAnyApiKeyConfigured()) {
            launchMain()
            return
        }

        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    OnboardingScreen(
                        onSetupApi = {
                            markCompleted()
                            val main = Intent(this@OnboardingActivity, MainActivity::class.java)
                            main.putExtra("goto_api_config", true)
                            startActivity(main)
                            finish()
                        },
                        onSkip = {
                            markCompleted()
                            launchMain()
                        }
                    )
                }
            }
        }
    }

    private fun isOnboardingCompleted(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_COMPLETED, false)
    }

    private fun markCompleted() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }

    private fun hasAnyApiKeyConfigured(): Boolean {
        val providers = MyConfig.loadProviders(this)
        return providers.any { it.apiKey.isNotEmpty() }
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
