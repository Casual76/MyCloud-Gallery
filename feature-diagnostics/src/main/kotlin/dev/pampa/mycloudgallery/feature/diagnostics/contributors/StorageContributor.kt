package dev.pampa.mycloudgallery.feature.diagnostics.contributors

import android.content.Context
import android.os.StatFs
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.rules.ByteFormat
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.withContext

/**
 * Quanto spazio occupa l'app, in byte. Solo numeri: niente nomi di file ne' percorsi.
 *
 * @property freeBytes lo spazio libero sul volume dei dati dell'app, o null se non si legge.
 */
class StorageSnapshot(
  val databaseBytes: Long,
  val cacheBytes: Long,
  val noBackupBytes: Long,
  val filesBytes: Long,
  val freeBytes: Long?,
  val totalBytes: Long?,
  /** DG-5: quanto pesano i modelli (con i programmi compilati), l'indice di ricerca e il buffer dell'analisi, dentro `noBackupFilesDir`. */
  val modelsBytes: Long = 0,
  val searchBytes: Long = 0,
  val aiBufferBytes: Long = 0,
) {
  val appBytes: Long get() = databaseBytes + cacheBytes + noBackupBytes + filesBytes

  val hasAiAreas: Boolean get() = modelsBytes > 0 || searchBytes > 0 || aiBufferBytes > 0
}

/** Da dove si legge lo spazio ([AppStorageSource] su Android; un fake nei test). */
fun interface StorageSource {
  suspend fun read(): StorageSnapshot
}

/**
 * «Spazio» (docs/fase2-progetto.md §2.6): database, cache e `noBackupFilesDir` (dove stanno l'anello della
 * diagnostica, l'interruttore di sicurezza, l'avanzamento dell'onboarding), piu' lo spazio libero del telefono.
 *
 * Con la memoria quasi piena (meno di [LOW_FREE_BYTES] o del [LOW_FREE_PERCENT]%) c'e' un avviso (E-P3: l'app riduce
 * le cache e continua, ma l'utente lo deve sapere).
 */
class StorageContributor(private val source: StorageSource) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.STORAGE

  override suspend fun collect(): List<DiagItem> {
    val storage = source.read()
    val usage = DiagItem(
      code = DiagCodes.STORAGE_USAGE,
      section = DiagSection.STORAGE,
      severity = DiagSeverity.INFO,
      titleRes = DiagCatalog.titleOf(DiagCodes.STORAGE_USAGE),
      detail = if (storage.hasAiAreas) {
        DiagText(
          R.string.diagnostics_storage_detail_ai,
          listOf(
            ByteFormat.size(storage.databaseBytes),
            ByteFormat.size(storage.cacheBytes),
            ByteFormat.size(storage.noBackupBytes + storage.filesBytes),
            ByteFormat.size(storage.modelsBytes),
            ByteFormat.size(storage.searchBytes),
            ByteFormat.size(storage.aiBufferBytes),
          ),
        )
      } else {
        DiagText(
          R.string.diagnostics_storage_detail,
          listOf(
            ByteFormat.size(storage.databaseBytes),
            ByteFormat.size(storage.cacheBytes),
            ByteFormat.size(storage.noBackupBytes + storage.filesBytes),
          ),
        )
      },
      export = buildMap {
        put("databaseBytes", ExportValue.Num(storage.databaseBytes))
        put("cacheBytes", ExportValue.Num(storage.cacheBytes))
        put("noBackupBytes", ExportValue.Num(storage.noBackupBytes))
        put("filesBytes", ExportValue.Num(storage.filesBytes))
        // DG-5: le tre aree della ricerca (solo numeri).
        if (storage.hasAiAreas) {
          put("modelsBytes", ExportValue.Num(storage.modelsBytes))
          put("searchBytes", ExportValue.Num(storage.searchBytes))
          put("aiBufferBytes", ExportValue.Num(storage.aiBufferBytes))
        }
      },
    )
    val free = storage.freeBytes
    val total = storage.totalBytes
    val low = if (free != null && total != null && total > 0) isLow(free, total) else false
    return if (low && free != null && total != null) {
      listOf(
        usage,
        DiagItem(
          code = DiagCodes.STORAGE_PHONE_LOW,
          section = DiagSection.STORAGE,
          severity = DiagSeverity.WARNING,
          titleRes = DiagCatalog.titleOf(DiagCodes.STORAGE_PHONE_LOW),
          detail = DiagText(R.string.diagnostics_storage_free, listOf(ByteFormat.size(free), ByteFormat.size(total))),
          export = mapOf("freeBytes" to ExportValue.Num(free), "totalBytes" to ExportValue.Num(total)),
        ),
      )
    } else {
      listOf(usage)
    }
  }

  companion object {
    const val LOW_FREE_BYTES: Long = 500_000_000L
    const val LOW_FREE_PERCENT: Int = 5

    fun isLow(freeBytes: Long, totalBytes: Long): Boolean =
      freeBytes < LOW_FREE_BYTES || freeBytes * 100 < totalBytes * LOW_FREE_PERCENT
  }
}

/**
 * Lo spazio vero dell'app: percorre le cartelle (senza seguire i collegamenti simbolici, con un tetto di file per
 * non restare ore in una cartella impazzita) e legge `StatFs` del volume. Gira su I/O.
 */
class AppStorageSource(
  context: Context,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val maxFiles: Int = MAX_FILES,
) : StorageSource {

  private val app: Context = context.applicationContext ?: context

  override suspend fun read(): StorageSnapshot = withContext(dispatchers.io) {
    val volume = app.filesDir
    val stat = try {
      StatFs(volume.path).let { it.availableBytes to it.totalBytes }
    } catch (_: IllegalArgumentException) {
      null
    }
    StorageSnapshot(
      databaseBytes = sizeOf(app.getDatabasePath("placeholder").parentFile),
      cacheBytes = sizeOf(app.cacheDir) + sizeOf(app.externalCacheDir),
      noBackupBytes = sizeOf(app.noBackupFilesDir),
      filesBytes = sizeOf(app.filesDir),
      freeBytes = stat?.first,
      totalBytes = stat?.second,
      modelsBytes = sizeOf(File(app.noBackupFilesDir, "models")),
      searchBytes = sizeOf(File(app.noBackupFilesDir, "search")),
      aiBufferBytes = sizeOf(File(app.noBackupFilesDir, "ai-buffer")),
    )
  }

  private fun sizeOf(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    var total = 0L
    var seen = 0
    try {
      Files.walkFileTree(
        dir.toPath(),
        object : SimpleFileVisitor<java.nio.file.Path>() {
          override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile) total += attrs.size()
            return if (++seen >= maxFiles) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
          }

          override fun visitFileFailed(file: java.nio.file.Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        },
      )
    } catch (_: IOException) {
      // Una cartella illeggibile conta per quello che si e' potuto leggere.
    }
    return total
  }

  companion object {
    /** Oltre questo numero di file il conteggio si ferma: la cifra e' una stima per difetto, non un'esattezza. */
    const val MAX_FILES = 50_000
  }
}
