package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Activity
import android.content.IntentSender
import android.os.Build
import android.os.SystemClock
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaRewriter
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteResult
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
 *  - (D19, E-E10) il permesso `createWriteRequest` e la riscrittura di una foto (`PhoneMediaRewriter`): prova il dialogo, `rw` e la rilettura sul
 *    telefono vero. Il riscrittore non scrive piu' se la riga ha gia' i byte che deve avere (R3 della revisione 5B: un giro ripetuto non
 *    riscrive), quindi con gli stessi byte non scriverebbe mai e la prova non proverebbe niente: la foto si riscrive con **un byte in piu' in
 *    coda** e subito dopo si rimette **com'era** (due scritture vere, due riletture), e il resoconto dice se la seconda e' riuscita.
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

  /** La foto scelta per la riscrittura: l'`_ID` e la generazione di prima del dialogo (la riga si rilegge dal telefono a prova finita). */
  private var writeRowId: Long? = null
  private var writeGenerationBefore: Long = 0

  /** La stessa riga com'era quando si e' chiesto il permesso, se questo oggetto l'ha vista (dopo un ripristino non c'e': si rilegge dal telefono). */
  private var writeRow: ProbeRow? = null

  init {
    restore()
  }

  /**
   * LA-7: la fotografia di riferimento, le righe cestinate e la foto in scrittura stanno anche su file ([FieldProbeStore.saveSession]). T-09 manda
   * in Samsung Gallery e al ritorno l'app puo' essere stata chiusa o la schermata ricreata: stavano solo in memoria e "Confronta" diceva "Prima la
   * fotografia di riferimento", "Ripristina" non trovava le righe.
   */
  private fun restore() {
    val lines = store.loadSession(SESSION) ?: return
    baseline = lines.filter { it.startsWith(ROW) }.mapNotNull { ProbeRowMarks.decode(it.removePrefix(ROW)) }
    baselineGeneration = lines.firstNotNullOfOrNull { it.removePrefixOrNull(GENERATION)?.toLongOrNull() } ?: 0L
    trashedIds = lines.firstNotNullOfOrNull { it.removePrefixOrNull(TRASHED) }?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
    writeRowId = lines.firstNotNullOfOrNull { it.removePrefixOrNull(WRITE_ID)?.toLongOrNull() }
    writeGenerationBefore = lines.firstNotNullOfOrNull { it.removePrefixOrNull(WRITE_GENERATION)?.toLongOrNull() } ?: 0L
  }

  private fun persist() {
    val lines = ArrayList<String>()
    baseline.forEach { lines += ROW + ProbeRowMarks.encode(it) }
    if (baseline.isNotEmpty()) lines += GENERATION + baselineGeneration
    if (trashedIds.isNotEmpty()) lines += TRASHED + trashedIds.joinToString(",")
    writeRowId?.let {
      lines += WRITE_ID + it
      lines += WRITE_GENERATION + writeGenerationBefore
    }
    store.saveSession(SESSION, lines)
  }

  private fun String.removePrefixOrNull(prefix: String): String? = if (startsWith(prefix)) removePrefix(prefix) else null

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
    persist()
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
    persist()
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
    writeRowId = row.id
    writeGenerationBefore = row.generationModified ?: 0L
    persist()
    store.set(FieldProbeKey.T09_WRITE_SIZE_BEFORE, row.size)
    // VE-2 della verifica funzionale 5B: il dialogo si chiede **come lo chiede l'app** (i fogli della 5B passano righe senza MIME,
    // `AppPhoneSystemDialogs`); la riscrittura dopo usa il riferimento con il tipo, come `AppPhoneRewriter`. Prima la prova usava il tipo in
    // tutte e due le chiamate e sul telefono provava una coppia di indirizzi che l'app non usa: un dialogo su `files/<id>` rifiutato dal sistema,
    // o un permesso che non vale per l'URI scritto, non si sarebbero visti.
    return intents.writeRequest(listOf(phone.untypedRefOf(row)))
  }

  /**
   * Dopo il dialogo: se l'utente ha dato il permesso, riscrive la foto con **un byte in piu' in coda** (copia in cache, SHA-256,
   * `PhoneMediaRewriter`) e, se e' riuscito, la rimette **com'era**; misura codice, tempo, dimensione e generazione dopo. Se la seconda scrittura non
   * riesce il codice e' [WRITE_CODE_NOT_RESTORED].
   */
  suspend fun finishWrite(resultCode: Int) {
    store.set(FieldProbeKey.T09_WRITE_DIALOG, resultCode.toLong())
    val id = writeRowId ?: return
    if (resultCode != Activity.RESULT_OK) return
    // Dopo un ripristino il riferimento ha solo i numeri: la riga si rilegge dal telefono (il tipo serve per aprirla).
    val row = writeRow ?: phone.rows().firstOrNull { it.id == id } ?: return
    val generationBefore = writeGenerationBefore
    withContext(Dispatchers.IO) {
      val copy = File(cacheDir, "t09-write.bin").also { cacheDir.mkdirs() }
      val changed = File(cacheDir, "t09-write-plus.bin")
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
        // Il file con un byte in piu' in coda (un JPEG con dati dopo EOI si apre lo stesso): la riga ha `sha`, deve avere `shaPlus`.
        changed.outputStream().use { out -> copy.inputStream().use { it.copyTo(out) }; out.write(0) }
        val shaPlus = Sha256(MessageDigest.getInstance("SHA-256").digest(changed.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) })
        val ref = phone.refOf(row)
        val started = elapsedMs()
        val result = rewriter.replace(ref, changed, shaPlus, sha)
        var code = FieldProbeMath.rewriteCode(result)
        // Riuscita: si rimette com'era (R3: il riscrittore controlla che la riga abbia adesso i byte con il byte in piu').
        if (result is RewriteResult.Replaced && rewriter.replace(ref, copy, sha, shaPlus) !is RewriteResult.Replaced) code = WRITE_CODE_NOT_RESTORED
        store.set(FieldProbeKey.T09_WRITE_MS, elapsedMs() - started)
        store.set(FieldProbeKey.T09_WRITE_CODE, code)
      } catch (_: IOException) {
        store.set(FieldProbeKey.T09_WRITE_CODE, WRITE_CODE_UNREADABLE)
      } finally {
        copy.delete()
        changed.delete()
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

    /** Il codice "riscritta con un byte in piu' ma NON rimessa com'era": 8 (il 7 e' "la riga e' cambiata prima di scrivere", `FieldProbeMath.rewriteCode`). */
    const val WRITE_CODE_NOT_RESTORED: Long = 8L

    private const val SESSION = "t09"
    private const val ROW = "row="
    private const val GENERATION = "generation="
    private const val TRASHED = "trashed="
    private const val WRITE_ID = "write_id="
    private const val WRITE_GENERATION = "write_generation="

    private const val BUFFER = 64 * 1024
    private const val MILLIS_PER_SECOND = 1000L
  }
}
