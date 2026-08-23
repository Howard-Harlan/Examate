package go.examate.hyp.customview

data class RectangleInfo(var x: Int, var y: Int, var width: Int, var height: Int) {
    fun getLocation(): IntArray = intArrayOf(x, y)
}
