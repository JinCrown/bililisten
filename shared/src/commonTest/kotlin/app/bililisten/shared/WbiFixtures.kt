package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData

internal fun wbiEngine(handler:suspend MockRequestHandleScope.(HttpRequestData)->HttpResponseData)=MockEngine { request ->
    if(request.url.encodedPath=="/x/web-interface/nav" && request.headers["Cookie"]==null)
        respond("""{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/${"0".repeat(32)}.png","sub_url":"https://i0.hdslb.com/bfs/wbi/${"1".repeat(32)}.png"}}}""")
    else handler(request)
}
internal fun wbiApi(client:HttpClient,generation:()->Long={0},governor:RequestGovernor?=null,credentials:()->String?)=
    BiliApi(client,governor,generation,searchClock=object:Clock {override fun nowMs()=100000L},searchMd5={"a".repeat(32)},credentials=credentials)
