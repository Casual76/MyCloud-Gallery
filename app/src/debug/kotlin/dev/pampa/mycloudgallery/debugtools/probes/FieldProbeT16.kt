package dev.pampa.mycloudgallery.debugtools.probes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * SOLO build debug (Fase 5B, P0-B; docs/android.md T-14, T-15, T-16): quanti gestori hanno le altre app per cio' che l'app vuole fare. Sono i numeri
 * della tabella di `docs/android.md` 5.2 (`cmd package query-activities`), letti dall'app con le dichiarazioni `<queries>` del manifest di debug.
 * Il conto comprende anche i gestori nostri (il vero e quello di prova): [FieldProbeKey.T14_OWN_HANDLER] dice quanti.
 */
internal object FieldProbeHandlers {

  fun measure(context: Context, store: FieldProbeStore) {
    val pm = context.packageManager
    val media = Uri.parse("content://media/external/images/media/1")
    fun count(intent: Intent): List<android.content.pm.ResolveInfo> = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

    val getContent = count(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE))
    val pick = count(Intent(Intent.ACTION_PICK).setType("image/*"))
    val send = count(Intent(Intent.ACTION_SEND).setType("image/jpeg"))
    val sendMultiple = count(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*"))
    val edit = count(Intent(Intent.ACTION_EDIT).setDataAndType(media, "image/jpeg"))
    val view = count(Intent(Intent.ACTION_VIEW).setDataAndType(media, "image/jpeg"))
    store.set(FieldProbeKey.T14_HANDLERS_GET_CONTENT, getContent.size.toLong())
    store.set(FieldProbeKey.T14_HANDLERS_PICK, pick.size.toLong())
    store.set(FieldProbeKey.T14_OWN_HANDLER, (getContent + pick).count { it.activityInfo.packageName == context.packageName }.toLong())
    store.set(FieldProbeKey.T15_HANDLERS_SEND, send.size.toLong())
    store.set(FieldProbeKey.T15_HANDLERS_SEND_MULTIPLE, sendMultiple.size.toLong())
    store.set(FieldProbeKey.T15_OWN_HANDLER, (send + sendMultiple).count { it.activityInfo.packageName == context.packageName }.toLong())
    store.set(FieldProbeKey.T16_HANDLERS_EDIT, edit.size.toLong())
    store.set(FieldProbeKey.T16_HANDLERS_VIEW, view.size.toLong())
  }
}

/**
 * SOLO build debug (Fase 5B, P0-B; docs/android.md T-16): la prova guidata di **"Modifica con..."**, sulle sole cartelle `mcg-prova`.
 *  - a) `ACTION_VIEW` verso Samsung Gallery (pacchetto esplicito, come D25) su una foto di prova: l'utente modifica e salva ("Salva", "Salva copia",
 *    "Ripristina originale"), torna qui e [compareView] scrive un blocco indicizzato di numeri: la stessa riga e' cambiata? c'e' una riga nuova nella
 *    cartella con la stessa data di scatto (E-X3)? di quanto e' cambiata la dimensione e la generazione? e quante righe hanno `ORIGINAL_DOCUMENT_ID`,
 *    `DOCUMENT_ID`, `INSTANCE_ID` (c)?
 *  - b) `ACTION_EDIT` con una copia in `cacheDir/edit/` servita dal `FileProvider` delle integrazioni in lettura **e scrittura** (Google Foto,
 *    OneDrive): [prepareEdit] la prepara, [finishEdit] confronta byte e dimensione e conta i file nuovi comparsi accanto, poi revoca i permessi e
 *    toglie la copia.
 * Niente si scrive su un media del telefono: l'editor lavora su una copia in cache.
 */
internal class FieldProbeT16(
  private val context: Context,
  private val phone: FieldProbePhone,
  private val store: FieldProbeStore,
) {

  private var baseline: List<ProbeRow> = emptyList()
  private var viewed: ProbeRow? = null
  private var editFile: File? = null
  private var editUri: Uri? = null
  private var editShaBefore: String? = null
  private var editSizeBefore: Long = 0
  private var editSiblingsBefore: Set<String> = emptySet()

  init {
    restore()
  }

  /**
   * LA-7: il riferimento di "Apri in Samsung Gallery" e la copia in modifica stanno anche su file ([FieldProbeStore.saveSession]). Le due prove
   * mandano in un'altra app e al ritorno la schermata puo' essere stata ricreata: "Confronta" diceva "Prima la fotografia di riferimento", e
   * `finishEdit` non trovava piu' la copia (con i permessi ancora aperti sull'indirizzo).
   */
  private fun restore() {
    store.loadSession(SESSION_VIEW)?.let { lines ->
      baseline = lines.filter { it.startsWith(ROW) }.mapNotNull { ProbeRowMarks.decode(it.removePrefix(ROW)) }
      viewed = lines.firstNotNullOfOrNull { if (it.startsWith(VIEWED)) ProbeRowMarks.decode(it.removePrefix(VIEWED)) else null }
    }
    store.loadSession(SESSION_EDIT)?.let { lines ->
      val name = lines.firstNotNullOfOrNull { if (it.startsWith(EDIT_FILE)) it.removePrefix(EDIT_FILE) else null } ?: return@let
      // Solo il nome di un file di prova di `cacheDir/edit/`: mai un percorso.
      if (!EDIT_NAME.matches(name)) return@let
      val file = File(File(context.cacheDir, "edit"), name)
      editFile = file
      editUri = runCatching { FileProvider.getUriForFile(context, context.packageName + ".integrations.files", file) }.getOrNull()
      editShaBefore = lines.firstNotNullOfOrNull { if (it.startsWith(EDIT_SHA)) it.removePrefix(EDIT_SHA) else null }
      editSizeBefore = lines.firstNotNullOfOrNull { if (it.startsWith(EDIT_SIZE)) it.removePrefix(EDIT_SIZE).toLongOrNull() else null } ?: 0L
      editSiblingsBefore = lines.filter { it.startsWith(EDIT_SIBLING) }.map { it.removePrefix(EDIT_SIBLING) }.toSet()
    }
  }

  private fun persistView() {
    val lines = ArrayList<String>()
    baseline.forEach { lines += ROW + ProbeRowMarks.encode(it) }
    viewed?.let { lines += VIEWED + ProbeRowMarks.encode(it) }
    store.saveSession(SESSION_VIEW, lines)
  }

  private fun persistEdit() {
    val file = editFile
    if (file == null) {
      store.saveSession(SESSION_EDIT, emptyList())
      return
    }
    val lines = ArrayList<String>()
    lines += EDIT_FILE + file.name
    editShaBefore?.let { lines += EDIT_SHA + it }
    lines += EDIT_SIZE + editSizeBefore
    editSiblingsBefore.forEach { lines += EDIT_SIBLING + it }
    store.saveSession(SESSION_EDIT, lines)
  }

  // --- a) c) ACTION_VIEW verso Samsung Gallery ----------------------------------------------------------------------------------------

  /** L'intento per aprire la prima foto di prova in Samsung Gallery, con il riferimento preso prima. Null se non ci sono foto. */
  fun prepareView(): Intent? {
    baseline = phone.rows()
    val row = FieldProbeMath.pick(baseline, 1, photosOnly = true).firstOrNull() ?: return null
    viewed = row
    persistView()
    return Intent(Intent.ACTION_VIEW)
      .setDataAndType(phone.uriOf(row), row.mime ?: "image/jpeg")
      .setPackage(SAMSUNG_GALLERY)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }

  fun markViewLaunched(launched: Boolean) {
    store.set(FieldProbeKey.T16_VIEW_LAUNCHED, if (launched) 1 else 0)
  }

  /** Confronta le righe di adesso con il riferimento di [prepareView] e scrive un blocco indicizzato. Falso se non c'e' un riferimento. */
  fun compareView(): Boolean {
    val before = viewed ?: return false
    val now = phone.rows()
    val index = store.nextIndex(FieldProbeKey.T16_VIEW_ROW_CHANGED)
    val same = now.firstOrNull { it.id == before.id }
    val baseIds = baseline.mapTo(HashSet()) { it.id }
    val added = now.filter { it.id !in baseIds }
    store.set(FieldProbeKey.T16_VIEW_SAME_ID, if (same != null) 1 else 0, index)
    store.set(FieldProbeKey.T16_VIEW_ROW_CHANGED, if (same != null && same.fingerprint != before.fingerprint) 1 else 0, index)
    store.set(FieldProbeKey.T16_VIEW_ROWS_NEW, added.size.toLong(), index)
    store.set(
      FieldProbeKey.T16_VIEW_NEW_SAME_TAKEN,
      added.count { it.dateTakenMs != null && it.dateTakenMs == before.dateTakenMs }.toLong(),
      index,
    )
    store.set(FieldProbeKey.T16_VIEW_SIZE_DELTA, (same?.size ?: before.size) - before.size, index)
    store.set(FieldProbeKey.T16_VIEW_GENERATION_DELTA, (same?.generationModified ?: 0L) - (before.generationModified ?: 0L), index)
    store.set(FieldProbeKey.T16_COL_ORIGINAL_DOCUMENT_ID, FieldProbeMath.countWith(now) { it.originalDocumentId }.toLong(), index)
    store.set(FieldProbeKey.T16_COL_DOCUMENT_ID, FieldProbeMath.countWith(now) { it.documentId }.toLong(), index)
    store.set(FieldProbeKey.T16_COL_INSTANCE_ID, FieldProbeMath.countWith(now) { it.instanceId }.toLong(), index)
    return true
  }

  // --- b) ACTION_EDIT con il FileProvider in lettura e scrittura ---------------------------------------------------------------------------

  /** Copia la prima foto di prova in `cacheDir/edit/` e prepara il selettore delle app di modifica. Null se non ci sono foto o la copia non riesce. */
  fun prepareEdit(): Intent? {
    val row = FieldProbeMath.pick(phone.rows(), 1, photosOnly = true).firstOrNull() ?: return null
    val dir = File(context.cacheDir, "edit").also { it.mkdirs() }
    val file = File(dir, "probe-${row.id}.jpg")
    try {
      phone.open(row).use { input -> file.outputStream().use { input.copyTo(it) } }
    } catch (_: IOException) {
      return null
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".integrations.files", file)
    editFile = file
    editUri = uri
    editShaBefore = sha256(file)
    editSizeBefore = file.length()
    editSiblingsBefore = dir.list()?.toSet().orEmpty()
    persistEdit()
    val edit = Intent(Intent.ACTION_EDIT)
      .setDataAndType(uri, row.mime ?: "image/jpeg")
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    edit.clipData = ClipData.newRawUri(null, uri)
    store.set(FieldProbeKey.T16_EDIT_LAUNCHED, 1)
    return Intent.createChooser(edit, null)
  }

  /** Dopo l'editor: com'e' andata ([resultCode] di `Activity`, c'e' un risultato?) e se i byte della copia sono cambiati. Poi revoca i permessi. */
  fun finishEdit(resultCode: Int, data: Intent?) {
    val file = editFile ?: return
    store.set(FieldProbeKey.T16_EDIT_RESULT_CODE, resultCode.toLong())
    store.set(FieldProbeKey.T16_EDIT_RESULT_HAS_DATA, if (data?.data != null || data?.clipData != null) 1 else 0)
    store.set(FieldProbeKey.T16_EDIT_SIZE_BEFORE, editSizeBefore)
    store.set(FieldProbeKey.T16_EDIT_SIZE_AFTER, if (file.isFile) file.length() else -1)
    store.set(FieldProbeKey.T16_EDIT_BYTES_CHANGED, if (file.isFile && sha256(file) != editShaBefore) 1 else 0)
    val now = file.parentFile?.list()?.toSet().orEmpty()
    store.set(FieldProbeKey.T16_EDIT_NEW_FILES, (now - editSiblingsBefore).size.toLong())
    editUri?.let { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    file.parentFile?.listFiles()?.filter { it.name.startsWith("probe-") }?.forEach { it.delete() }
    editFile = null
    editUri = null
    persistEdit()
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(BUFFER)
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  companion object {
    /** Il pacchetto di Samsung Gallery (`android.md` 5.2): `ACTION_VIEW` con pacchetto esplicito (D25). */
    const val SAMSUNG_GALLERY: String = "com.sec.android.gallery3d"

    private const val BUFFER = 64 * 1024
    private const val SESSION_VIEW = "t16_view"
    private const val SESSION_EDIT = "t16_edit"
    private const val ROW = "row="
    private const val VIEWED = "viewed="
    private const val EDIT_FILE = "edit_file="
    private const val EDIT_SHA = "edit_sha="
    private const val EDIT_SIZE = "edit_size="
    private const val EDIT_SIBLING = "edit_sibling="

    /** Il nome di una copia di modifica di prova (`probe-<_ID>.jpg`): il solo nome che si rilegge dal file di stato. */
    private val EDIT_NAME = Regex("^probe-[0-9]{1,19}\\.jpg$")
  }
}

/**
 * SOLO build debug (Fase 5B, P0-B; docs/android.md T-15): il **permesso di lettura** delle condivisioni ricevute dura oltre l'activity che le ha
 * ricevute? e dopo la morte del processo? [retest] riprova ad aprire ogni indirizzo che [FieldProbeShareActivity] ha registrato e scrive quanti si
 * aprono ancora e quanti no, l'eta' della piu' vecchia e se il processo e' ripartito dalla ricezione (il numero del processo e' cambiato).
 */
internal object FieldProbeT15 {

  fun retest(context: Context, store: FieldProbeStore) {
    val shares = store.shares()
    val now = android.os.SystemClock.elapsedRealtime()
    var ok = 0L
    var denied = 0L
    var oldestMs = 0L
    var restarted = false
    for (share in shares) {
      oldestMs = maxOf(oldestMs, now - share.receivedAtElapsedMs)
      if (share.processMarker != store.processMarker) restarted = true
      val opened = try {
        context.contentResolver.openInputStream(Uri.parse(share.uri))?.use { it.read() >= -1 } ?: false
      } catch (_: Exception) {
        false
      }
      if (opened) ok++ else denied++
    }
    // LA-8: ogni riprova ha il suo blocco indicizzato (`t15.reopen_ok.2`...): la seconda non cancella la prima, e si vede cosa cambia col tempo.
    val index = store.nextIndex(FieldProbeKey.T15_REOPEN_OK)
    store.set(FieldProbeKey.T15_REOPEN_OK, ok, index)
    store.set(FieldProbeKey.T15_REOPEN_DENIED, denied, index)
    store.set(FieldProbeKey.T15_REOPEN_AGE_S, oldestMs / MILLIS_PER_SECOND, index)
    store.set(FieldProbeKey.T15_REOPEN_AFTER_RESTART, if (restarted) 1 else 0, index)
  }

  private const val MILLIS_PER_SECOND = 1000L
}
