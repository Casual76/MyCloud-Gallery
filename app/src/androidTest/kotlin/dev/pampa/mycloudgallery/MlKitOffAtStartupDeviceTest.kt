package dev.pampa.mycloudgallery

import android.app.job.JobScheduler
import android.content.pm.PackageManager
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P0, D41, risposta Q4; "Fatto quando" di docs/fase6-progetto.md §4.4): con la lettura del testo spenta (il default: nessuno
 * l'ha attivata) **ML Kit non si inizializza** nel processo dell'app vera (manifest fuso della debug, Application con Hilt, lavori):
 * 1. `MlKitInitProvider` non e' fra i provider installati (tolto dal manifest fuso, `tools:node="remove"` di core-ai);
 * 2. nessuna classe di ML Kit o di DataTransport e' caricata nel processo (controllo positivo: la classe dell'Application lo e');
 * 3. nessun lavoro di DataTransport e' pianificato in JobScheduler.
 *
 * Le classi caricate si leggono da un'istantanea della memoria del processo (HPROF: un record LOAD CLASS per ogni classe caricata,
 * da qualunque classloader), **non** con `ClassLoader.findLoadedClass`: su ART quel metodo, se la classe non c'e' ancora, la cerca
 * nei file dex del PathClassLoader e **la carica** (un'ottimizzazione di `VMClassLoader.findLoadedClass`), quindi la prova
 * produrrebbe da sola la violazione che cerca.
 *
 * Il test raccoglie tutte le violazioni e le riporta insieme. I nomi delle classi sono stringhe: questo file non usa ML Kit
 * (Phase6ArchitectureTest lo vieta fuori da core-ai).
 */
@RunWith(AndroidJUnit4::class)
class MlKitOffAtStartupDeviceTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun conLaLetturaSpentaMlKitNonParte() {
    val problems = mutableListOf<String>()

    // 1. Il provider che avvierebbe ML Kit all'avvio del processo non c'e'.
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
    val providers = info.providers.orEmpty().map { it.name }
    if (providers.any { it.endsWith(".MlKitInitProvider") }) problems += "MlKitInitProvider installato"

    // 2. Nessuna classe di ML Kit o di DataTransport caricata (prima di ogni altra cosa che potrebbe caricarne).
    val loaded = loadedClassNames()
    val application = context.applicationContext.javaClass.name
    assertTrue("controllo positivo: $application non e' fra le ${loaded.size} classi caricate", application in loaded)
    for (prefix in WATCHED_PREFIXES) {
      val found = loaded.filter { it.startsWith(prefix) }.sorted()
      if (found.isNotEmpty()) problems += "${found.size} classi $prefix* caricate con la lettura del testo spenta (${found.take(MAX_LISTED)})"
    }

    // 3. Nessun lavoro di DataTransport pianificato.
    val jobs = context.getSystemService(JobScheduler::class.java).allPendingJobs
    val transport = jobs.filter { it.service.className.startsWith(TRANSPORT) }
    if (transport.isNotEmpty()) problems += "lavori di DataTransport pianificati: ${transport.map { it.service.className }}"

    assertTrue(problems.joinToString("; "), problems.isEmpty())
  }

  /** I nomi (con i punti) delle classi caricate nel processo, dall'istantanea HPROF scritta e cancellata qui. */
  private fun loadedClassNames(): Set<String> {
    val file = File(context.cacheDir, "p0-mlkit-off.hprof")
    try {
      Debug.dumpHprofData(file.path)
      return HprofClasses.read(file)
    } finally {
      file.delete()
    }
  }

  private companion object {
    const val TRANSPORT = "com.google.android.datatransport."
    const val MAX_LISTED = 3

    /**
     * ML Kit (le sue API, le sue classi interne in gms e la pipeline del testo), DataTransport e i componenti di Firebase che ML Kit
     * avvia. I pacchetti sono quelli degli AAR di text-recognition 16.0.1 e delle sue dipendenze.
     */
    val WATCHED_PREFIXES = listOf(
      "com.google.ml" + "kit.",
      "com.google.android.gms.internal.ml" + "kit_",
      "com.google.android.libraries.vision.",
      TRANSPORT,
      "com.google.firebase.components.",
    )
  }
}

/**
 * Lettore minimo del formato HPROF di ART ("JAVA PROFILE 1.0.3"): tiene i record STRING (0x01) e LOAD CLASS (0x02), salta gli altri
 * (anche i segmenti dell'heap) per lunghezza. ART scrive i nomi delle classi gia' nella forma con i punti (`a.b.C`, `a.b.C[]`).
 */
internal object HprofClasses {
  private const val TAG_STRING = 0x01
  private const val TAG_LOAD_CLASS = 0x02

  fun read(file: File): Set<String> {
    DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
      while (input.readByte() != 0.toByte()) Unit // intestazione, terminata da uno zero
      val idSize = input.readInt()
      check(idSize == 4 || idSize == 8) { "dimensione degli id HPROF inattesa: $idSize" }
      input.readLong() // istante dell'istantanea
      val strings = HashMap<Long, String>()
      val nameIds = ArrayList<Long>()
      while (true) {
        val tag = input.read()
        if (tag < 0) break
        input.readInt() // tempo relativo
        val length = input.readInt().toLong() and 0xFFFFFFFFL
        when (tag) {
          TAG_STRING -> {
            val id = readId(input, idSize)
            val bytes = ByteArray((length - idSize).toInt())
            input.readFully(bytes)
            strings[id] = String(bytes, Charsets.UTF_8)
          }
          TAG_LOAD_CLASS -> {
            input.readInt() // numero di serie
            readId(input, idSize) // oggetto classe
            input.readInt() // serie della traccia
            nameIds += readId(input, idSize)
          }
          else -> skipFully(input, length)
        }
      }
      return nameIds.mapNotNullTo(HashSet()) { strings[it] }
    }
  }

  private fun readId(input: DataInputStream, idSize: Int): Long =
    if (idSize == 4) input.readInt().toLong() and 0xFFFFFFFFL else input.readLong()

  private fun skipFully(input: DataInputStream, count: Long) {
    var left = count
    while (left > 0) {
      val skipped = input.skip(left)
      if (skipped <= 0) {
        if (input.read() < 0) throw EOFException("HPROF troncato")
        left--
      } else {
        left -= skipped
      }
    }
  }
}
