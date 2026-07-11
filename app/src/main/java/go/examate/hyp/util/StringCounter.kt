package go.examate.hyp.util

object StringCounter {

    /**
     * 统计以 "fl" 开头且包含 "SECURE" 的行数。
     * 同时会跳过 "mOwn" 开头且包含 "go.examate.hyp" 的行及其后 3 行。
     * 使用高效的字符遍历，不创建子字符串。
     */
    @JvmStatic
    fun countLinesStartingAndContaining(input: CharSequence?): Int {
        if (input == null || input.isEmpty()) {
            return 0
        }

        var count = 0
        var i = 0
        val len = input.length
        var skipNextLine = 0 // 用于标记是否跳过下一行

        while (i < len) {
            // 跳过多余的换行符(\r\n或\n)
            while (i < len && (input[i] == '\n' || input[i] == '\r')) {
                i++
            }
            if (i >= len) break

            val lineStart = i

            // 找到行尾(后续的i表示行尾索引)
            while (i < len && input[i] != '\n' && input[i] != '\r') {
                i++
            }

            // 如果需要跳过这一行
            if (skipNextLine > 0) {
                skipNextLine-- // 重置标志
                continue // 跳过处理
            }

            // 跳过行首空白
            var start = lineStart
            while (start < i && Character.isWhitespace(input[start])) {
                start++
            }

            // 检查是否以"mOwn"开头
            if (start + 4 <= i &&
                input[start] == 'm' &&
                input[start + 1] == 'O' &&
                input[start + 2] == 'w' &&
                input[start + 3] == 'n'
            ) {
                // 检查是否包含"go.examate.hyp"
                if (containsPackageName(input, start, i)) {
                    skipNextLine = 3 // 设置标志：下3行跳过
                    continue // 不再检查 fl/SECURE 条件
                }
            }

            // 检查是否以"fl"开头
            if (start + 2 <= i &&
                input[start] == 'f' &&
                input[start + 1] == 'l'
            ) {
                // 检查是否包含"SECURE"（不创建子字符串）
                if (containsSecure(input, start, i)) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * 高效的子串搜索（比 String.contains() 更快且不创建对象）
     */
    private fun containsSecure(cs: CharSequence, start: Int, end: Int): Boolean {
        val targetLen = 6 // "SECURE".length()
        for (i in start..end - targetLen) {
            if (cs[i] == 'S' &&
                cs[i + 1] == 'E' &&
                cs[i + 2] == 'C' &&
                cs[i + 3] == 'U' &&
                cs[i + 4] == 'R' &&
                cs[i + 5] == 'E'
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 高效的子串搜索（比 String.contains() 更快且不创建对象）
     * 注：targetLen 已修复为 14（"go.examate.hyp" 为 14 个字符），
     * 原始 Java 代码此处为 13 导致越界比较 cs[i+13]。
     */
    private fun containsPackageName(cs: CharSequence, start: Int, end: Int): Boolean {
        val targetLen = 14 // "go.examate.hyp".length()
        for (i in start..end - targetLen) {
            if (cs[i] == 'g' &&
                cs[i + 1] == 'o' &&
                cs[i + 2] == '.' &&
                cs[i + 3] == 'e' &&
                cs[i + 4] == 'x' &&
                cs[i + 5] == 'a' &&
                cs[i + 6] == 'm' &&
                cs[i + 7] == 'a' &&
                cs[i + 8] == 't' &&
                cs[i + 9] == 'e' &&
                cs[i + 10] == '.' &&
                cs[i + 11] == 'h' &&
                cs[i + 12] == 'y' &&
                cs[i + 13] == 'p'
            ) {
                return true
            }
        }
        return false
    }
}
