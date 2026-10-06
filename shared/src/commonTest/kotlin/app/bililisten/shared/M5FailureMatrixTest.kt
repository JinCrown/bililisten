package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.*
import kotlin.test.*

class M5FailureMatrixTest {
    @Test fun aNewResolutionNeverReusesThePreviousTemporaryAudioUrl()=runTest {
        var calls=0
        val client=HttpClient(wbiEngine{request->
            assertEquals("2",request.url.parameters["cid"])
            calls++
            respond("""{"code":0,"data":{"dash":{"audio":[{"id":30280,"baseUrl":"https://example.org/fixture-$calls","codecs":"mp4a.40.2","bandwidth":192000}]}}}""")
        })
        try {
            val repository=BiliContentRepository(wbiApi(client,governor=RequestGovernor(backgroundScope)){null})
            assertEquals("https://example.org/fixture-1",repository.audio("BV1xx411c7mD",2))
            assertEquals("https://example.org/fixture-2",repository.audio("BV1xx411c7mD",2))
            assertEquals(2,calls)
        }finally{client.close()}
    }
    @Test fun permissionMissingAndMalformedResponsesDoNotRetryOrTurnIntoEmptySuccess()=runTest {
        val cases=listOf("{\"code\":-403}" to FailureKind.ACCESS_DENIED,"{\"code\":-404}" to FailureKind.MISSING,"<html>challenge</html>" to FailureKind.MALFORMED)
        for((body,kind) in cases){
            var calls=0
            val client=HttpClient(wbiEngine{calls++;respond(body)})
            try {
                val api=wbiApi(client,governor=RequestGovernor(backgroundScope)){null}
                assertEquals(kind,assertFailsWith<PlatformFailure>{api.audio("BV1xx411c7mD",2)}.kind())
                assertEquals(1,calls)
            }finally{client.close()}
        }
    }
    @Test fun rateLimitAndPlatformChallengeStopSubsequentRequestsInTheSameGroup()=runTest {
        for(http in listOf(false,true)){
            var calls=0
            val client=HttpClient(wbiEngine{calls++;if(http)respond("limited",HttpStatusCode.TooManyRequests)else respond("{\"code\":-352}")})
            try {
                val api=wbiApi(client,governor=RequestGovernor(backgroundScope)){null}
                repeat(2){assertEquals(FailureKind.PLATFORM_BLOCKED,assertFailsWith<PlatformFailure>{api.audio("BV1xx411c7mD",2)}.kind())}
                assertEquals(1,calls)
            }finally{client.close()}
        }
    }
}
