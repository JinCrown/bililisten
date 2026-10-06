package app.bililisten.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetStyleTest {
    @Test fun squareStylesNeverTurnIntoTheFiveButtonStrip() {
        listOf(WidgetStyle.VINYL,WidgetStyle.COVER).forEach {
            assertFalse(it.expanded(128,148)); assertFalse(it.expanded(400,400))
            assertEquals("2×2",it.size)
        }
    }
    @Test fun stripRequiresEnoughRoomInBothDimensions() {
        assertFalse(WidgetStyle.STRIP.expanded(249,200))
        assertFalse(WidgetStyle.STRIP.expanded(320,175))
        assertTrue(WidgetStyle.STRIP.expanded(250,176))
    }
    @Test fun stylesHaveDistinctPickerTitles() {
        assertEquals(3,WidgetStyle.entries.map{it.title}.distinct().size)
    }
}
