package dev.pampa.mycloudgallery.benchmark

import android.os.Bundle
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Le manopole del collaudo, tutte da argomenti dello strumentatore (`-Pandroid.testInstrumentationRunnerArguments.<nome>=<valore>`),
 * cosi' lo stesso codice gira sul dispositivo vero (misura) e sull'emulatore (smoke) senza due copie degli scenari.
 *
 * | Argomento | Valori | Effetto |
 * |---|---|---|
 * | `benchSmoke` | `true` | Poche iterazioni e un solo ciclo: serve a vedere che gli scenari girano, i numeri non valgono. Lo imposta `check.ps1 -Bench` sugli emulatori |
 * | `benchCompilation` | `require` (default), `useIfAvailable`, `full`, `none` | `CompilationMode` degli scenari: `Partial(BaselineProfileMode.Require)` e' quello del §2.6 |
 * | `benchDiag` | `true` | Scrive nel logcat (`MCGdiag`) i fotogrammi piu' lunghi con le sezioni di traccia che li compongono (`FrameDiagMetric`): solo per capire le cause, non cambia le misure |
 * | `benchDatasetTimeoutS` | secondi (default 240) | Quanto aspettare che il dataset sintetico sia pronto: il Tab S9 ci mette piu' dei 4 minuti dell'S25 (03/11) |
 */
object BenchConfig {

  private val args: Bundle get() = InstrumentationRegistry.getArguments()

  /** Smoke: l'infrastruttura gira, i numeri non contano (emulatore). */
  val smoke: Boolean get() = args.getString("benchSmoke") == "true"

  /** Le iterazioni di uno scenario: quelle del §2.6, ridotte a 2 nello smoke. */
  fun iterations(normal: Int): Int = if (smoke) minOf(normal, SMOKE_ITERATIONS) else normal

  /** L'attesa del dataset sintetico in millisecondi: 240 s di serie (l'S25), di piu' sui dispositivi lenti (`benchDatasetTimeoutS`). */
  val datasetTimeoutMs: Long get() = (args.getString("benchDatasetTimeoutS")?.toLongOrNull()?.coerceAtLeast(1L) ?: 240L) * 1_000L

  /** Diagnosi dei fotogrammi lunghi nel logcat (`FrameDiagMetric`). */
  val diag: Boolean get() = args.getString("benchDiag") == "true"

  /** Quante volte ripetere il gesto dentro una iterazione (cicli di zoom, coppie di pinch): 1 nello smoke. */
  fun repeats(normal: Int): Int = if (smoke) 1 else normal

  /**
   * `CompilationMode.Partial(BaselineProfileMode.Require)` (§2.6): la misura vale solo se il profilo e' installato.
   * Gli altri valori servono a confrontare (`none`, `full`) o a girare su un'app senza profilo (`useIfAvailable`).
   */
  val compilationMode: CompilationMode
    get() = when (args.getString("benchCompilation")) {
      "useIfAvailable" -> CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)
      "full" -> CompilationMode.Full()
      "none" -> CompilationMode.None()
      else -> CompilationMode.Partial(BaselineProfileMode.Require)
    }

  private const val SMOKE_ITERATIONS = 2
}
