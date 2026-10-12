package dev.pampa.mycloudgallery.spike.embedprobe

import java.util.concurrent.TimeUnit

/**
 * Righe di logcat del nostro stesso processo (l'app puo leggere solo le proprie) che parlano di
 * acceleratori: serve a capire quale backend il runtime nativo ha davvero scelto, perche l'API Kotlin
 * non espone un getter del backend effettivo.
 */
object Logcat {
    private val filter = Regex(
        "npu|qnn|htp|dsp|dispatch|litert|delegate|accelerator|backend|fallback|opencl|gpu|hexagon|skel|cdsprpc",
        RegexOption.IGNORE_CASE,
    )

    fun recent(maxLines: Int = 40): List<String> = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500", "--pid=${android.os.Process.myPid()}")
            .redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor(3, TimeUnit.SECONDS)
        lines.filter { filter.containsMatchIn(it) && !it.contains("EmbedProbeDebug") }.takeLast(maxLines).map { it.take(260) }
    } catch (_: Throwable) {
        emptyList()
    }
}
