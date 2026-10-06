package app.bililisten.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class FailureKind { NETWORK, SESSION_EXPIRED, ACCESS_DENIED, PLATFORM_BLOCKED, MISSING, MALFORMED, STORAGE, UNKNOWN }
fun PlatformFailure.kind(): FailureKind = when (code) {
    -101 -> FailureKind.SESSION_EXPIRED
    -403 -> FailureKind.ACCESS_DENIED
    403, 412, 429, -412, -352 -> FailureKind.PLATFORM_BLOCKED
    -404, 62002 -> FailureKind.MISSING
    in 500..599 -> FailureKind.NETWORK
    else -> when (category) {
        "网络请求失败" -> FailureKind.NETWORK
        "平台响应格式变化", "缺少响应状态", "缺少响应数据" -> FailureKind.MALFORMED
        else -> FailureKind.UNKNOWN
    }
}
data class RequestKey(val generation: Long, val group: String, val identity: String)

/** Shared reads only. No write retries. The last cancelled waiter cancels its underlying request. */
class RequestGovernor(scope: CoroutineScope, concurrency: Int = 3, private val backoff: suspend (Long) -> Unit = { delay(it) }) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private data class Flight(val task: Deferred<Any?>, var users: Int)
    private val gate = Mutex()
    private val permits = Semaphore(concurrency)
    private val flights = mutableMapOf<RequestKey, Flight>()
    private val blocked = mutableMapOf<Pair<Long, String>, PlatformFailure>()
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> read(key: RequestKey, action: suspend () -> T): T {
        val flight = gate.withLock {
            (blocked[key.generation to "session"] ?: blocked[key.generation to key.group])?.let { throw it }
            flights[key]?.also { it.users++ } ?: Flight(scope.async(start = CoroutineStart.LAZY) {
                permits.withPermit {
                    gate.withLock { (blocked[key.generation to "session"] ?: blocked[key.generation to key.group])?.let { throw it } }
                    try {
                        try { action() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: PlatformFailure) {
                            if (failure.kind() != FailureKind.NETWORK) throw failure
                            backoff(350)
                            gate.withLock { (blocked[key.generation to "session"] ?: blocked[key.generation to key.group])?.let { throw it } }
                            action() // one bounded retry, only after a transient network/server failure
                        }
                    } catch (failure: PlatformFailure) {
                        if (failure.kind() in setOf(FailureKind.PLATFORM_BLOCKED, FailureKind.SESSION_EXPIRED)) {
                            gate.withLock { blocked[key.generation to if (failure.code == -101) "session" else key.group] = failure }
                        }
                        throw failure
                    }
                }
            }, 1).also { flights[key] = it }
        }
        try { return flight.task.await() as T }
        finally {
            withContext(NonCancellable) { gate.withLock {
                flight.users--
                if (flight.users == 0) { if (flights[key] === flight) flights.remove(key); flight.task.cancel() }
            } }
        }
    }
    /** Explicit UI retry or a newly established session, never an automatic challenge bypass. */
    suspend fun acknowledge(generation: Long) = gate.withLock { blocked.keys.removeAll { it.first == generation } }
    suspend fun cancelOtherGenerations(generation: Long) = gate.withLock {
        flights.filterKeys { it.generation != generation }.values.forEach { it.task.cancel() }
        blocked.keys.removeAll { it.first != generation }
    }
}

enum class DiagnosticEvent { START, READ_FAILED, WRITE_PENDING, STORAGE_FAILED, PLAYBACK_FAILED, MIGRATION, SETTINGS_RESET }
data class DiagnosticRecord(val time: Long, val event: DiagnosticEvent, val failure: FailureKind?)
/** No free-text API: account IDs, titles, cookies and signed URLs cannot enter exported records. */
class DiagnosticLog(private val clock: Clock, private val capacity: Int = 200) {
    init { require(capacity in 1..1000) }
    private val records = kotlinx.coroutines.flow.MutableStateFlow<List<DiagnosticRecord>>(emptyList())
    fun record(event: DiagnosticEvent, failure: FailureKind? = null) {
        while (true) {
            val old = records.value
            if (records.compareAndSet(old, (old + DiagnosticRecord(clock.nowMs(), event, failure)).takeLast(capacity))) return
        }
    }
    fun preview(): String = records.value.joinToString("\n") { "${it.time}\t${it.event.name}\t${it.failure?.name ?: "OK"}" }.take(32768)
    fun clear() { records.value = emptyList() }
}
