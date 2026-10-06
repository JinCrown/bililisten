package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Aggregate sizes only. Never exports filenames, database rows, credentials or media URLs. */
@RunWith(AndroidJUnit4::class)
class M5StorageFootprintProbe {
    @Test fun recordPostPlaybackPrivateStorageTotals() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m5footprint")=="1")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        fun total(directory:File):JsonObject {
            val files=directory.walkTopDown().filter{it.isFile}.toList()
            return buildJsonObject {
                put("files",files.size);put("logicalBytes",files.sumOf{it.length()})
                put("largestFileBytes",files.maxOfOrNull{it.length()}?:0)
            }
        }
        val cache=context.cacheDir
        val mediaExtensions=setOf("mp3","m4a","aac","flac","ogg","opus","wav","mp4","webm","ts")
        // Cover and WebView caches are reported separately; they are not the audio player's cache.
        val candidates=(cache.walkTopDown()+context.filesDir.walkTopDown()).filter{it.isFile}
            .filterNot{it.toPath().startsWith(File(cache,"covers-v1").toPath())}.toList()
        val result=buildJsonObject {
            put("method","Post-soak aggregate logical sizes; not a before/after disk-write measurement")
            put("cache",total(cache));put("coverCache",total(File(cache,"covers-v1")))
            put("files",total(context.filesDir));put("databases",total(File(context.dataDir,"databases")))
            put("webView",total(File(context.dataDir,"app_webview")))
            put("noBackup",total(context.noBackupFilesDir))
            put("namedMediaFilesOutsideCoverCache",candidates.count{it.extension.lowercase() in mediaExtensions})
            put("scope","Extension scan supplements cache(null) and no audio-file sink source review; not proof against disguised files")
            put("exportedPrivateContents",false)
        }
        File(context.filesDir,"m5-evidence").mkdirs()
        File(context.filesDir,"m5-evidence/storage-footprint.json").writeText(result.toString())
    }
}
