package app.bililisten.platform

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

class SettingsStore(file: File, scope: CoroutineScope, diagnostics: DiagnosticLog) : SettingsRepository {
    // Keep the legacy field readable, but local history no longer has an opt-out switch.
    private val serializer = object : Serializer<UserSettings> {
        override val defaultValue = UserSettings()
        override suspend fun readFrom(input: InputStream): UserSettings = try {
            Json.decodeFromString<UserSettings>(input.readBytes().decodeToString()).checked().copy(historyEnabled = true)
        } catch (e: SerializationException) { throw CorruptionException("Settings format", e) }
        catch (e: IllegalArgumentException) { throw CorruptionException("Settings values", e) }
        override suspend fun writeTo(t: UserSettings, output: OutputStream) { output.write(Json.encodeToString(t.checked().copy(historyEnabled = true)).encodeToByteArray()) }
    }
    private val store = DataStoreFactory.create(serializer, ReplaceFileCorruptionHandler {
        diagnostics.record(DiagnosticEvent.SETTINGS_RESET); UserSettings()
    }, scope = scope, produceFile = { file })
    override val settings = store.data.catch { e ->
        if (e is IOException) { diagnostics.record(DiagnosticEvent.STORAGE_FAILED, FailureKind.STORAGE); emit(UserSettings()) } else throw e
    }.stateIn(scope, SharingStarted.Eagerly, UserSettings())
    override suspend fun current(): UserSettings = store.data.first()
    override suspend fun update(value: UserSettings) { store.updateData { value.checked().copy(historyEnabled = true) } }
}
