package dev.pampa.mycloudgallery.spike.embedprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Pacchetti generici (CPU/GPU) dal documento di ricerca, par. 2.2. Commit fissati: i file HF possono cambiare. */
enum class ModelId(
    val label: String,
    val repo: String,
    val commit: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val hasVision: Boolean,
    /** Valorizzato solo per le varianti NPU precompilate: Build.SOC_MODEL per cui il file e fatto. */
    val soc: String? = null,
) {
    M440(
        label = "440M testo+visione",
        repo = "embeddinggemma-2-text-vision-440m-litert-lm",
        commit = "e301f74d5551b0c2641bd5cb4652a76239d5c5f8",
        fileName = "embeddinggemma-2-text-vision-440m.litertlm",
        sizeBytes = 387_710_976L,
        sha256 = "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0",
        hasVision = true,
    ),
    M270(
        label = "270M testo",
        repo = "embeddinggemma-2-text-270m-litert-lm",
        commit = "9be6e8b90982095dc05c2bd162e4b954ee4dbac7",
        fileName = "embeddinggemma-2-text-270m.litertlm",
        sizeBytes = 164_626_432L,
        sha256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
        hasVision = false,
    ),

    // Varianti NPU Qualcomm del 440M (stesso repo e stesso commit; dimensioni e SHA-256 dall'API di Hugging Face).
    M440_SM8750(
        label = "440M testo+visione NPU (Qualcomm SM8750)",
        repo = "embeddinggemma-2-text-vision-440m-litert-lm",
        commit = "e301f74d5551b0c2641bd5cb4652a76239d5c5f8",
        fileName = "embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm",
        sizeBytes = 446_611_456L,
        sha256 = "c5c05cb59833541a2d0d9374c7b50d2c3a406a4a84c85762775e5f9c6d96cd0c",
        hasVision = true,
        soc = "SM8750",
    ),
    M440_SM8550(
        label = "440M testo+visione NPU (Qualcomm SM8550)",
        repo = "embeddinggemma-2-text-vision-440m-litert-lm",
        commit = "e301f74d5551b0c2641bd5cb4652a76239d5c5f8",
        fileName = "embeddinggemma-2-text-vision-440m_Qualcomm_SM8550.litertlm",
        sizeBytes = 465_993_728L,
        sha256 = "4427eecca4289555e72b297308f4c9cf97110a657de25dd2fe7946ac824f6214",
        hasVision = true,
        soc = "SM8550",
    );

    val url: String get() = "https://huggingface.co/litert-community/$repo/resolve/$commit/$fileName"
    val isNpu: Boolean get() = soc != null

    companion object {
        /** Pacchetti generici CPU/GPU. */
        val generic: List<ModelId> get() = entries.filter { it.soc == null }

        /** La variante NPU per il SoC di questo dispositivo, o null se non supportato. */
        fun deviceNpu(): ModelId? = entries.firstOrNull { it.soc != null && it.soc.equals(Build.SOC_MODEL, ignoreCase = true) }

        /** Quelli che hanno senso su questo dispositivo: generici + eventuale NPU del suo SoC. */
        fun relevant(): List<ModelId> = generic + listOfNotNull(deviceNpu())
    }
}

enum class ModelState { MISSING, PARTIAL, READY }

data class ModelStatus(val state: ModelState, val bytesOnDisk: Long)

class Sha256MismatchException(val expected: String, val actual: String) :
    IOException("SHA-256 non corrisponde (atteso $expected, ottenuto $actual): file cancellato")

class NotOnWifiException : IOException("Non sei collegato al Wi-Fi: download bloccato (potrai riprendere piu tardi)")

data class DownloadProgress(
    val model: ModelId,
    val phase: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val bytesPerSec: Double,
)

class ModelStore(private val ctx: Context) {
    val dir = File(ctx.filesDir, "models")

    fun file(m: ModelId) = File(dir, m.fileName)
    fun partFile(m: ModelId) = File(dir, m.fileName + ".part")
    private fun marker(m: ModelId) = File(dir, m.fileName + ".sha256ok")

    fun isReady(m: ModelId): Boolean {
        val f = file(m)
        val mk = marker(m)
        return f.isFile && f.length() == m.sizeBytes && mk.isFile && mk.readText().trim() == m.sha256
    }

    fun status(m: ModelId): ModelStatus {
        if (isReady(m)) return ModelStatus(ModelState.READY, file(m).length())
        val part = partFile(m)
        if (part.isFile && part.length() > 0) return ModelStatus(ModelState.PARTIAL, part.length())
        return ModelStatus(ModelState.MISSING, 0)
    }

    fun markVerified(m: ModelId) {
        marker(m).writeText(m.sha256)
    }

    fun deleteAll(m: ModelId) {
        file(m).delete()
        partFile(m).delete()
        marker(m).delete()
    }

    fun freeBytes(): Long {
        dir.mkdirs()
        return StatFs(dir.path).availableBytes
    }

    fun isOnWifi(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

class ModelDownloader(private val store: ModelStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Scarica (solo Wi-Fi), riprende da .part con Range se esiste, verifica lo SHA-256.
     * Se lo SHA non corrisponde cancella tutto e solleva Sha256MismatchException.
     */
    suspend fun download(m: ModelId, onProgress: (DownloadProgress) -> Unit) = withContext(Dispatchers.IO) {
        store.dir.mkdirs()
        if (store.isReady(m)) return@withContext
        if (!store.isOnWifi()) throw NotOnWifiException()

        val finalFile = store.file(m)
        val part = store.partFile(m)
        finalFile.delete()
        var restarted = false

        while (true) {
            currentCoroutineContext().ensureActive()
            var have = if (part.isFile) part.length() else 0L
            if (have > m.sizeBytes) {
                part.delete()
                have = 0L
            }
            if (have == m.sizeBytes) break

            val needed = m.sizeBytes - have + 64L * 1024 * 1024
            if (store.freeBytes() < needed) {
                throw IOException("Spazio insufficiente: servono ${Fmt.mib(needed)}, liberi ${Fmt.mib(store.freeBytes())}")
            }

            val req = Request.Builder().url(m.url)
                .header("User-Agent", "EmbedProbe/0.1 (spike)")
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()

            var retry = false
            http.newCall(req).execute().use { resp ->
                val append: Boolean
                when (resp.code) {
                    206 -> append = true
                    200 -> {
                        // Il server ha ignorato Range: si riparte da zero.
                        append = false
                        if (have > 0) part.delete()
                    }
                    416 -> {
                        // Range non valido: il .part non e coerente. Una sola ripartenza da zero.
                        if (restarted) throw IOException("HTTP 416 anche dopo la ripartenza")
                        restarted = true
                        part.delete()
                        retry = true
                        return@use
                    }
                    else -> throw IOException("HTTP ${resp.code} da Hugging Face")
                }
                val body = resp.body ?: throw IOException("Risposta vuota")
                var done = if (append) have else 0L
                val sessionStartBytes = done
                val t0 = System.nanoTime()
                var lastReport = 0L
                var lastWifiCheck = t0
                val buf = ByteArray(256 * 1024)
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - lastWifiCheck > 2_000_000_000L) {
                                lastWifiCheck = now
                                if (!store.isOnWifi()) throw NotOnWifiException()
                            }
                            if (now - lastReport > 250_000_000L) {
                                lastReport = now
                                val secs = (now - t0) / 1e9
                                onProgress(
                                    DownloadProgress(
                                        m, "download", done, m.sizeBytes,
                                        if (secs > 0) (done - sessionStartBytes) / secs else 0.0,
                                    ),
                                )
                            }
                        }
                    }
                }
                onProgress(DownloadProgress(m, "download", done, m.sizeBytes, 0.0))
            }
            if (retry) continue
            if (part.length() < m.sizeBytes) {
                throw IOException(
                    "Download interrotto a ${Fmt.mib(part.length())} di ${Fmt.mib(m.sizeBytes)}: premi di nuovo per riprendere",
                )
            }
            break
        }

        if (part.length() != m.sizeBytes) {
            part.delete()
            throw IOException("Dimensione errata (${part.length()} B invece di ${m.sizeBytes} B): file cancellato")
        }

        // Verifica SHA-256 sul file completo.
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1024 * 1024)
        var read = 0L
        var lastReport = 0L
        part.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                read += n
                val now = System.nanoTime()
                if (now - lastReport > 250_000_000L) {
                    lastReport = now
                    onProgress(DownloadProgress(m, "verifica SHA-256", read, m.sizeBytes, 0.0))
                }
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != m.sha256) {
            store.deleteAll(m)
            throw Sha256MismatchException(m.sha256, actual)
        }
        if (!part.renameTo(finalFile)) throw IOException("Impossibile rinominare il file scaricato")
        store.markVerified(m)
    }
}
