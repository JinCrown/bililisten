package app.bililisten.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic app-owned fixtures only: no network, credentials, playback or user cache writes. */
class CoverOptimizationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class CountingDirectory(path:File):File(path.path) {
        var listings=0
        override fun listFiles():Array<File>? { listings++;return super.listFiles() }
    }
    private fun image(width:Int=80,height:Int=80):ByteArray {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.RED)
            ByteArrayOutputStream().use{out->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.toByteArray()}
        }finally{bitmap.recycle()}
    }
    private fun cacheFile(directory:File,url:String)=File(directory,
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)})
    private fun directory()=CountingDirectory(File(context.cacheDir,"cover-opt-${System.nanoTime()}")).also{check(it.mkdirs())}
    private fun remove(directory:File) {
        check(directory.canonicalFile.parentFile==context.cacheDir.canonicalFile)
        check(directory.name.startsWith("cover-opt-"));check(directory.deleteRecursively())
    }
    @Test fun realDecoderBoundsLargeCoverAllocationAndKeepsItsPixels()=runBlocking {
        val directory=directory();val bytes=image(1920,1080)
        val url="https://i0.hdslb.com/fixture-large.png"
        cacheFile(directory,url).writeBytes(bytes)
        val http=OkHttpClient.Builder().addInterceptor{error("Cached fixture must not request network")}.build()
        val store=CoverStore(directory,http)
        val old=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,
            BitmapFactory.Options().apply{inSampleSize=(1920/1000).coerceAtLeast(1)}))
        try {
            val current=checkNotNull(store.load(url))
            assertEquals(960,current.width);assertEquals(540,current.height)
            assertEquals(Color.RED,current.getPixel(400,200))
            assertEquals(old.allocationByteCount/4,current.allocationByteCount)
            assertSame(current,store.load(url));assertEquals(0,directory.listings)
            val report=JSONObject().put("fixture","1920x1080 PNG; synthetic red cover")
                .put("oldAllocationBytes",old.allocationByteCount).put("newAllocationBytes",current.allocationByteCount)
                .put("newWidth",current.width).put("newHeight",current.height)
                .put("cacheHitDirectoryListings",directory.listings).put("pixelVerified",true)
            val proof=File(context.filesDir,"optimization-audit");check(proof.mkdirs()||proof.isDirectory)
            File(proof,"cover.json").writeText(report.toString(2))
            store.clear();current.recycle()
        }finally{old.recycle();remove(directory)}
    }
    @Test fun onlyNewWritesScanDiskAndCorruptCachedImagesAreRemoved()=runBlocking {
        val directory=directory();val bytes=image();var requests=0
        val http=OkHttpClient.Builder().addInterceptor{chain->
            requests++;Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Synthetic fixture").body(bytes.toResponseBody()).build()
        }.build()
        val url="https://i0.hdslb.com/fixture-write.png"
        try {
            assertNotNull(CoverStore(directory,http).load(url));assertEquals(1,requests);assertEquals(1,directory.listings)
            assertNotNull(CoverStore(directory,http).load(url));assertEquals(1,requests);assertEquals(1,directory.listings)
            val bad="https://i0.hdslb.com/fixture-bad.png";cacheFile(directory,bad).writeBytes(byteArrayOf(1,2,3))
            assertNull(CoverStore(directory,http).load(bad));assertFalse(cacheFile(directory,bad).exists())
            assertEquals(1,requests)
        }finally{remove(directory)}
    }
    @Test fun clearStillRejectsAnInFlightCoverPublication()=runBlocking {
        val directory=directory();val started=CountDownLatch(1);val release=CountDownLatch(1);val bytes=image()
        val http=OkHttpClient.Builder().addInterceptor{chain->
            started.countDown();check(release.await(10,TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Synthetic fixture").body(bytes.toResponseBody()).build()
        }.build()
        val store=CoverStore(directory,http)
        try {
            val pending=async(Dispatchers.IO){store.load("https://i0.hdslb.com/fixture-delayed.png")}
            try {
                assertTrue(withContext(Dispatchers.IO){started.await(5,TimeUnit.SECONDS)})
                store.clear()
            }finally{release.countDown()}
            assertNull(pending.await());assertEquals(0L,store.bytes())
        }finally{release.countDown();remove(directory)}
    }
}
