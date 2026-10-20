package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.noop.NoPhoneMediaRewriter
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.write.MediaStorePhoneMediaRewriter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 5B (P0-B): le prove guidate T-09 e T-16 su un MediaStore finto che parla come quello vero (selezioni eseguite davvero, cestino, rinumerazioni,
 * scritture). Provano la **recinzione** (solo `DCIM/mcg-prova/` e `Pictures/mcg-prova/`, nessuna riga fuori e nessuna richiesta di sistema su righe
 * fuori), che ogni passo scriva soli numeri, e la logica di confronto: cio' che il telefono vero dira' a F-prove lo si legge con gli stessi calcoli.
 * Le parti che solo il telefono puo' dire (cosa fa Samsung Gallery, cosa legge Gmail) non si provano qui: sono le prove.
 */
@RunWith(AndroidJUnit4::class)
class FieldProbeFlowTest {

  @get:Rule val temp = TemporaryFolder()

  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private lateinit var provider: ProbeMediaProvider
  private lateinit var store: FieldProbeStore
  private lateinit var phone: FieldProbePhone
  private lateinit var intents: FakePhoneMediaIntents

  private val now = 1_760_000_000L
  private val thirtyDays = 30 * 86_400L

  @Before
  fun setUp() {
    provider = ProbeMediaProvider.register()
    store = FieldProbeStore.forTest(File(temp.root, "store"))
    phone = FieldProbePhone(context)
    intents = FakePhoneMediaIntents()
    for (id in 1L..4L) provider.insertRow(id, "DCIM/mcg-prova/")
    provider.insertRow(5, "Pictures/mcg-prova/", mime = "video/mp4", content = ByteArray(64))
    // Le righe che le prove non devono mai toccare: una foto vera, una sottocartella, una riga in scrittura.
    provider.insertRow(6, "DCIM/Camera/")
    provider.insertRow(7, "DCIM/mcg-prova/sub/")
    provider.insertRow(8, "DCIM/mcg-prova/", pending = true)
  }

  private fun t09(rewriter: dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaRewriter = NoPhoneMediaRewriter, deleted: List<Long>? = emptyList()) = FieldProbeT09(
    phone = phone,
    store = store,
    intents = intents,
    rewriter = rewriter,
    cacheDir = File(temp.root, "cache"),
    deletedSince = { deleted },
    nowSeconds = { now },
    elapsedMs = { 10L },
  )

  @Test
  fun leProveLeggonoSoloLeDueCartelleDiProva() {
    val rows = phone.rows()
    assertThat(rows.map { it.id }).containsExactly(1L, 2L, 3L, 4L, 5L)
    // La selezione eseguita dal MediaStore e' proprio quella delle due cartelle (non un filtro fatto dopo).
    val (selection, args) = provider.selections.last()
    assertThat(selection).contains("relative_path = ?")
    assertThat(args).containsExactly("DCIM/mcg-prova/", "Pictures/mcg-prova/").inOrder()
    assertThat(FieldProbePhone.FOLDERS).containsExactly("DCIM/mcg-prova/", "Pictures/mcg-prova/")
  }

  @Test
  fun l_ambienteContaLeRigheEScriveSoloNumeri() {
    val rows = t09().environment()
    assertThat(rows).isEqualTo(5)
    assertThat(store.get(FieldProbeKey.ENV_SDK)).isEqualTo(Build.VERSION.SDK_INT.toLong())
    assertThat(store.get(FieldProbeKey.ENV_ROWS)).isEqualTo(5L)
    assertThat(store.get(FieldProbeKey.ENV_ROWS_IMAGES)).isEqualTo(4L)
    assertThat(store.get(FieldProbeKey.ENV_ROWS_VIDEOS)).isEqualTo(1L)
    assertThat(FieldProbeReport.isNumbersOnly(store.render())).isTrue()
  }

  @Test
  fun ilConfrontoSegueSamsungCheCestinaERipristinaConUnAltroId() = runBlocking<Unit> {
    val probe = t09(deleted = listOf(2L, 6L))
    // Senza riferimento non si confronta.
    assertThat(probe.compare()).isFalse()
    assertThat(probe.takeBaseline()).isEqualTo(5)
    assertThat(store.get(FieldProbeKey.T09_BASE_ROWS)).isEqualTo(5L)

    // Samsung cestina la 2: la riga sparisce dal MediaStore (non c'e' IS_TRASHED).
    provider.vanishRow(2)
    assertThat(probe.compare()).isTrue()
    assertThat(store.get(FieldProbeKey.T09_DIFF_MISSING, 1)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T09_DIFF_NEW, 1)).isEqualTo(0L)
    assertThat(store.get(FieldProbeKey.T09_DIFF_SAME_ID_PRESENT, 1)).isEqualTo(4L)
    assertThat(store.get(FieldProbeKey.T09_DIFF_GENERATION_DELTA, 1)).isGreaterThan(0L)
    // Fra le voci di deleted_files contano solo quelle del riferimento: la 6 (fuori recinzione) non conta.
    assertThat(store.get(FieldProbeKey.T09_DIFF_DELETED_FILES, 1)).isEqualTo(1L)

    // Ripristina dalla Galleria: stessi byte, stessa data, ma un _ID nuovo.
    provider.returnWithNewId(oldId = 2, newId = 22, path = "DCIM/mcg-prova/", content = ByteArray(16) { (it + 2).toByte() }, modifiedSec = 1_760_000_000L)
    probe.compare()
    assertThat(store.get(FieldProbeKey.T09_DIFF_MISSING, 2)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T09_DIFF_NEW, 2)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T09_DIFF_OTHER_ID_BACK, 2)).isEqualTo(1L)
    assertThat(FieldProbeReport.isNumbersOnly(store.render())).isTrue()
  }

  @Test
  fun ilCestinoDiSistemaSiMisuraInGiorniEIlRipristinoRimetteLeRighe() = runBlocking<Unit> {
    val probe = t09()
    probe.environment()
    // La richiesta e' sulle prime due righe di prova, mai fuori recinzione (la 6 ha un _ID e un tipo qualunque).
    probe.prepareTrash(2, many = false)
    val (refs, trash) = intents.trashRequests.single()
    assertThat(refs.map { it.mediaStoreId }).containsExactly(1L, 2L).inOrder()
    assertThat(trash).isTrue()
    assertThat(store.get(FieldProbeKey.T09_TRASH_REQUESTED)).isEqualTo(2L)

    // L'utente accetta nel dialogo di sistema: le due righe diventano cestinate, con la scadenza a 30 giorni.
    provider.trashRow(1, now + thirtyDays)
    provider.trashRow(2, now + thirtyDays)
    probe.finishTrash(Activity.RESULT_OK, many = false)
    assertThat(store.get(FieldProbeKey.T09_TRASH_RESULT)).isEqualTo(Activity.RESULT_OK.toLong())
    assertThat(store.get(FieldProbeKey.T09_TRASH_IS_TRASHED)).isEqualTo(2L)
    assertThat(store.get(FieldProbeKey.T09_TRASH_VISIBLE_DEFAULT)).isEqualTo(0L)
    assertThat(store.get(FieldProbeKey.T09_TRASH_EXPIRES_DAYS_MIN)).isEqualTo(30L)
    assertThat(store.get(FieldProbeKey.T09_TRASH_EXPIRES_DAYS_MAX)).isEqualTo(30L)
    probe.answerSamsungTrash(shown = true)
    assertThat(store.get(FieldProbeKey.T09_TRASH_IN_SAMSUNG)).isEqualTo(1L)

    // Ripristina: la richiesta e' di quelle due righe, con trash = false.
    probe.prepareRestore()
    val (restoreRefs, restoreTrash) = intents.trashRequests.last()
    assertThat(restoreRefs.map { it.mediaStoreId }).containsExactly(1L, 2L)
    assertThat(restoreTrash).isFalse()
    provider.restoreRow(1)
    provider.restoreRow(2)
    probe.finishRestore(Activity.RESULT_OK)
    assertThat(store.get(FieldProbeKey.T09_UNTRASH_IS_TRASHED)).isEqualTo(0L)
    assertThat(store.get(FieldProbeKey.T09_UNTRASH_SAME_IDS)).isEqualTo(2L)

    // La richiesta "fino a 500": tutte le righe di prova, nessuna di fuori.
    probe.prepareTrash(0, many = true)
    val many = intents.trashRequests.last().first
    assertThat(many.map { it.mediaStoreId }).containsExactly(1L, 2L, 3L, 4L, 5L)
    assertThat(store.get(FieldProbeKey.T09_MANY_REQUESTED)).isEqualTo(5L)

    // Nessuna richiesta di sistema ha mai nominato una riga fuori dalle due cartelle.
    val everyId = intents.trashRequests.flatMap { it.first }.map { it.mediaStoreId }.toSet()
    assertThat(everyId - setOf(1L, 2L, 3L, 4L, 5L)).isEmpty()
    assertThat(FieldProbeReport.isNumbersOnly(store.render())).isTrue()
  }

  /**
   * D-3 delle prove sul campo: su One UI il cestino dell'app manda le foto nel Cestino di Galleria. Prima "Ripristina quelle foto" diceva "nessuna
   * foto adatta in mcg-prova" senza misurare niente. Adesso la prova misura dove sono finite le righe (stesso `_ID` sotto `Android/.Trash/`, o
   * sparite), dice che si ripristinano da Galleria → Cestino senza chiedere nessun dialogo, e misura il ritorno con lo stesso `_ID`.
   */
  @Test
  fun d3_conIlCestinoDiGalleriaIlRipristinoLoDiceELoMisura() = runBlocking<Unit> {
    val probe = t09()
    probe.prepareTrash(3, many = false)
    assertThat(intents.trashRequests.single().first.map { it.mediaStoreId }).containsExactly(1L, 2L, 3L).inOrder()

    // One UI: due righe nel Cestino di Galleria con lo stesso _ID (non media, non cestinate), la terza non si vede piu'.
    provider.moveRow(1, "Android/.Trash/com.sec.android.gallery3d/", mediaType = 0)
    provider.moveRow(2, "Android/.Trash/com.sec.android.gallery3d/", mediaType = 0)
    provider.vanishRow(3)
    probe.finishTrash(Activity.RESULT_OK, many = false)
    assertThat(store.get(FieldProbeKey.T09_TRASH_IS_TRASHED)).isEqualTo(0L)
    assertThat(store.get(FieldProbeKey.T09_TRASH_VISIBLE_DEFAULT)).isEqualTo(0L)
    assertThat(store.get(FieldProbeKey.T09_TRASH_OUT_OF_FOLDERS)).isEqualTo(3L)
    assertThat(store.get(FieldProbeKey.T09_TRASH_IN_GALLERY_TRASH)).isEqualTo(2L)
    assertThat(store.get(FieldProbeKey.T09_TRASH_ELSEWHERE_IS_TRASHED)).isEqualTo(0L)
    assertThat(store.get(FieldProbeKey.T09_TRASH_GONE)).isEqualTo(1L)

    // "Ripristina": nessun dialogo (l'app non le puo' ripristinare), la prova lo dice e lo misura.
    val inGallery = probe.prepareRestore()
    assertThat(inGallery).isEqualTo(FieldProbeT09.RestoreStart.InGalleryTrash(3))
    assertThat(intents.trashRequests).hasSize(1)
    assertThat(store.get(FieldProbeKey.T09_UNTRASH_IN_GALLERY)).isEqualTo(3L)
    assertThat(store.get(FieldProbeKey.T09_UNTRASH_BACK_SAME_ID)).isEqualTo(0L)

    // Galleria → Cestino → Ripristina: le righe tornano al loro posto con lo stesso _ID.
    provider.moveRow(1, "DCIM/mcg-prova/", mediaType = 1)
    provider.moveRow(2, "DCIM/mcg-prova/", mediaType = 1)
    val back = probe.prepareRestore()
    assertThat(back).isEqualTo(FieldProbeT09.RestoreStart.InGalleryTrash(1))
    assertThat(store.get(FieldProbeKey.T09_UNTRASH_BACK_SAME_ID)).isEqualTo(2L)
    assertThat(intents.trashRequests).hasSize(1)
    assertThat(FieldProbeReport.isNumbersOnly(store.render())).isTrue()
  }

  /** D-3: senza nessun cestino fatto dalla prova "Ripristina" non ha niente da fare; con tutte le righe tornate lo dice. */
  @Test
  fun d3_ilRipristinoSenzaCestinoNonFaNienteEConLeRigheTornateLoDice() = runBlocking<Unit> {
    val probe = t09()
    assertThat(probe.prepareRestore()).isEqualTo(FieldProbeT09.RestoreStart.Nothing)
    probe.prepareTrash(1, many = false)
    provider.moveRow(1, "Android/.Trash/com.sec.android.gallery3d/", mediaType = 0)
    probe.finishTrash(Activity.RESULT_OK, many = false)
    provider.moveRow(1, "DCIM/mcg-prova/", mediaType = 1)
    assertThat(probe.prepareRestore()).isEqualTo(FieldProbeT09.RestoreStart.AlreadyBack(1))
    assertThat(intents.trashRequests).hasSize(1)
  }

  @Test
  fun ilPermessoDiScritturaRiscriveLaFotoConISuoiStessiByte() = runBlocking<Unit> {
    val rewriter = MediaStorePhoneMediaRewriter(context, undoDir = File(temp.root, "undo"))
    val probe = t09(rewriter)
    val before = provider.contentOf(1)!!.toList()

    probe.prepareWrite()
    val asked = intents.writeRequests.single()
    assertThat(asked.map { it.mediaStoreId }).containsExactly(1L)
    // VE-2: il dialogo si chiede come lo chiede l'app (riferimento senza MIME); la collezione la sceglie l'adattatore dal tipo della riga, e la
    // riscrittura vera qui sotto apre lo stesso URI.
    assertThat(asked.single().mime).isNull()
    probe.finishWrite(Activity.RESULT_OK)
    assertThat(store.get(FieldProbeKey.T09_WRITE_DIALOG)).isEqualTo(Activity.RESULT_OK.toLong())
    assertThat(store.get(FieldProbeKey.T09_WRITE_CODE)).isEqualTo(0L)
    assertThat(store.get(FieldProbeKey.T09_WRITE_SIZE_BEFORE)).isEqualTo(16L)
    assertThat(store.get(FieldProbeKey.T09_WRITE_SIZE_AFTER)).isEqualTo(16L)
    assertThat(provider.contentOf(1)!!.toList()).isEqualTo(before)

    // Il dialogo rifiutato non scrive niente; senza il permesso di sistema la riscrittura dice "nessun permesso" e il file resta com'e'.
    val declined = t09(rewriter)
    declined.prepareWrite()
    declined.finishWrite(Activity.RESULT_CANCELED)
    assertThat(store.get(FieldProbeKey.T09_WRITE_DIALOG)).isEqualTo(Activity.RESULT_CANCELED.toLong())
    provider.allowWrite = false
    val denied = t09(rewriter)
    denied.prepareWrite()
    denied.finishWrite(Activity.RESULT_OK)
    assertThat(store.get(FieldProbeKey.T09_WRITE_CODE)).isEqualTo(1L)
    assertThat(provider.contentOf(1)!!.toList()).isEqualTo(before)
  }

  @Test
  fun laProvaDiViewMisuraLaModificaDiSamsungGallery() {
    val t16 = FieldProbeT16(context, phone, store)
    // Non c'e' ancora un riferimento.
    assertThat(t16.compareView()).isFalse()
    val view = t16.prepareView()!!
    assertThat(view.action).isEqualTo(android.content.Intent.ACTION_VIEW)
    assertThat(view.`package`).isEqualTo(FieldProbeT16.SAMSUNG_GALLERY)
    // L'indirizzo e' quello del MediaStore di una riga di prova, con il permesso di lettura e nessun permesso di scrittura.
    assertThat(view.data.toString()).contains("/images/media/1")
    assertThat(view.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    assertThat(view.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)

    // "Salva": stessa riga, byte e data nuovi; "Salva copia": una riga nuova con la stessa data di scatto.
    provider.overwrite(1, ByteArray(40) { 9 }, modifiedSec = 1_760_000_500L)
    provider.insertRow(40, "DCIM/mcg-prova/", taken = 1_760_000_000_001L, content = ByteArray(40) { 9 })
    provider.setOriginalDocumentId(1, "uuid")
    assertThat(t16.compareView()).isTrue()
    assertThat(store.get(FieldProbeKey.T16_VIEW_SAME_ID, 1)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T16_VIEW_ROW_CHANGED, 1)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T16_VIEW_ROWS_NEW, 1)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T16_VIEW_NEW_SAME_TAKEN, 1)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T16_VIEW_SIZE_DELTA, 1)).isEqualTo(24L)
    assertThat(store.get(FieldProbeKey.T16_COL_ORIGINAL_DOCUMENT_ID, 1)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T16_COL_DOCUMENT_ID, 1)).isEqualTo(1L)
    assertThat(FieldProbeReport.isNumbersOnly(store.render())).isTrue()
  }
}
