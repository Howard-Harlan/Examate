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
 * 笔记页面 — 列表 / 编辑器 两态切换(重做版)。
 *
 * 速记已独立为 MemoActivity,这里只承载 Markdown 知识笔记。
 * 接收 ACTION_VIEW intent 时,从 extra `noteId` 直接打开对应笔记编辑器。
 */
class NotesActivity : ComponentActivity() {

    private sealed class Screen {
        data object List : Screen()
        data class Editor(val noteId: String?, val folderId: String = "") : Screen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                            onOpenNote = { id, folderId -> screen = Screen.Editor(id, folderId) }
                        )
                        is Screen.Editor -> NoteEditorScreen(
                            noteId = s.noteId,
                            folderId = s.folderId,
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
