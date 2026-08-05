package go.examate.hyp.notes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.ExamateTheme

/**
 * 笔记页面 — 列表 / 编辑器 / 速记代办 三页切换。
 *
 * 简单 when(state) 分支管理页面,与项目其他单 Activity 页面一致(不用 Navigation Compose)。
 * 接收 ACTION_VIEW intent 时,从 extra `noteId` 直接打开对应笔记编辑器。
 */
class NotesActivity : ComponentActivity() {

    private sealed class Screen {
        data object List : Screen()
        data class Editor(val noteId: String?) : Screen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // deep link:外部传 noteId 直接续编
        val initialNoteId = intent?.getStringExtra("noteId")
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    var screen by remember {
                        mutableStateOf<Screen>(
                            if (initialNoteId != null) Screen.Editor(initialNoteId) else Screen.List
                        )
                    }
                    when (val s = screen) {
                        is Screen.List -> NotesScreen(
                            onBack = { finish() },
                            onOpenNote = { id -> screen = Screen.Editor(id) }
                        )
                        is Screen.Editor -> NoteEditorScreen(
                            noteId = s.noteId,
                            onBack = { screen = Screen.List }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
