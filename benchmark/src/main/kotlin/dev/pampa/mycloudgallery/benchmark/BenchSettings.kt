package dev.pampa.mycloudgallery.benchmark

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException

/**
 * Le impostazioni del dispositivo che le prove della Fase 10 cambiano (P9: dimensione e densita' dello schermo per le due finestre e i pannelli, scala del testo, servizio di
 * accessibilita') e **rimettono come prima**. Regola del brief: cio' che si cambia sull'emulatore si rimette, anche se la prova cade a meta'.
 *
 * Come: ogni cambio ricorda il valore di prima ([restoreAll] lo rimette); i valori di prima stanno anche in un file dei dati del modulo di collaudo ([saved]) cosi' una
 * corsa che muore a meta' non li perde: [restoreStale] (chiamato da ogni `@Before` che cambia qualcosa) li rimette alla corsa dopo. **Lo schermo si cambia solo
 * sull'emulatore** ([isEmulator]) **o con `benchAllowScreen=true`** ([canChangeScreen]: lo dice chi lancia la prova, per il ridimensionamento sul Tab S9): sull'S25 le
 * prove di finestre e pannelli non cambiano dimensione, si saltano se non c'e' la larghezza (`BenchWindow`).
 *
 * I comandi di shell passano da [shellRaw], senza il `2>&1` di `BenchSession.shell`: `UiDevice.executeShellCommand` non interpreta la shell, e un argomento in piu' finirebbe
 * a `settings put` come "tag" e a `am broadcast` come dato dell'intento.
 */
object BenchSettings {

  /** L'emulatore Android (il Medium_Phone di `check.ps1 -Bench`): l'unico dove il collaudo cambia dimensione e densita' dello schermo. */
  val isEmulator: Boolean
    get() = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") ||
      Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"

  /** Si puo' cambiare dimensione e densita' dello schermo: sull'emulatore sempre, su un dispositivo vero solo se chi lancia la prova lo chiede (`benchAllowScreen=true`). */
  val canChangeScreen: Boolean
    get() = isEmulator || InstrumentationRegistry.getArguments().getString("benchAllowScreen") == "true"

  /** Un comando di shell com'e' scritto, senza redirezioni; la sua uscita (vuota in caso di errore). */
  fun shellRaw(command: String): String = try {
    BenchSession.device.executeShellCommand(command)
  } catch (e: IOException) {
    ""
  }

  private val saved: File get() = File(InstrumentationRegistry.getInstrumentation().context.filesDir, "bench-saved-settings.txt")

  private val before = LinkedHashMap<String, String>()

  // --- Schermo -------------------------------------------------------------------------------------------------------------------

  /** Dimensione dello schermo emulato in pixel (`wm size`) e densita' in dpi (`wm density`): una finestra di `width / (dpi / 160)` dp. Solo dove [canChangeScreen]. */
  fun screen(widthPx: Int, heightPx: Int, dpi: Int) {
    check(canChangeScreen) { "La dimensione dello schermo si cambia solo sull'emulatore (o con benchAllowScreen=true)." }
    remember("wm-size") { wmOverride("size", "Override size:") }
    remember("wm-density") { wmOverride("density", "Override density:") }
    shellRaw("wm size ${widthPx}x$heightPx")
    shellRaw("wm density $dpi")
  }

  /** Cambia solo la dimensione (densita' invariata): un ridimensionamento della finestra. */
  fun size(widthPx: Int, heightPx: Int) {
    check(canChangeScreen) { "La dimensione dello schermo si cambia solo sull'emulatore (o con benchAllowScreen=true)." }
    shellRaw("wm size ${widthPx}x$heightPx")
  }

  // --- Impostazioni di sistema --------------------------------------------------------------------------------------------------

  /** `settings put <namespace> <key> <value>` (namespace `system`, `secure` o `global`). */
  fun put(namespace: String, key: String, value: String) {
    remember("settings:$namespace:$key") { shellRaw("settings get $namespace $key").trim() }
    shellRaw("settings put $namespace $key $value")
  }

  /** La scala del testo (`font_scale`): 2,0 e' il massimo che Android e One UI offrono. */
  fun fontScale(scale: String) = put("system", "font_scale", scale)

  /** Accende un servizio di accessibilita' (`componente` = `pacchetto/classe`) e l'interruttore generale. */
  fun accessibilityService(component: String) {
    put("secure", "enabled_accessibility_services", component)
    put("secure", "accessibility_enabled", "1")
  }

  // --- Rimettere come prima -------------------------------------------------------------------------------------------------------

  /** Rimette tutto cio' che questa corsa ha cambiato, nell'ordine inverso. Mai un'eccezione: e' il lavoro di fine prova. */
  fun restoreAll() {
    for ((key, value) in before.entries.reversed()) runCatching { apply(key, value) }
    before.clear()
    runCatching { saved.delete() }
  }

  /** Rimette cio' che una corsa morta a meta' ha lasciato (il file): niente se non c'e'. */
  fun restoreStale() {
    val file = saved
    if (!file.isFile) return
    for (line in file.readLines().asReversed()) {
      val parts = line.split('\t', limit = 2)
      if (parts.size == 2) runCatching { apply(parts[0], parts[1]) }
    }
    file.delete()
  }

  private fun remember(key: String, read: () -> String) {
    if (before.containsKey(key)) return
    before[key] = read()
    saved.writeText(before.entries.joinToString("\n") { "${it.key}\t${it.value}" })
  }

  private fun apply(key: String, value: String) {
    when {
      key == "wm-size" -> shellRaw(if (value.isEmpty()) "wm size reset" else "wm size $value")
      key == "wm-density" -> shellRaw(if (value.isEmpty()) "wm density reset" else "wm density $value")
      key.startsWith("settings:") -> {
        val (_, namespace, name) = key.split(':', limit = 3)
        // `settings get` dice "null" per una chiave che non c'era: allora si toglie.
        shellRaw(if (value.isEmpty() || value == "null") "settings delete $namespace $name" else "settings put $namespace $name $value")
      }
    }
  }

  /** Il valore forzato di `wm size` o `wm density` (la parte dopo `Override ...:`), o stringa vuota se non c'e' (e allora `reset` lo rimette). */
  private fun wmOverride(command: String, label: String): String =
    shellRaw("wm $command").lineSequence().firstOrNull { it.trim().startsWith(label) }?.substringAfter(':')?.trim().orEmpty()
}
