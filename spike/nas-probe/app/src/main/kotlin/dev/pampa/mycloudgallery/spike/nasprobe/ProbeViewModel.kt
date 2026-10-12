package dev.pampa.mycloudgallery.spike.nasprobe

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val TAG = "NasProbe"

class ProbeViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("nasprobe_settings", Context.MODE_PRIVATE)

    // Campi non sensibili: ricordati tra un avvio e l'altro.
    var host by mutableStateOf(prefs.getString("host", "MyCloudEX2Ultra.fritz.box") ?: "")
    var share by mutableStateOf(prefs.getString("share", "test") ?: "")
    var folder by mutableStateOf(prefs.getString("folder", "") ?: "")
    var user by mutableStateOf(prefs.getString("user", "test") ?: "")
    var share2 by mutableStateOf(prefs.getString("share2", "") ?: "")
    var mac by mutableStateOf(prefs.getString("mac", "") ?: "")
    var inventory by mutableStateOf(prefs.getString("inventory", "") ?: "")
    var requireEncryption by mutableStateOf(prefs.getBoolean("enc", false))
    var bindToWifi by mutableStateOf(prefs.getBoolean("wifi", false))

    /** La password vive solo qui, in memoria: non viene salvata, loggata ne' scritta nei risultati. */
    var password by mutableStateOf("")

    val results = mutableStateListOf<TestResult>()
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("Pronto.")
        private set
    var savedPath by mutableStateOf<String?>(null)
        private set
    var selfCheck by mutableStateOf("Controllo locale di smbj in corso...")
        private set

    private var lastJson: String? = null
    private var job: Job? = null

    init {
        Log.i(TAG, "avvio app")
        viewModelScope.launch(Dispatchers.Default) {
            val (ok, msg) = smbjSelfCheck()
            Log.i(TAG, "selfcheck ok=$ok: $msg")
            selfCheck = msg
        }
    }

    private fun currentSettings() = ProbeSettings(
        host = host, share = share, folder = folder, user = user, password = password,
        share2 = share2, mac = mac, requireEncryption = requireEncryption, bindToWifi = bindToWifi,
        inventory = inventory,
    )

    private fun saveSettings() {
        prefs.edit()
            .putString("host", host).putString("share", share).putString("folder", folder)
            .putString("user", user).putString("share2", share2).putString("mac", mac)
            .putString("inventory", inventory)
            .putBoolean("enc", requireEncryption).putBoolean("wifi", bindToWifi)
            .apply()
    }

    fun runAll() = launchRun("tutti") { runAll() }
    fun runNetworkOnly() = launchRun("rete") { runNetworkOnly() }
    fun runInventory() = launchRun("inventario") { runInventory() }
    fun sendWakeOnLan() = launchRun("wol") { runWakeOnLan() }

    fun stop() {
        if (!running) return
        status = "Interruzione in corso: pulizia dei file .nasprobe- creati..."
        job?.cancel()
    }

    private fun launchRun(mode: String, block: suspend Runner.() -> Unit) {
        if (running) return
        saveSettings()
        val settings = currentSettings()
        results.clear()
        savedPath = null
        lastJson = null
        running = true
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "inizio esecuzione modo=$mode")
        job = viewModelScope.launch {
            val runner = Runner(app, settings, onResult = { results.add(it) }, onStatus = { status = it })
            var interrupted = false
            try {
                runner.block()
            } catch (e: CancellationException) {
                interrupted = true
                results.add(TestResult("STOP", "Interrotto dall'utente", Status.SKIPPED, 0, mapOf("motivo" to "esecuzione interrotta")))
            } catch (t: Throwable) {
                results.add(
                    TestResult("ERR", "Errore imprevisto", Status.FAIL, 0, error = describeError(t, settings.sensitiveTokens)),
                )
            } finally {
                withContext(NonCancellable) {
                    val duration = System.currentTimeMillis() - startedAt
                    val json = Report.build(mode, settings, startedAt, duration, interrupted, results.toList())
                    lastJson = json.pretty()
                    savedPath = try {
                        withContext(Dispatchers.IO) { Report.save(
                            app, json, startedAt,
                            prefix = if (mode == "inventario") "nasprobe-inventario" else "nasprobe",
                        ).absolutePath }
                    } catch (t: Throwable) {
                        "salvataggio non riuscito: ${t.javaClass.simpleName}"
                    }
                    status = if (interrupted) "Interrotto. Risultati parziali salvati." else "Finito in ${duration / 1000} s."
                    Log.i(TAG, "fine esecuzione modo=$mode interrotto=$interrupted durataMs=$duration")
                    running = false
                }
            }
        }
    }

    /** Copia negli appunti il JSON dell'ultima esecuzione (o dei risultati finora ottenuti). */
    fun copyResults(): Boolean {
        val text = lastJson ?: if (results.isEmpty()) return false else
            Report.build("parziale", currentSettings(), System.currentTimeMillis(), 0, true, results.toList()).pretty()
        val cm = app.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("nasprobe", text))
        return true
    }
}

/** Testo leggibile di un risultato per la schermata. */
fun TestResult.dataText(): String =
    if (data.isEmpty()) "" else (toJsonValue(data) as JSONObject).pretty()
