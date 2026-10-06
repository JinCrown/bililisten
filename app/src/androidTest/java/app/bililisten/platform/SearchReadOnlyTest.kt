package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchReadOnlyTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    @Test fun actualSignedVideoSearchLoadsDifferentQueriesAndSecondPageWithoutPlaybackOrWrites()=runBlocking {
        assertNotNull("Needs existing authenticated session",app.vault.read())
        val reads=mutableListOf<JsonObject>()
        app.governor.acknowledge(app.vault.generation)
        for((query,page)in listOf("周杰伦" to 1,"周杰伦" to 2,"音乐" to 1)) {
            val result=app.content.search(query,page)
            assertEquals(page,result.page);assertTrue("Actual video results required",result.items.isNotEmpty())
            assertEquals(result.items.size,result.items.distinctBy{it.bvid}.size)
            assertTrue(result.items.all{Bvid.parse(it.bvid)==it.bvid})
            reads+=buildJsonObject{put("query",query);put("page",result.page);put("items",result.items.size);put("total",result.total)}
            delay(400)
        }
        val client=HttpClient(OkHttp){followRedirects=false;install(HttpTimeout){requestTimeoutMillis=15000}}
        val old=try{
            val response=client.get("https://api.bilibili.com/x/web-interface/search/type"){
                header("Referer","https://www.bilibili.com/");header("User-Agent","Mozilla/5.0 BiliListen/0.0.4")
                header("Cookie",app.vault.read());parameter("search_type","video");parameter("keyword","音乐")
                parameter("page",1);parameter("page_size",20);parameter("order","totalrank");parameter("tids",0)
            }
            buildJsonObject{put("http",response.status.value)
                if(response.status.value==200)put("code",Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"] ?: JsonNull)}
        }catch(e:CancellationException){throw e}catch(_:Exception){buildJsonObject{put("networkFailure",true)}}finally{client.close()}
        val output=buildJsonObject{
            put("endpoint","/x/web-interface/wbi/search/type");put("actualSignedSearchSucceeded",true)
            put("reads",JsonArray(reads));put("oldEndpointSingleComparison",old)
            put("realMediaPlayed",false);put("remoteWrites",false);put("credentialsExported",false)
        }
        File(app.filesDir,"search-evidence").apply{mkdirs()}.resolve("readonly.json").writeText(output.toString())
    }
}
