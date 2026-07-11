package go.examate.hyp.util

import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan

/**
 * 将 AI 回复中的【答案】和【解析】分层格式化显示。
 * 答案部分：加粗 + 正常字号
 * 解析部分：正常 + 缩小字号 + 浅色
 */
object AnswerFormatter {

    /**
     * 格式化 AI 回复文本，将【答案】和【解析】分层显示。
     */
    @JvmStatic
    fun format(text: String?): CharSequence? {
        if (text.isNullOrEmpty()) return text

        // 检查是否包含分层标记
        val answerIdx = text.indexOf("【答案】")
        val analysisIdx = text.indexOf("【解析】")

        if (answerIdx == -1 && analysisIdx == -1) {
            // 没有分层标记，原样返回，但根据长度自适应字号
            return autoSizeText(text)
        }

        val builder = SpannableStringBuilder()

        // 前言部分（【答案】之前的内容）
        if (answerIdx > 0) {
            val prefix = text.substring(0, answerIdx).trim()
            if (prefix.isNotEmpty()) {
                val sp = SpannableString(prefix + "\n")
                builder.append(sp)
            }
        }

        // 答案部分
        if (answerIdx != -1) {
            val answerStart = answerIdx + "【答案】".length
            val answerEnd = if (analysisIdx != -1) analysisIdx else text.length
            val answerContent = text.substring(answerStart, answerEnd).trim()

            // 答案标签
            val label = SpannableString("答案  ")
            label.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, 0)
            builder.append(label)

            // 答案内容 - 加粗
            val answerSp = SpannableString(answerContent)
            answerSp.setSpan(StyleSpan(Typeface.BOLD), 0, answerContent.length, 0)
            builder.append(answerSp)
            builder.append("\n")
        }

        // 解析部分
        if (analysisIdx != -1) {
            val analysisStart = analysisIdx + "【解析】".length
            val analysisContent = text.substring(analysisStart).trim()

            // 解析标签
            val label = SpannableString("解析  ")
            label.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, 0)
            builder.append(label)

            // 解析内容 - 缩小字号
            val analysisSp = SpannableString(analysisContent)
            analysisSp.setSpan(AbsoluteSizeSpan(12, true), 0, analysisContent.length, 0)
            builder.append(analysisSp)
        }

        return builder
    }

    /**
     * 根据文本长度自适应字号：短文本正常，长文本缩小。
     */
    private fun autoSizeText(text: String): CharSequence {
        return if (text.length > 200) {
            val sp = SpannableString(text)
            sp.setSpan(AbsoluteSizeSpan(12, true), 0, text.length, 0)
            sp
        } else {
            text
        }
    }
}
