package dev.pampa.mycloudgallery.core.ai

import android.content.Context
import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.models.LocalModels
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import org.junit.Assume.assumeTrue

/**
 * Gli arnesi comuni dei test strumentati dei motori (Fase 6, P4).
 *
 * **I modelli** (157 MiB e 370 MiB) non stanno nel repository ne' nell'APK: si copiano sul dispositivo con
 * `adb push <file> /data/local/tmp/mcg-models/<nome del catalogo>` (i file stanno sul PC in `%USERPROFILE%\.mycloudgallery\models\`) e
 * [TestModels] li porta nella cartella dei file dell'APK di prova con la shell (l'app non legge `/data/local/tmp`). Da li' passano da
 * `LocalModels.files`, cioe' dalla verifica vera di core-models (dimensione e SHA-256 a ogni apertura, descrittore duplicato per il
 * motore): le prove vedono lo stesso percorso dell'app. Senza il file il test si salta (`Assume`), mai con un falso verde: il resoconto
 * del pacchetto elenca i test eseguiti e quelli saltati.
 */
internal object TestModels {

  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  /** La cartella `models/` dell'APK di prova: `<sha256>` verificati e, in `cache/`, i programmi compilati dei motori. */
  val root: File get() = File(context.filesDir, "p4-models").apply { mkdirs() }

  val cacheRoot: File get() = File(root, "cache").apply { mkdirs() }

  private var cached: LocalModels? = null

  /** I modelli di prova: nessuna rete, nessuna sorgente (i file arrivano con adb). */
  @Synchronized
  fun models(): LocalModels =
    cached ?: LocalModels(
      root = root,
      fetcher = ModelFetcher { _, _, _, _ -> FetchResult.NotFound },
      networkGate = ModelNetworkGate { _, _ -> ModelNetworkVerdict.Refused("prova") },
    ).also { cached = it }

  val files: ModelFiles get() = models().files

  /** Porta il modello sul dispositivo di prova (se c'e' in `/data/local/tmp/mcg-models/`) e dice se c'e'. */
  fun available(spec: ModelSpec): Boolean {
    val out = File(root, spec.sha256.hex)
    if (out.isFile && out.length() == spec.sizeBytes) return true
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /data/local/tmp/mcg-models/${spec.fileName}")
    val partial = File(root, spec.sha256.hex + ".copy")
    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> partial.outputStream().use { input.copyTo(it, 1 shl 20) } }
    if (partial.length() != spec.sizeBytes) {
      partial.delete()
      return false
    }
    // Il nome finale c'e' solo a copia finita: `models/<sha256>` contiene sempre e solo un file della dimensione giusta.
    check(partial.renameTo(out)) { "rinomina del modello copiato" }
    // Su un dispositivo con poco spazio (l'emulatore: 2 GB liberi) l'originale si toglie dopo la copia: -e mcgConsumeModels true.
    if (InstrumentationRegistry.getArguments().getString("mcgConsumeModels") == "true") {
      val rm = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("rm /data/local/tmp/mcg-models/${spec.fileName}")
      ParcelFileDescriptor.AutoCloseInputStream(rm).use { it.readBytes() }
    }
    return true
  }

  /** Salta il test se il modello non e' stato copiato sul dispositivo. */
  fun require(spec: ModelSpec) {
    assumeTrue("Modello ${spec.fileName} assente: adb push in /data/local/tmp/mcg-models/ (vedi DeviceSupport.kt)", available(spec))
  }

  val query270m: ModelSpec get() = ModelCatalog.QUERY_270M
  val index440m: ModelSpec get() = ModelCatalog.INDEX_440M
}

/** I nomi delle classi caricate nel processo, dall'istantanea HPROF di ART (un record LOAD CLASS per classe, da qualunque classloader). */
internal object LoadedClasses {

  /** ML Kit (le API, le classi interne in gms, la pipeline del testo), DataTransport e i componenti di Firebase che ML Kit avvia. */
  val WATCHED_PREFIXES: List<String> = listOf(
    "com.google.ml" + "kit.",
    "com.google.android.gms.internal.ml" + "kit_",
    "com.google.android.libraries.vision.",
    "com.google.android.datatransport.",
    "com.google.firebase.components.",
  )

  /**
   * Non si usa `ClassLoader.findLoadedClass`: su ART, se la classe non c'e' ancora, la cerca nei file dex e la carica, e la prova
   * produrrebbe da sola cio' che cerca (scoperta di P0). L'istantanea dell'heap dice solo cio' che e' gia' caricato.
   */
  fun names(context: Context): Set<String> {
    val file = File(context.cacheDir, "p4-loaded-classes.hprof")
    try {
      Debug.dumpHprofData(file.path)
      return read(file)
    } finally {
      file.delete()
    }
  }

  fun watched(names: Set<String>): List<String> = names.filter { name -> WATCHED_PREFIXES.any { name.startsWith(it) } }.sorted()

  private const val TAG_STRING = 0x01
  private const val TAG_LOAD_CLASS = 0x02

  private fun read(file: File): Set<String> {
    DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
      while (input.readByte() != 0.toByte()) Unit
      val idSize = input.readInt()
      check(idSize == 4 || idSize == 8) { "dimensione degli id HPROF inattesa: $idSize" }
      input.readLong()
      val strings = HashMap<Long, String>()
      val nameIds = ArrayList<Long>()
      while (true) {
        val tag = input.read()
        if (tag < 0) break
        input.readInt()
        val length = input.readInt().toLong() and 0xFFFFFFFFL
        when (tag) {
          TAG_STRING -> {
            val id = readId(input, idSize)
            val bytes = ByteArray((length - idSize).toInt())
            input.readFully(bytes)
            strings[id] = String(bytes, Charsets.UTF_8)
          }
          TAG_LOAD_CLASS -> {
            input.readInt()
            readId(input, idSize)
            input.readInt()
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

/** Misure comuni. */
internal object Probes {
  /** La memoria del processo in MB (PSS totale, `Debug.getMemoryInfo`). */
  fun pssMb(): Double {
    val info = Debug.MemoryInfo()
    Debug.getMemoryInfo(info)
    return info.totalPss / 1024.0
  }
}
