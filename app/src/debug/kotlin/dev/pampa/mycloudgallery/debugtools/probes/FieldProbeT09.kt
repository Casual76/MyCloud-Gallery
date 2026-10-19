package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Activity
import android.content.IntentSender
import android.os.Build
import android.os.SystemClock
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaRewriter
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SOLO build debug (Fase 5B, P0-B; docs/android.md T-09): la prova guidata del **cestino**, sulle sole cartelle `mcg-prova` del telefono
 * ([FieldProbePhone]). Misura, con numeri nel resoconto ([FieldProbeStore]):
 *  - a) b) c) cio' che il MediaStore fa quando l'utente cestina, ripristina e svuota in Samsung Gallery: una fotografia di riferimento, poi
 *    "Confronta" dopo ogni gesto ([compare]): righe sparite, nuove, cestinate, ricomparse con lo stesso `_ID` o con un altro, voci di
 *    `deleted_files`, generazione;
 *  - d) e) `createTrashRequest` sull'app: `IS_TRASHED`, scadenza (`DATE_EXPIRES`, in giorni), righe ancora visibili, e se il cestino di Samsung
 *    Gallery le mostra (risposta dell'utente);
 *  - f) la richiesta con tante righe quante ce ne sono, fino a 500;
 *  - (D19, E-E10) il permesso `createWriteRequest` e la riscrittura di una foto con gli **stessi byte** (`PhoneMediaRewriter`): non cambia il
 *    contenuto, prova il dialogo, `rw` e la rilettura sul telefono vero.
 *
 * Le richieste di sistema partono dal dialogo di sistema (la schermata lancia l'`IntentSender` e poi chiama `finish...`); niente si cancella per
 * sempre: il cestino di Android tiene 30 giorni e [prepareRestore] rimette le foto al loro posto.
 */
internal class FieldProbeT09(
  private val phone: FieldProbePhone,
  private val store: FieldProbeStore,
  private val intents: PhoneMediaIntents,
  private val rewriter: PhoneMediaRewriter,
  private val cacheDir: File,
  /** Gli `_ID` in `deleted_files` dopo la generazione data (le voci che l'app puo' vedere), o null se la tabella non c'e'. */
  private val deletedSince: suspend (Long) -> List<Long>?,
  private val nowSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val elapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
) {

  private var baseline: List<ProbeRow> = emptyList()
  private var baselineGeneration: Long = 0
  private var trashedIds: List<Long> = emptyList()
  private var writeRow: ProbeRow? = null

  /** Ambiente: la versione di Android e quante righe di prova ci sono. Restituisce le righe. */
  fun environment(): Int {
    val rows = phone.rows()
    store.set(FieldProbeKey.ENV_SDK, Build.VERSION.SDK_INT.toLong())
    store.set(FieldProbeKey.ENV_ROWS, rows.size.toLong())
    store.set(FieldProbeKey.ENV_ROWS_IMAGES, rows.count { !it.isVideo }.toLong())
    store.set(FieldProbeKey.ENV_ROWS_VIDEOS, rows.count { it.isVideo }.toLong())
    return rows.size
  }

  /** La fotografia di riferimento (T-09 a): dopo, l'utente cestina in Samsung Gallery e si chiama [compare]. */
  fun takeBaseline(): Int {
    baseline = phone.rows()
    baselineGeneration = phone.generation()
    store.set(FieldProbeKey.T09_BASE_ROWS, baseline.size.toLong())
    return baseline.size
  }

  /** Confronta le righe di adesso con il riferimento e scrive un blocco **indicizzato** nel resoconto. Falso se non c'e' un riferimento. */
  suspend fun compare(): Boolean {
    if (baseline.isEmpty()) return false
    val now = phone.rows()
    val diff = FieldProbeMath.diff(baseline, now)
    val index = store.nextIndex(FieldProbeKey.T09_DIFF_MISSING)
    store.set(FieldProbeKey.T09_DIFF_MISSING, diff.missing.toLong(), index)
    store.set(FieldProbeKey.T09_DIFF_NEW, diff.new.toLong(), index)
    store.set(FieldProbeKey.T09_DIFF_TRASHED, diff.trashed.toLong(), index)
    store.set(FieldProbeKey.T09_DIFF_SAME_ID_PRESENT, diff.sameIdPresent.toLong(), index)
    store.set(FieldProbeKey.T09_DIFF_OTHER_ID_BACK, diff.otherIdBack.toLong(), index)
    store.set(FieldProbeKey.T09_DIFF_GENERATION_DELTA, phone.generation() - baselineGeneration, index)
    val ids = baseline.mapTo(HashSet()) { it.id }
    val deleted = withContext(Dispatchers.IO) { deletedSince(baselineGeneration) }
    store.set(FieldProbeKey.T09_DIFF_DELETED_FILES, (deleted?.count { it in ids } ?: -1).toLong(), index)
    return true
  }

  // --- d) e) f) createTrashRequest -------------------------------------------------------------------------------------------------

  /** La richiesta di cestino di [count] foto o video (o, con [many], di tutte le righe fino a 500). Null se non c'e' niente da cestinare. */
  fun prepareTrash(count: Int, many: Boolean): IntentSender? {
    val limit = if (many) MANY else count
    val picked = FieldProbeMath.pick(phone.rows(), limit)
    if (picked.isEmpty()) return null
    trashedIds = picked.map { it.id }
    store.set(if (many) FieldProbeKey.T09_MANY_REQUESTED else FieldProbeKey.T09_TRASH_REQUESTED, picked.size.toLong())
    return intents.trashRequest(picked.map(phone::refOf), true)
  }

  /** Dopo il dialogo: com'e' andata ([resultCode] di `Activity`) e cosa dice adesso il MediaStore di quelle righe. */
  fun finishTrash(resultCode: Int, many: Boolean) {
    val relevant = phone.rows().filter { it.id in trashedIds }
    store.set(if (many) FieldProbeKey.T09_MANY_RESULT else FieldProbeKey.T09_TRASH_RESULT, resultCode.toLong())
    store.set(if (many) FieldProbeKey.T09_MANY_IS_TRASHED else FieldProbeKey.T09_TRASH_IS_TRASHED, relevant.count { it.isTrashed }.toLong())
    if (many) return
    store.set(FieldProbeKey.T09_TRASH_VISIBLE_DEFAULT, relevant.count { !it.isTrashed }.toLong())
    FieldProbeMath.expiresDays(relevant, nowSeconds())?.let { (min, max) ->
      store.set(FieldProbeKey.T09_TRASH_EXPIRES_DAYS_MIN, min)
      store.set(FieldProbeKey.T09_TRASH_EXPIRES_DAYS_MAX, max)
    }
  }

  /** La risposta dell'utente: il cestino di Samsung Gallery mostra le foto cestinate dall'app? */
  fun answerSamsungTrash(shown: Boolean) {
    store.set(FieldProbeKey.T09_TRASH_IN_SAMSUNG, if (shown) 1 else 0)
  }

  /** Rimette al loro posto le righe cestinate dall'ultima richiesta (`createTrashRequest` con `trash = false`). */
  fun prepareRestore(): IntentSender? {
    val trashed = phone.rows().filter { it.id in trashedIds && it.isTrashed }
    if (trashed.isEmpty()) return null
    store.set(FieldProbeKey.T09_UNTRASH_REQUESTED, trashed.size.toLong())
    return intents.trashRequest(trashed.map(phone::refOf), false)
  }

  fun finishRestore(resultCode: Int) {
    val relevant = phone.rows().filter { it.id in trashedIds }
    store.set(FieldProbeKey.T09_UNTRASH_RESULT, resultCode.toLong())
    store.set(FieldProbeKey.T09_UNTRASH_IS_TRASHED, relevant.count { it.isTrashed }.toLong())
    store.set(FieldProbeKey.T09_UNTRASH_SAME_IDS, relevant.count { !it.isTrashed }.toLong())
  }

  // --- createWriteRequest e riscrittura con gli stessi byte ---------------------------------------------------------------------------

  /** Il permesso di scrivere su una foto di prova (la prima). Null se non ce n'e'. */
  fun prepareWrite(): IntentSender? {
    val row = FieldProbeMath.pick(phone.rows(), 1, photosOnly = true).firstOrNull() ?: return null
    writeRow = row
    store.set(FieldProbeKey.T09_WRITE_SIZE_BEFORE, row.size)
    return intents.writeRequest(listOf(phone.refOf(row)))
  }

  /**
   * Dopo il dialogo: se l'utente ha dato il permesso, riscrive la foto con i **suoi stessi byte** (copia in cache, SHA-256, `PhoneMediaRewriter`) e
   * misura codice, tempo, dimensione e generazione dopo.
   */
  suspend fun finishWrite(resultCode: Int) {
    store.set(FieldProbeKey.T09_WRITE_DIALOG, resultCode.toLong())
    val row = writeRow ?: return
    if (resultCode != Activity.RESULT_OK) return
    val generationBefore = row.generationModified ?: 0L
    withContext(Dispatchers.IO) {
      val copy = File(cacheDir, "t09-write.bin").also { cacheDir.mkdirs() }
      try {
        val digest = MessageDigest.getInstance("SHA-256")
        phone.open(row).use { input ->
          copy.outputStream().use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
              val n = input.read(buffer)
              if (n < 0) break
              out.write(buffer, 0, n)
              digest.update(buffer, 0, n)
            }
          }
        }
        val sha = Sha256(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        val started = elapsedMs()
        val result = rewriter.replace(phone.refOf(row), copy, sha)
        store.set(FieldProbeKey.T09_WRITE_MS, elapsedMs() - started)
        store.set(FieldProbeKey.T09_WRITE_CODE, FieldProbeMath.rewriteCode(result))
      } catch (_: IOException) {
        store.set(FieldProbeKey.T09_WRITE_CODE, WRITE_CODE_UNREADABLE)
      } finally {
        copy.delete()
      }
      phone.rows().firstOrNull { it.id == row.id }?.let { after ->
        store.set(FieldProbeKey.T09_WRITE_SIZE_AFTER, after.size)
        store.set(FieldProbeKey.T09_WRITE_GENERATION_DELTA, (after.generationModified ?: 0L) - generationBefore)
      }
    }
  }

  companion object {
    /** Il tetto della prova (f): 500 righe in una richiesta (il limite di sistema e' 2.000). */
    const val MANY: Int = 500

    /** Il codice "non sono riuscito a leggere la foto per prepararla": 6, dopo i cinque esiti di `PhoneMediaRewriter`. */
    const val WRITE_CODE_UNREADABLE: Long = 6L

    private const val BUFFER = 64 * 1024
    private const val MILLIS_PER_SECOND = 1000L
  }
}
