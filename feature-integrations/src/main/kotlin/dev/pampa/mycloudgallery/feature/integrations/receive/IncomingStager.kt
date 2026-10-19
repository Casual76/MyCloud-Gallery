package dev.pampa.mycloudgallery.feature.integrations.receive

import android.graphics.BitmapFactory
import dev.pampa.mycloudgallery.core.backup.contract.IncomingItem
import dev.pampa.mycloudgallery.core.backup.contract.IncomingLimits
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingReject
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.trust.nas.ImageBounds
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Fase 5B (P11, docs/fase5-progetto.md §2.7 "Ricevere condivisioni", D24, C18, E-X2, E-X8, §4.3 "Lettura difensiva"): la COPIA IMMEDIATA dei file in
// arrivo da un'altra app. Quel che arriva e' input non fidato:
//  - il permesso di lettura dell'altra app dura quanto la nostra activity: i flussi si aprono e si copiano **subito** in `noBackupFilesDir/incoming/`
//    (fuori dal backup di Android, fuori dalla cache), uno alla volta;
//  - i tetti valgono **prima** di scrivere e mentre si scrive, mai dopo: 2 GiB per file, 4 GiB e 500 elementi per condivisione, almeno 1 GiB libero
//    sul telefono; un flusso che non finisce non si legge fino in fondo (si interrompe al tetto) e uno che non consegna piu' byte si abbandona;
//  - `file://` e i provider dell'app si rifiutano **senza aprirli** (`IncomingOrigins`); il tipo si legge dai byte (`IncomingMime`, `MediaSniffer`);
//    l'immagine che dichiara dimensioni da bomba si rifiuta prima che qualcosa la decodifichi (`ImageBounds`);
//  - il nome locale e' l'SHA-256 dei byte: il nome dell'altra app non finisce mai in un percorso;
//  - niente parte da solo: ricevere non avvia nessun caricamento (E-S11), serve il tocco su "Salva sul NAS".
// Nessun log: solo i contatori (codici fissi). Cancellazione: i file a meta' si tolgono.

/** I file che una condivisione ha messo in `incoming/`: chi li carica (P14) li trova da qui. */
interface IncomingFiles {
  /** Il file copiato di [item] nella condivisione [stage], o null se non c'e' (piu'). */
  fun fileOf(stage: IncomingStageId, item: IncomingItem): File?
}

/** Le dimensioni in pixel di un'immagine, dalla sola intestazione. */
class ImageSize(val width: Int, val height: Int)

/** Legge le dimensioni di un'immagine senza decodificarla; null se l'intestazione non si legge. */
fun interface ImageBoundsReader {
  fun read(file: File): ImageSize?
}

/** `BitmapFactory` con `inJustDecodeBounds`: legge solo l'intestazione. */
object BitmapBoundsReader : ImageBoundsReader {
  override fun read(file: File): ImageSize? = try {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    if (options.outWidth > 0 && options.outHeight > 0) ImageSize(options.outWidth, options.outHeight) else null
  } catch (_: RuntimeException) {
    null
  }
}

/**
 * I tetti di una condivisione. Quelli veri sono quelli di D24 ([IncomingLimits]); i test ne danno di piu' piccoli per provare cosa succede al tetto
 * senza scrivere 2 GiB.
 */
class StageLimits(
  val maxFileBytes: Long = IncomingLimits.MAX_FILE_BYTES,
  val maxStageBytes: Long = IncomingLimits.MAX_STAGE_BYTES,
  val maxItems: Int = IncomingLimits.MAX_ITEMS,
  val minFreeAfterBytes: Long = IncomingLimits.MIN_FREE_AFTER_BYTES,
  val maxExamined: Int = IncomingStager.MAX_EXAMINED,
)

class IncomingStager(
  private val root: File,
  private val freeBytes: () -> Long = { root.usableSpace },
  private val limits: StageLimits = StageLimits(),
  private val bounds: ImageBoundsReader = BitmapBoundsReader,
  /** Quanto si aspetta un flusso che non consegna piu' niente prima di abbandonarlo. */
  private val stallMs: Long = DEFAULT_STALL_MS,
  private val newId: () -> String = { ShareCache.randomToken() },
  private val io: CoroutineContext = Dispatchers.IO,
) : IncomingFiles {

  private val guard = BlockingGuard(io)
  private val temps = AtomicInteger()

  // --- Copiare --------------------------------------------------------------------------------------------------------------------

  /**
   * Copia [streams] (una condivisione) in una cartella nuova di `incoming/`. I rifiuti si contano per motivo; una condivisione tutta rifiutata
   * non lascia niente su disco. Non lancia per un flusso che non va: lo conta come `UNREADABLE`.
   */
  suspend fun stage(streams: List<IncomingStream>): IncomingStage {
    val id = IncomingStageId(newId())
    val dir = File(root, id.value)
    val rejected = EnumMap<IncomingReject, Int>(IncomingReject::class.java)
    fun reject(reason: IncomingReject, count: Int = 1) {
      rejected[reason] = (rejected[reason] ?: 0) + count
    }
    val accepted = ArrayList<IncomingItem>()
    var total = 0L
    var finished = false
    try {
      withContext(io) { if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cartella dei file in arrivo non creata.") }
      for ((position, stream) in streams.withIndex()) {
        currentCoroutineContext().ensureActive()
        if (position >= limits.maxExamined) {
          // Un'infinita' di elementi: gli ultimi non si guardano nemmeno, si contano.
          reject(IncomingReject.TOO_MANY, streams.size - position)
          break
        }
        when {
          stream.origin == IncomingOrigin.FILE_SCHEME -> reject(IncomingReject.FILE_SCHEME)
          stream.origin == IncomingOrigin.OWN_PROVIDER -> reject(IncomingReject.OWN_PROVIDER)
          accepted.size >= limits.maxItems -> reject(IncomingReject.TOO_MANY)
          (stream.declaredSize ?: 0L) > limits.maxFileBytes -> reject(IncomingReject.TOO_LARGE)
          total + (stream.declaredSize ?: 0L) > limits.maxStageBytes -> reject(IncomingReject.STAGE_TOO_BIG)
          freeBytes() - (stream.declaredSize ?: 0L) < limits.minFreeAfterBytes -> reject(IncomingReject.NO_SPACE)
          else -> when (val result = copyOne(stream, dir, total)) {
            is Copied -> {
              // Lo stesso contenuto due volte nella stessa condivisione si tiene una volta sola.
              if (accepted.none { it.sha256 == result.item.sha256 }) {
                accepted += result.item
                total += result.item.size
              }
            }
            is Refused -> reject(result.reason)
          }
        }
      }
      finished = true
    } finally {
      // Una cancellazione o un guasto non lasciano file a meta'; una condivisione senza niente di buono non lascia nemmeno la cartella.
      if (!finished || accepted.isEmpty()) {
        withContext(NonCancellable + io) {
          if (!dir.deleteRecursively()) {
            // Un lavoro abbandonato (flusso fermo, cancellazione) puo' tenere ancora aperto un file: su Windows non si toglie. Si riprova finche' non lo
            // lascia; la cartella vuota se ne va con lui. Senza un lavoro, il giro di pulizia all'avvio la toglierebbe comunque (`sweep`).
            guard.launch { repeat(CLEANUP_RETRIES) { if (dir.deleteRecursively() || !dir.exists()) return@launch else delay(CLEANUP_WAIT_MS) } }
          }
        }
      }
    }
    return IncomingStage(id, accepted, rejected)
  }

  private sealed interface Outcome

  private class Copied(val item: IncomingItem) : Outcome

  private class Refused(val reason: IncomingReject) : Outcome

  private suspend fun copyOne(stream: IncomingStream, dir: File, totalSoFar: Long): Outcome {
    val live = BlockingGuard.Live()
    val outcome = guard.run(stallMs, live) { blockingCopy(stream, dir, totalSoFar, live) }
    // Fermo (non consegna piu' byte) o abbandonato: il file a meta' lo toglie il lavoro stesso quando si sblocca; qui non c'e' niente da tenere.
    return outcome ?: Refused(IncomingReject.UNREADABLE)
  }

  /** La copia vera, bloccante: gira su un thread di lavoro, mai su quello della UI. */
  private fun blockingCopy(stream: IncomingStream, dir: File, totalSoFar: Long, live: BlockingGuard.Live): Outcome {
    val input: InputStream = try {
      stream.open()
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      return Refused(IncomingReject.UNREADABLE)
    }
    live.closeable = input
    var temp: File? = null
    var kept = false
    try {
      input.use { source ->
        val buffer = ByteArray(BUFFER)
        val first = readUpTo(source, buffer, IncomingMime.HEAD_BYTES)
        if (first <= 0) return Refused(IncomingReject.NOT_MEDIA)
        val head = buffer.copyOf(first)
        val mime = IncomingMime.of(head, stream.declaredMime) ?: return Refused(IncomingReject.NOT_MEDIA)
        live.progress.addAndGet(first.toLong())
        val file = File(dir, ".w${temps.incrementAndGet()}").also { temp = it }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var sinceSpaceCheck = 0L
        file.outputStream().use { out ->
          out.write(buffer, 0, first)
          digest.update(buffer, 0, first)
          size += first
          while (true) {
            if (live.abandoned) return Refused(IncomingReject.UNREADABLE)
            val n = source.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            size += n
            // I tetti valgono mentre si legge: un flusso che non finisce non si legge fino in fondo.
            if (size > limits.maxFileBytes) return Refused(IncomingReject.TOO_LARGE)
            if (totalSoFar + size > limits.maxStageBytes) return Refused(IncomingReject.STAGE_TOO_BIG)
            sinceSpaceCheck += n
            if (sinceSpaceCheck >= SPACE_CHECK_BYTES) {
              sinceSpaceCheck = 0
              if (freeBytes() < limits.minFreeAfterBytes) return Refused(IncomingReject.NO_SPACE)
            }
            out.write(buffer, 0, n)
            digest.update(buffer, 0, n)
            live.progress.addAndGet(n.toLong())
          }
        }
        if (live.abandoned) return Refused(IncomingReject.UNREADABLE)
        if (freeBytes() < limits.minFreeAfterBytes) return Refused(IncomingReject.NO_SPACE)
        // Un'immagine che dichiara dimensioni da bomba non si lascia a niente che la decodifichi (ImageBounds): rifiutata qui.
        if (mime.startsWith("image/")) {
          val dimensions = bounds.read(file)
          if (dimensions != null && !ImageBounds.isDecodable(dimensions.width, dimensions.height)) return Refused(IncomingReject.TOO_LARGE)
        }
        val sha = Sha256(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        val target = File(dir, sha.hex)
        if (target.exists()) {
          // Gia' presente (lo stesso contenuto due volte): si tiene quello che c'e'.
          file.delete()
        } else if (!file.renameTo(target)) {
          return Refused(IncomingReject.UNREADABLE)
        }
        kept = true
        return Copied(IncomingItem(name = sha.hex, sha256 = sha, size = size, mime = mime, displayName = cleanName(stream.displayName)))
      }
    } catch (e: IOException) {
      // Disco pieno a meta': e' mancanza di spazio, non un flusso rotto.
      return Refused(if (freeBytes() < limits.minFreeAfterBytes) IncomingReject.NO_SPACE else IncomingReject.UNREADABLE)
    } catch (e: SecurityException) {
      return Refused(IncomingReject.UNREADABLE)
    } finally {
      if (!kept) temp?.delete()
    }
  }

  // --- Dopo --------------------------------------------------------------------------------------------------------------------------

  override fun fileOf(stage: IncomingStageId, item: IncomingItem): File? {
    if (!ITEM_NAME.matches(item.name)) return null
    val file = File(File(root, stage.value), item.name)
    return file.takeIf { it.isFile }
  }

  /** Tutti i file di [stage] ci sono ancora, della dimensione giusta (nessun file e' stato tolto o sostituito dopo la copia). */
  fun isIntact(stage: IncomingStage): Boolean = stage.accepted.isNotEmpty() && stage.accepted.all { fileOf(stage.id, it)?.length() == it.size }

  /** L'utente rinuncia, o la condivisione e' vecchia: la cartella si toglie. Idempotente. */
  suspend fun discard(id: IncomingStageId) {
    withContext(NonCancellable + io) { File(root, id.value).deleteRecursively() }
  }

  /**
   * All'avvio dell'app: toglie ogni condivisione che il database non cita ([keep] = gli `stage_id` degli `incoming_item` aperti) e i resti di
   * copie a meta' (`.w*`) nelle altre. Cio' che e' in coda per il NAS non si tocca.
   */
  fun sweep(keep: Set<String>) {
    val children = root.listFiles() ?: return
    for (child in children) {
      if (child.isDirectory && child.name in keep) {
        child.listFiles { f -> f.name.startsWith(".w") }?.forEach { it.delete() }
      } else {
        child.deleteRecursively()
      }
    }
  }

  // --- Piccole cose ------------------------------------------------------------------------------------------------------------------

  /** Legge fino a [limit] byte o a fine flusso; i byte letti. */
  private fun readUpTo(source: InputStream, buffer: ByteArray, limit: Int): Int {
    var total = 0
    while (total < limit) {
      val n = source.read(buffer, total, limit - total)
      if (n < 0) break
      total += n
    }
    return total
  }

  /** Il nome dell'altra app, ripulito: niente controlli ne' separatori di percorso, al massimo 200 caratteri. Mai usato come nome locale. */
  private fun cleanName(name: String?): String? {
    val cleaned = name?.filter { it >= ' ' && it != '/' && it != '\\' && it != '\u007f' }?.trim()?.take(MAX_NAME)
    return cleaned?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
  }

  companion object {
    const val DEFAULT_STALL_MS: Long = 30_000

    /** Quanti elementi di una condivisione si guardano al massimo (il resto si conta come "troppi"). */
    const val MAX_EXAMINED: Int = 2_000

    private const val CLEANUP_RETRIES = 50
    private const val CLEANUP_WAIT_MS = 100L
    private const val BUFFER = 256 * 1024
    private const val SPACE_CHECK_BYTES = 8L * 1024 * 1024
    private const val MAX_NAME = 200
    private val ITEM_NAME = Regex("^[0-9a-f]{64}$")
  }
}
