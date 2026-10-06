package app.bililisten.widget

import android.graphics.*
import android.util.LruCache

/** Reuse the composed cover across progress updates; never persist account artwork here. */
internal object WidgetArtwork {
    private data class Key(val source: Bitmap?, val vinyl: Boolean, val dark: Boolean)
    private val cache = LruCache<Key, Bitmap>(6)
    fun clear() = cache.evictAll()
    fun render(source: Bitmap?, vinyl: Boolean, dark: Boolean): Bitmap {
        val key = Key(source, vinyl, dark)
        cache.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun cover(bounds: RectF, radius: Float) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })
            if (source != null) {
                val side = minOf(source.width, source.height)
                val x = (source.width-side)/2; val y = (source.height-side)/2
                canvas.drawBitmap(source, Rect(x,y,x+side,y+side), bounds, paint)
            } else {
                paint.style = Paint.Style.FILL; paint.color = if(dark)0xff42313b.toInt() else 0xfff5dce6.toInt()
                canvas.drawRect(bounds, paint)
                paint.color = if(dark)0xffe4a8c0.toInt() else 0xffb35277.toInt()
                paint.style = Paint.Style.STROKE; paint.strokeWidth = bounds.width()*.045f; paint.strokeCap = Paint.Cap.ROUND
                val cx=bounds.centerX(); val cy=bounds.centerY(); val u=bounds.width()*.10f
                canvas.drawLine(cx-u,cy+u,cx-u,cy-2*u,paint)
                canvas.drawLine(cx-u,cy-2*u,cx+u,cy-2.5f*u,paint)
                canvas.drawLine(cx+u,cy-2.5f*u,cx+u,cy,paint)
                paint.style=Paint.Style.FILL
                canvas.drawOval(cx-2*u,cy+.5f*u,cx-u,cy+1.4f*u,paint)
                canvas.drawOval(cx,cy-.5f*u,cx+u,cy+.4f*u,paint)
            }
            canvas.restore(); paint.style=Paint.Style.FILL
        }
        if (vinyl) {
            paint.color=0xff222327.toInt(); canvas.drawCircle(147f,164f,142f,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=1.2f
            for(radius in 69..136 step 6) {
                paint.color=if(radius%3==0)0xff48494d.toInt() else 0xff35363a.toInt()
                canvas.drawCircle(147f,164f,radius.toFloat(),paint)
            }
            paint.style=Paint.Style.FILL
            cover(RectF(87f,104f,207f,224f),60f)
            paint.color=0xfff1edef.toInt();canvas.drawCircle(147f,164f,7f,paint)
            paint.color=0xff26272b.toInt();canvas.drawCircle(147f,164f,3f,paint)
            // A fixed tonearm does not require a continuously scheduled animation.
            paint.color=if(dark)0xffc7c9cf.toInt() else 0xff7d818b.toInt()
            canvas.drawCircle(278f,35f,18f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=8f;paint.strokeCap=Paint.Cap.ROUND
            canvas.drawPath(Path().apply{moveTo(278f,35f);lineTo(278f,125f);lineTo(235f,178f)},paint)
            paint.style=Paint.Style.FILL;paint.color=if(dark)0xfff2c5d7.toInt() else 0xffb7567e.toInt()
            canvas.save();canvas.rotate(38f,232f,183f);canvas.drawRoundRect(225f,168f,239f,198f,4f,4f,paint);canvas.restore()
        } else cover(RectF(0f,0f,320f,320f),24f)
        cache.put(key,bitmap)
        return bitmap
    }
}
