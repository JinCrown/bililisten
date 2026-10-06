package app.bililisten.widget

enum class WidgetStyle(val title: String, val size: String) {
    VINYL("唱片", "2×2"), COVER("封面", "2×2"), STRIP("播放条", "4×1 / 4×2");

    fun expanded(width: Int, height: Int) = this == STRIP && width >= 250 && height >= 176
}
