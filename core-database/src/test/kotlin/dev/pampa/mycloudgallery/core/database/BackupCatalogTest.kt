package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.Db.DAY
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.domain.WorkRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le operazioni del catalogo per il backup (Fase 5A, P1; `docs/fase5-progetto.md` §1.2, §2.1-§2.3; `docs/modello-dati.md` §12): i
 * candidati, i conteggi, la deduplica, le verifiche, il guardiano, i lotti di copie, il problema di un media.
 */
@RunWith(AndroidJUnit4::class)
class BackupCatalogTest : CatalogTestBase() {

  private val camera = setOf<CatalogSource>(CatalogSource.Camera)

  @Before
  fun phoneCursor() = runBlocking {
    catalog.saveMediaStoreCursor(MediaStoreCursor("external_primary", msVersion = Db.STORE_V1))
  }

  private suspend fun addPhone(
    n: Int,
    path: String = "DCIM/Camera/",
    source: MediaSource = MediaSource.CAMERA,
    kind: MediaKind = MediaKind.PHOTO,
    fingerprint: Fingerprint = smallFull(n),
    capture: Long = T0 - n * 1_000L,
  ): MediaId = catalog.record(phoneObservation("IMG_$n.jpg", 100L + n, fingerprint, capture, path, source, kind)).mediaId!!

  /** Un file nell'area privata con gli stessi byte: lo stesso media, ora con una copia nell'area privata non ancora verificata. */
  private suspend fun addPrivateCopy(n: Int, path: String = "Backup/S25/2025/10/IMG_$n.jpg", share: String = "alessio"): LocationId =
    catalog.recordAll(listOf(nasObservation(path, smallFull(n), share)), seenAt = T0).single().locationId!!

  private suspend fun flags(id: MediaId) = db.mediaDao().get(id.value)!!.cellFlags

  private suspend fun candidates(
    sources: Set<CatalogSource> = camera,
    kind: CatalogBackupKind = CatalogBackupKind.PHOTOS,
    after: CatalogCandidateCursor? = null,
    limit: Int = 100,
  ) = catalog.backupCandidates(sources, kind, after, limit)

  // --- Candidati --------------------------------------------------------------------------------------------------------------

  @Test
  fun iCandidatiSonoMediaPropriSulTelefonoNonAlSicuroDalPiuRecente() = runBlocking<Unit> {
    val photos = (1..5).map { addPhone(it) }
    val video = addPhone(6, kind = MediaKind.VIDEO, capture = T0 - 100)
    val screenshot = addPhone(7, path = "Pictures/Screenshots/", source = MediaSource.SCREENSHOT, capture = T0 - 200)
    val telegram = addPhone(8, path = "Pictures/Telegram/", source = MediaSource.OTHER, capture = T0 - 300)
    // Non sono candidati: solo sul NAS (area privata) e gia' al sicuro.
    val nasOnly = catalog.recordAll(listOf(nasObservation("Vecchi/A.jpg", smallFull(50))), seenAt = T0).single().mediaId!!
    val safe = addPhone(9)
    val copy = addPrivateCopy(9)
    assertThat(catalog.markVerified(copy, smallFull(9).fullHash!!, T0)).isTrue()

    val cameraPhotos = candidates()
    assertThat(cameraPhotos.map { it.mediaId }).containsExactly(photos[0], photos[1], photos[2], photos[3], photos[4]).inOrder()
    assertThat(cameraPhotos.map { it.sortTime }).isInOrder(Comparator.reverseOrder<Long>())
    // I video sono un altro giro: quello della fotocamera ha il suo video, le foto non lo vedono.
    assertThat(candidates(kind = CatalogBackupKind.VIDEOS).map { it.mediaId }).containsExactly(video)
    assertThat(candidates(setOf(CatalogSource.Camera, CatalogSource.Screenshots), CatalogBackupKind.VIDEOS).map { it.mediaId })
      .containsExactly(video)
    assertThat(candidates(setOf(CatalogSource.Screenshots)).single().let { it.mediaId to it.source }).isEqualTo(screenshot to CatalogSource.Screenshots)
    val folder = CatalogSource.Folder("external_primary", "Pictures/Telegram/")
    assertThat(candidates(setOf(folder)).single().let { it.mediaId to it.source }).isEqualTo(telegram to folder)
    // Una cartella con un altro percorso o un altro volume non e' la stessa.
    assertThat(candidates(setOf(CatalogSource.Folder("external_primary", "Pictures/telegram/")))).isEmpty()
    assertThat(candidates(setOf(CatalogSource.Folder("1a2b-3c4d", "Pictures/Telegram/")))).isEmpty()
    assertThat(candidates(emptySet())).isEmpty()
    // Tutto insieme, in un solo ordine.
    val everything = candidates(setOf(CatalogSource.Camera, CatalogSource.Screenshots, folder), CatalogBackupKind.PHOTOS)
    assertThat(everything.map { it.mediaId })
      .containsExactly(screenshot, telegram, photos[0], photos[1], photos[2], photos[3], photos[4]).inOrder()
    assertThat(everything.map { it.mediaId }).containsNoneOf(nasOnly, safe, video)

    // Il contenuto di un candidato: la versione, la riga del telefono, il timbro, il nome e il giorno.
    val first = cameraPhotos.first()
    assertThat(first.versionId).isEqualTo(catalog.versionOf(photos[0]))
    assertThat(first.phone).isEqualTo(CatalogPhonePlace("external_primary", 101, Db.STORE_V1))
    assertThat(first.stamp).isEqualTo(FileStamp(2_000, T0, 7))
    assertThat(first.size).isEqualTo(2_000)
    assertThat(first.fileName).isEqualTo("IMG_1.jpg")
    assertThat(first.original).isTrue()
    assertThat(first.quickHash).isEqualTo(smallFull(1).quickHash)
    assertThat(first.localDay).isNotNull()
    // META non ha ancora letto la data: e' una stima (nome o data del file).
    assertThat(first.dateEstimated).isTrue()
  }

  /**
   * Seguito di O-2 (prove sul campo): nei conteggi delle righe della fotocamera e degli screenshot (pagina Backup) un media che ha una posizione
   * del telefono in una cartella scelta non conta: sta nella riga della cartella, come nel piano. Prima uno screenshot in `mcg-prova` contava
   * due volte. Senza cartelle da escludere i conteggi sono quelli di sempre.
   */
  @Test
  fun iConteggiFuoriDalleCartelleScelteNonContanoDueVolteUnoScreenshotDellaCartella() = runBlocking<Unit> {
    val folder = CatalogSource.Folder("external_primary", "DCIM/mcg-prova/")
    addPhone(1, path = "DCIM/mcg-prova/", source = MediaSource.SCREENSHOT)
    addPhone(2, path = "Pictures/Screenshots/", source = MediaSource.SCREENSHOT)
    addPhone(3, path = "DCIM/mcg-prova/", source = MediaSource.OTHER)
    addPhone(4, path = "DCIM/Camera/", source = MediaSource.CAMERA)
    val screenshots = setOf<CatalogSource>(CatalogSource.Screenshots)
    catalog.markBackupProblem(addPhone(5, path = "DCIM/mcg-prova/", source = MediaSource.SCREENSHOT), "verify_failed")

    // Senza esclusioni: tutti gli screenshot, anche quelli della cartella.
    assertThat(catalog.safetyCounts(screenshots).noNasCopy).isEqualTo(3)
    assertThat(catalog.safetyCounts(screenshots).problems).containsExactly("verify_failed", 1)
    // Fuori dalla cartella scelta: solo lo screenshot che sta altrove, e nessun problema della cartella.
    val outside = catalog.safetyCounts(screenshots, outside = setOf(folder))
    assertThat(outside.noNasCopy).isEqualTo(1)
    assertThat(outside.problems).isEmpty()
    // La riga della cartella li ha tutti; la fotocamera non cambia; una cartella che non c'entra non toglie niente.
    assertThat(catalog.safetyCounts(setOf(folder)).noNasCopy).isEqualTo(3)
    assertThat(catalog.safetyCounts(setOf(CatalogSource.Camera), outside = setOf(folder)).noNasCopy).isEqualTo(1)
    assertThat(catalog.safetyCounts(screenshots, outside = setOf(CatalogSource.Folder("external_primary", "Pictures/Telegram/"))).noNasCopy).isEqualTo(3)
  }

  /**
   * O-2 (prove sul campo): uno "Screenshot_….png" dentro una cartella scelta dall'utente si salva con quella cartella, anche con la sorgente
   * Screenshot accesa: prima vinceva il tipo e finiva in `Screenshot/` sul NAS. Fuori dalle cartelle scelte gli screenshot restano della loro sorgente.
   */
  @Test
  fun unoScreenshotInUnaCartellaSceltaSiSalvaConLaCartella() = runBlocking<Unit> {
    val folder = CatalogSource.Folder("external_primary", "DCIM/mcg-prova/")
    val inFolder = addPhone(1, path = "DCIM/mcg-prova/", source = MediaSource.SCREENSHOT)
    val elsewhere = addPhone(2, path = "Pictures/Screenshots/", source = MediaSource.SCREENSHOT)
    val cameraNamed = addPhone(3, path = "DCIM/mcg-prova/", source = MediaSource.OTHER)

    val both = candidates(setOf(CatalogSource.Screenshots, folder)).associate { it.mediaId to it.source }
    assertThat(both).containsExactly(inFolder, folder, elsewhere, CatalogSource.Screenshots, cameraNamed, folder)
    // Con la sola cartella (la sorgente Screenshot spenta, o tolta dalla recinzione D13) lo screenshot della cartella c'e' lo stesso.
    val onlyFolder = candidates(setOf(folder)).associate { it.mediaId to it.source }
    assertThat(onlyFolder).containsExactly(inFolder, folder, cameraNamed, folder)
    // Con la sola sorgente Screenshot restano tutti e due gli screenshot, ognuno della sorgente Screenshot.
    val onlyScreenshots = candidates(setOf(CatalogSource.Screenshots)).associate { it.mediaId to it.source }
    assertThat(onlyScreenshots).containsExactly(inFolder, CatalogSource.Screenshots, elsewhere, CatalogSource.Screenshots)
  }

  @Test
  fun lePagineDeiCandidatiRipartonoDalCursoreSenzaSaltiNeRipetizioni() = runBlocking<Unit> {
    val ids = (1..23).map { addPhone(it) }
    val seen = ArrayList<MediaId>()
    var cursor: CatalogCandidateCursor? = null
    var pages = 0
    while (true) {
      val page = candidates(after = cursor, limit = 5)
      if (page.isEmpty()) break
      pages++
      seen += page.map { it.mediaId }
      cursor = page.last().let { CatalogCandidateCursor(it.sortTime, it.mediaId) }
    }
    assertThat(pages).isEqualTo(5)
    assertThat(seen).containsExactlyElementsIn(ids).inOrder()
    // Media con la stessa data di scatto: l'identita' decide l'ordine e il cursore non salta ne' ripete.
    val sameTime = (30..34).map { addPhone(it, capture = T0 + 500_000) }
    val pageA = candidates(limit = 3)
    val pageB = candidates(after = pageA.last().let { CatalogCandidateCursor(it.sortTime, it.mediaId) }, limit = 3)
    assertThat((pageA + pageB).map { it.mediaId }.filter { it in sameTime }).containsExactlyElementsIn(sameTime)
    assertThat((pageA + pageB).map { it.mediaId }.filter { it in sameTime }).isInOrder(Comparator.reverseOrder<MediaId>())
  }

  @Test
  fun unCandidatoTornaAlSicuroEdEscePoiRientraSeLaCopiaNonTorna() = runBlocking<Unit> {
    val id = addPhone(1)
    val copy = addPrivateCopy(1)
    // Copia privata presente ma non riletta: ancora candidato (la deduplica la rileggera').
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.PRIVATE_COPY_UNVERIFIED)
    assertThat(candidates().map { it.mediaId }).containsExactly(id)

    assertThat(catalog.markVerified(copy, smallFull(1).fullHash!!, T0 + 10)).isTrue()
    assertThat(candidates()).isEmpty()
    assertThat(db.mediaDao().get(id.value)!!.backupTodo).isFalse()

    // Rotazione: la copia non torna, perde "al sicuro" (E-B8) e il telefono ce l'ha ancora: torna candidato.
    assertThat(catalog.markVerified(copy, smallFull(2).fullHash!!, T0 + 20)).isFalse()
    assertThat(candidates().map { it.mediaId }).containsExactly(id)
    assertThat(db.mediaDao().get(id.value)!!.backupTodo).isTrue()
    // Una posizione che non c'e' piu' non e' un errore: falso.
    assertThat(catalog.markVerified(LocationId(99_999), smallFull(1).fullHash!!, T0)).isFalse()
  }

  @Test
  fun unaRigaConUnIdScadutoNonEUnCandidatoFincheLaLetturaDelTelefonoNonLaRilegge() = runBlocking<Unit> {
    val id = addPhone(1)
    val version = catalog.versionOf(id)
    assertThat(catalog.phoneRefOf(id, version)).isEqualTo(CatalogPhonePlace("external_primary", 101, Db.STORE_V1))

    // Cambia la versione del MediaStore (E-P22): gli `_ID` in cache non valgono piu'.
    catalog.saveMediaStoreCursor(MediaStoreCursor("external_primary", msVersion = "store-v2"))
    assertThat(candidates()).isEmpty()
    assertThat(catalog.phoneRefOf(id, version)).isNull()
    // Il media resta da proteggere: i conteggi lo sanno.
    assertThat(catalog.safetyCounts(camera).noNasCopy).isEqualTo(1)

    // La lettura del telefono lo rilegge con la versione nuova.
    catalog.record(phoneObservation("IMG_1.jpg", 777, smallFull(1), T0 - 1_000, storeVersion = "store-v2"))
    assertThat(catalog.phoneRefOf(id, version)).isEqualTo(CatalogPhonePlace("external_primary", 777, "store-v2"))
    assertThat(candidates().single().phone).isEqualTo(CatalogPhonePlace("external_primary", 777, "store-v2"))
    // Un alias vale per il sopravvissuto; una versione di un altro media no.
    val other = addPhone(2)
    assertThat(catalog.phoneRefOf(other, version)).isNull()
    assertThat(catalog.phoneRefOf(MediaId("99999999-9999-4999-8999-999999999999"), version)).isNull()
  }

  @Test
  fun laDataDiUnCandidatoELaLetturaDeiMetadatiSolaSeMetaLHaFatta() = runBlocking<Unit> {
    val id = addPhone(1)
    val version = catalog.versionOf(id)
    assertThat(candidates().single().dateEstimated).isTrue()
    catalog.updateMetadata(version, exifMetadata(T0 - 5 * DAY))
    assertThat(candidates().single().dateEstimated).isFalse()
    // Data stimata (nome del file, data del file): la destinazione la usera' solo se credibile (D6).
    catalog.updateMetadata(
      version,
      exifMetadata(
        T0 - 5 * DAY,
        source = CaptureTimeSource.FILE_NAME,
        confidence = CaptureTimeConfidence.ESTIMATED,
      ),
    )
    assertThat(candidates().single().dateEstimated).isTrue()
  }

  // --- Problema di un media (D15) ---------------------------------------------------------------------------------------------

  @Test
  fun ilProblemaAccendeIlSegnoNellaStessaTransazioneEToglieIlMediaDaiCandidati() = runBlocking<Unit> {
    val id = addPhone(1)
    val other = addPhone(2)
    val changes = Changes(this, catalog)
    val seq = catalog.changeSeq()

    catalog.markBackupProblem(id, "verify_failed")
    // Nella stessa transazione: il bit, un cambiamento annunciato, il candidato tolto.
    assertThat(CellFlags.has(flags(id), CellFlags.BACKUP_PROBLEM)).isTrue()
    assertThat(PresenceBadge.of(flags(id))).isEqualTo(PresenceBadge.PROBLEM)
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
    assertThat(changes.next().ids).containsExactly(id)
    assertThat(candidates().map { it.mediaId }).containsExactly(other)
    assertThat(catalog.mediaSafety(id)).isEqualTo(CatalogMediaSafety(SafetyStatus.NO_NAS_COPY, null, "verify_failed"))
    assertThat(catalog.safetyCounts(camera).problems).containsExactly("verify_failed", 1)
    // Anche l'indice della timeline vede il bit.
    assertThat(catalog.indexRows(listOf(id)).single().cellFlags and CellFlags.BACKUP_PROBLEM).isEqualTo(CellFlags.BACKUP_PROBLEM)

    // Un problema che non marca la cella non accende il bit e non toglie il media dai candidati.
    catalog.markBackupProblem(other, "phone_storage_low", marksCell = false)
    assertThat(CellFlags.has(flags(other), CellFlags.BACKUP_PROBLEM)).isFalse()
    assertThat(candidates().map { it.mediaId }).containsExactly(other)

    // Lo si toglie: il bit si spegne, il media rientra, un cambiamento solo.
    catalog.markBackupProblem(id, null)
    assertThat(CellFlags.has(flags(id), CellFlags.BACKUP_PROBLEM)).isFalse()
    assertThat(candidates().map { it.mediaId }).containsExactly(id, other).inOrder()
    assertThat(catalog.mediaSafety(id)!!.problem).isNull()
    changes.job.cancel()
  }

  @Test
  fun unProblemaNonCopreMaiUnMediaAlSicuroESiToglieQuandoTornaAlSicuro() = runBlocking<Unit> {
    val id = addPhone(1)
    val copy = addPrivateCopy(1)
    catalog.markBackupProblem(id, "verify_failed")
    assertThat(CellFlags.has(flags(id), CellFlags.BACKUP_PROBLEM)).isTrue()

    // La copia viene riletta e torna: il media e' al sicuro e il problema di prima non vale piu'.
    assertThat(catalog.markVerified(copy, smallFull(1).fullHash!!, T0 + 5)).isTrue()
    assertThat(CellFlags.has(flags(id), CellFlags.BACKUP_PROBLEM)).isFalse()
    assertThat(PresenceBadge.of(flags(id))).isEqualTo(PresenceBadge.SAFE)
    assertThat(db.backupProblemDao().get(id.value)).isNull()

    // Segnarlo su un media al sicuro non fa nulla.
    catalog.markBackupProblem(id, "verify_failed")
    assertThat(CellFlags.has(flags(id), CellFlags.BACKUP_PROBLEM)).isFalse()
    assertThat(db.backupProblemDao().get(id.value)).isNull()
  }

  @Test
  fun unMediaPersoPrimaDelBackupPortaIlSegnoMaNonELaRigaDelTelefono() = runBlocking<Unit> {
    val id = addPhone(1)
    catalog.markVanished(phoneObservation("IMG_1.jpg", 101, smallFull(1), 0).location.key)
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.LOST)
    catalog.markBackupProblem(id, "lost_before_backup")
    assertThat(CellFlags.has(flags(id), CellFlags.BACKUP_PROBLEM)).isTrue()
    assertThat(candidates()).isEmpty()
    assertThat(catalog.safetyCounts(camera)).isEqualTo(CatalogSafetyCounts(0, 0, 0, 0, 1, mapOf("lost_before_backup" to 1)))
  }

  // --- "Persa prima del backup" e la foto che torna (E-B4, E-P11) -------------------------------------------------------------

  /** La lettura del telefono rivede il file del media [n] (come `addPhone`), con l'`_ID` [mediaStoreId] se e' stato rinumerato. */
  private fun seenAgain(n: Int, mediaStoreId: Long = 100L + n) =
    phoneObservation("IMG_$n.jpg", mediaStoreId, smallFull(n), T0 - n * 1_000L)

  @Test
  fun unaFotoRecuperataDalCestinoTornaCandidataESenzaIlSegnoDiPersa() = runBlocking<Unit> {
    val id = addPhone(1)
    val other = addPhone(2)
    assertThat(candidates().map { it.mediaId }).containsExactly(id, other).inOrder()
    val changes = Changes(this, catalog)

    // Persa prima del backup (E-B4): fuori dai candidati, con il segno sulla cella.
    catalog.markBackupProblem(id, "lost_before_backup")
    changes.next()
    assertThat(candidates().map { it.mediaId }).containsExactly(other)
    assertThat(CellFlags.has(flags(id), CellFlags.BACKUP_PROBLEM)).isTrue()
    assertThat(db.mediaDao().get(id.value)!!.backupTodo).isFalse()
    val seq = catalog.changeSeq()

    // L'utente la recupera dal cestino di Galleria: la lettura del telefono la registra di nuovo presente.
    catalog.recordAll(listOf(seenAgain(1)), seenAt = T0 + 10)

    // Nella stessa transazione: la riga, il bit della cella e `backup_todo` ricalcolati, e un solo cambiamento dell'indice.
    assertThat(db.backupProblemDao().get(id.value)).isNull()
    assertThat(CellFlags.has(flags(id), CellFlags.BACKUP_PROBLEM)).isFalse()
    assertThat(PresenceBadge.of(flags(id))).isNotEqualTo(PresenceBadge.PROBLEM)
    assertThat(db.mediaDao().get(id.value)!!.backupTodo).isTrue()
    assertThat(candidates().map { it.mediaId }).containsExactly(id, other).inOrder()
    assertThat(catalog.mediaSafety(id)!!.problem).isNull()
    assertThat(catalog.safetyCounts(camera).problems).isEmpty()
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
    assertThat(changes.next().ids).containsExactly(id)
    assertThat(changes.none()).isTrue()
    changes.job.cancel()
  }

  @Test
  fun laFotoRipristinataDopoEssereSparitaTornaCandidataConLaRigaNuova() = runBlocking<Unit> {
    val id = addPhone(1)
    // Sparita dal telefono prima di essere caricata: assente, persa, segnata.
    catalog.markVanished(seenAgain(1).location.key)
    catalog.markBackupProblem(id, "lost_before_backup")
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.LOST)
    assertThat(candidates()).isEmpty()
    val changes = Changes(this, catalog)
    val seq = catalog.changeSeq()

    // Ripristinata dal cestino: la riga del MediaStore ha un `_ID` nuovo, il posto e' lo stesso.
    catalog.recordAll(listOf(seenAgain(1, mediaStoreId = 555)), seenAt = T0 + 10)

    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.NO_NAS_COPY)
    assertThat(db.backupProblemDao().get(id.value)).isNull()
    assertThat(CellFlags.has(flags(id), CellFlags.BACKUP_PROBLEM)).isFalse()
    assertThat(db.mediaDao().get(id.value)!!.backupTodo).isTrue()
    val candidate = candidates().single()
    assertThat(candidate.mediaId).isEqualTo(id)
    assertThat(candidate.phone.mediaStoreId).isEqualTo(555)
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
    assertThat(changes.next().ids).containsExactly(id)
    assertThat(changes.none()).isTrue()
    changes.job.cancel()
  }

  @Test
  fun laConfermaDelTimbroTogliaIlSegnoDiPersaComeLaRegistrazione() = runBlocking<Unit> {
    val a = addPhone(1)
    val b = addPhone(2)
    val c = addPhone(3)
    fun stamped(n: Int) = PhoneStamp(Db.phone("IMG_$n.jpg", 100L + n), FileStamp(2_000, T0, 7))

    // In lotto: due media persi e uno no, una transazione e un cambiamento solo; il terzo non e' toccato.
    catalog.markBackupProblem(a, "lost_before_backup")
    catalog.markBackupProblem(b, "lost_before_backup")
    catalog.markBackupProblem(c, "unreadable")
    assertThat(candidates()).isEmpty()
    var seq = catalog.changeSeq()
    assertThat(catalog.confirmAllPresent(listOf(stamped(1), stamped(2), stamped(3)), seenAt = T0 + 5)).containsExactly(true, true, true)
    assertThat(candidates().map { it.mediaId }).containsExactly(a, b).inOrder()
    assertThat(db.backupProblemDao().get(a.value)).isNull()
    assertThat(db.backupProblemDao().get(b.value)).isNull()
    assertThat(db.backupProblemDao().get(c.value)!!.code).isEqualTo("unreadable")
    assertThat(CellFlags.has(flags(c), CellFlags.BACKUP_PROBLEM)).isTrue()
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)

    // Da solo, con `confirmPresent`.
    catalog.markBackupProblem(a, "lost_before_backup")
    assertThat(candidates().map { it.mediaId }).containsExactly(b)
    seq = catalog.changeSeq()
    assertThat(catalog.confirmPresent(Db.phone("IMG_1.jpg", 101), FileStamp(2_000, T0, 7), seenAt = T0 + 6)).isTrue()
    assertThat(candidates().map { it.mediaId }).containsExactly(a, b).inOrder()
    assertThat(CellFlags.has(flags(a), CellFlags.BACKUP_PROBLEM)).isFalse()
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
  }

  @Test
  fun glAltriProblemiNonSiTolgonoConUnaNuovaOsservazioneDelTelefono() = runBlocking<Unit> {
    val unreadable = addPhone(1)
    val verifyFailed = addPhone(2)
    val commonLost = addPhone(3)
    val problems = mapOf(unreadable to "unreadable", verifyFailed to "verify_failed", commonLost to "common_copy_lost")
    problems.forEach { (id, code) -> catalog.markBackupProblem(id, code) }
    val seq = catalog.changeSeq()

    // Registrati di nuovo (stessa riga) e confermati col timbro: il problema resta, la cella resta segnata, nessun candidato.
    catalog.recordAll(listOf(seenAgain(1), seenAgain(2), seenAgain(3)), seenAt = T0 + 10)
    catalog.confirmAllPresent(
      (1..3).map { PhoneStamp(Db.phone("IMG_$it.jpg", 100L + it), FileStamp(2_000, T0, 7)) },
      seenAt = T0 + 11,
    )
    catalog.confirmPresent(Db.phone("IMG_1.jpg", 101), FileStamp(2_000, T0, 7), seenAt = T0 + 12)

    problems.forEach { (id, code) ->
      assertWithMessage(code).that(db.backupProblemDao().get(id.value)?.code).isEqualTo(code)
      assertWithMessage(code).that(CellFlags.has(flags(id), CellFlags.BACKUP_PROBLEM)).isTrue()
      assertWithMessage(code).that(db.mediaDao().get(id.value)!!.backupTodo).isFalse()
    }
    assertThat(candidates()).isEmpty()
    assertThat(catalog.changeSeq()).isEqualTo(seq)
  }

  @Test
  fun ilSegnoDiPersaRestaSeNonRicompareUnaPosizioneDelTelefonoDiQuelMedia() = runBlocking<Unit> {
    val lost = addPhone(1)
    catalog.markVanished(seenAgain(1).location.key)
    catalog.markBackupProblem(lost, "lost_before_backup")

    // Un altro file del telefono, e una copia sul NAS dello stesso media: nessuna e' la posizione del telefono che mancava.
    addPhone(2)
    addPrivateCopy(1)
    catalog.confirmPresent(Db.phone("IMG_1.jpg", 101), FileStamp(2_000, T0, 7), seenAt = T0 + 5) // assente: non si conferma a occhio

    assertThat(db.backupProblemDao().get(lost.value)!!.code).isEqualTo("lost_before_backup")
    assertThat(CellFlags.has(flags(lost), CellFlags.BACKUP_PROBLEM)).isTrue()
    assertThat(candidates().map { it.mediaId }).doesNotContain(lost)
  }

  // --- Conteggi -----------------------------------------------------------------------------------------------------------------

  @Test
  fun iConteggiDellaSicurezzaSonoDeiMediaPropriDelleSorgenti() = runBlocking<Unit> {
    val safe = addPhone(1)
    val verified = addPrivateCopy(1)
    catalog.markVerified(verified, smallFull(1).fullHash!!, T0)
    val unverified = addPhone(2)
    addPrivateCopy(2)
    addPhone(3)
    addPhone(4)
    val common = addPhone(5)
    catalog.recordAll(listOf(nasObservation("Famiglia/IMG_5.jpg", smallFull(5), share = "NAS")), seenAt = T0)
    val lost = addPhone(6)
    catalog.markVanished(phoneObservation("IMG_6.jpg", 106, smallFull(6), 0).location.key)
    val screenshot = addPhone(7, source = MediaSource.SCREENSHOT, path = "Pictures/Screenshots/")
    catalog.markBackupProblem(screenshot, "verify_failed")
    // Non e' mio: media di un'altra persona nell'area comune.
    catalog.recordAll(listOf(nasObservation("Famiglia/Altri.jpg", smallFull(60), share = "NAS")), seenAt = T0)

    val counts = catalog.safetyCounts(camera)
    assertThat(counts).isEqualTo(CatalogSafetyCounts(safe = 1, unverified = 1, onlyCommonCopy = 1, noNasCopy = 2, lost = 1, problems = emptyMap()))
    val withScreenshots = catalog.safetyCounts(setOf(CatalogSource.Camera, CatalogSource.Screenshots))
    assertThat(withScreenshots.noNasCopy).isEqualTo(3)
    assertThat(withScreenshots.problems).containsExactly("verify_failed", 1)
    assertThat(catalog.safetyCounts(emptySet())).isEqualTo(CatalogSafetyCounts.ZERO)
    assertThat(catalog.safetyCounts(setOf(CatalogSource.Folder("external_primary", "Pictures/Telegram/")))).isEqualTo(CatalogSafetyCounts.ZERO)
    assertThat(listOf(safe, unverified, common, lost)).hasSize(4)
  }

  // --- Lotti di copie ---------------------------------------------------------------------------------------------------------

  private suspend fun batchOf(count: Int, first: Int = 1): List<AttachedCopy> = (first until first + count).map { n ->
    val media = addPhone(n)
    AttachedCopy(media, catalog.versionOf(media), nasLocation("Backup/S25/2025/10/IMG_$n.jpg"), FileStamp(2_000, T0 + n), T0 + 100 + n)
  }

  @Test
  fun unLottoDiCopieVerificateEUnaTransazioneConUnSoloCambiamento() = runBlocking<Unit> {
    val batch = batchOf(40)
    val changes = Changes(this, catalog)
    val seq = catalog.changeSeq()

    assertThat(catalog.attachCopies(batch)).isEqualTo(40)

    // Un solo cambiamento per il lotto: tutti i media dentro, una sola sequenza.
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
    val change = changes.next()
    assertThat(change.seq).isEqualTo(seq + 1)
    assertThat(change.ids).containsAtLeastElementsIn(batch.map { it.mediaId })
    assertThat(changes.none()).isTrue()
    // Ognuno e' al sicuro, la copia e' nell'area privata, verificata; nessun candidato rimasto.
    batch.forEach { copy ->
      assertThat(catalog.safety(copy.mediaId)!!.status).isEqualTo(SafetyStatus.SAFE)
      assertThat(PresenceBadge.of(flags(copy.mediaId))).isEqualTo(PresenceBadge.SAFE)
      assertThat(catalog.mediaSafety(copy.mediaId)!!.verifiedAtMillis).isEqualTo(copy.verifiedAtMillis)
    }
    assertThat(candidates()).isEmpty()
    assertThat(catalog.safetyCounts(camera).safe).isEqualTo(40)

    // Idempotente sulla stessa posizione: nessun doppione, nessuna posizione in piu'.
    val locationsBefore = batch.sumOf { catalog.media(it.mediaId)!!.locations.size }
    assertThat(catalog.attachCopies(batch)).isEqualTo(40)
    assertThat(batch.sumOf { catalog.media(it.mediaId)!!.locations.size }).isEqualTo(locationsBefore)
    changes.job.cancel()
  }

  @Test
  fun unLottoSbagliatoNonAttaccaNienteEUnaVersioneSparitaSiSalta() = runBlocking<Unit> {
    val batch = batchOf(5)
    val seq = catalog.changeSeq()
    // Una dimensione diversa dalla versione e' un errore di programmazione: o tutte o nessuna.
    val wrong = batch.toMutableList().also { it[3] = AttachedCopy(it[3].mediaId, it[3].versionId, it[3].location, FileStamp(1_999, T0), T0) }
    assertThrows(IllegalArgumentException::class.java) { runBlocking { catalog.attachCopies(wrong) } }
    batch.forEach { assertThat(catalog.media(it.mediaId)!!.locations.none { l -> l.isNas }).isTrue() }
    assertThat(catalog.changeSeq()).isEqualTo(seq)

    // Verificata contro un hash completo non noto: stesso errore.
    val noHash = catalog.recordAll(listOf(nasObservation("Vecchi/Q.jpg", smallQuick(80))), seenAt = T0).single().mediaId!!
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking {
        catalog.attachCopies(listOf(AttachedCopy(noHash, catalog.versionOf(noHash), nasLocation("Backup/Q.jpg"), FileStamp(2_000, T0), T0)))
      }
    }

    // Una versione che non esiste piu' (unita nel frattempo) si salta e non si conta.
    val ghost = AttachedCopy(batch[0].mediaId, VersionId("00000000-0000-4000-8000-0000000000ff"), nasLocation("Backup/G.jpg"), FileStamp(2_000, T0), T0)
    assertThat(catalog.attachCopies(listOf(ghost, batch[1], batch[2]))).isEqualTo(2)
    assertThat(catalog.safety(batch[0].mediaId)!!.status).isEqualTo(SafetyStatus.NO_NAS_COPY)
    assertThat(catalog.safety(batch[1].mediaId)!!.status).isEqualTo(SafetyStatus.SAFE)
  }

  @Test
  fun unaCopiaNonVerificataNonFaIlMediaAlSicuro() = runBlocking<Unit> {
    val id = addPhone(1)
    val unverified = AttachedCopy(id, catalog.versionOf(id), nasLocation("Backup/S25/IMG_1.jpg"), FileStamp(2_000, T0), null)
    assertThat(catalog.attachCopies(listOf(unverified))).isEqualTo(1)
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.PRIVATE_COPY_UNVERIFIED)
    assertThat(candidates().map { it.mediaId }).containsExactly(id)
  }

  // --- Deduplica (D8) -----------------------------------------------------------------------------------------------------------

  @Test
  fun lePrivateCopieCandidateSonoSoloDellAreaPrivataDelProprietario() = runBlocking<Unit> {
    // Stessa dimensione e stesso qh1: nell'area privata, anche in una cartella dei vecchi backup.
    val mine = catalog.recordAll(listOf(nasObservation("Vecchi backup/2019/foto.jpg", smallFull(1))), seenAt = T0).single()
    // Gli stessi byte nell'area comune e in un'area privata di un altro account.
    catalog.registerShare(NasShare(AccountId.of("maria"), "maria", NasArea.PRIVATE))
    catalog.recordAll(listOf(nasObservation("Famiglia/stessa.jpg", smallFull(1), share = "NAS")), seenAt = T0)
    val maria = Location.Nas(AccountId.of("maria"), "maria", NasPath.of("Foto/stessa.jpg"))
    catalog.recordAll(listOf(Observation(maria, FileStamp(2_000, T0), smallFull(1), MediaKind.PHOTO, MediaSource.OTHER)), seenAt = T0)
    // Un'altra dimensione e un altro qh1 con la stessa dimensione.
    catalog.recordAll(listOf(nasObservation("Vecchi backup/2019/altra.jpg", smallFull(2))), seenAt = T0)
    val sameSizeOtherBytes = smallFull(3)
    catalog.recordAll(listOf(nasObservation("Vecchi backup/2019/gemella.jpg", sameSizeOtherBytes)), seenAt = T0)

    val found = catalog.privateCopies("alessio", 2_000, smallFull(1).quickHash, 10)
    assertThat(found.map { it.locationId }).containsExactly(mine.locationId)
    assertThat(found.single().stamp).isEqualTo(NasStamp(2_000, T0))
    assertThat(found.single().path).isEqualTo(NasPath.of("Vecchi backup/2019/foto.jpg"))
    // Senza qh1: tutte le copie di quella dimensione dell'area privata (3), niente area comune ne' account altrui.
    assertThat(catalog.privateCopies("ALESSIO", 2_000, null, 10)).hasSize(3)
    assertThat(catalog.privateCopies("alessio", 2_000, null, 2)).hasSize(2)
    // L'area comune e l'area privata di un altro account non si chiedono nemmeno.
    assertThat(catalog.privateCopies("NAS", 2_000, null, 10)).isEmpty()
    assertThat(catalog.privateCopies("maria", 2_000, null, 10)).isEmpty()
    assertThat(catalog.privateCopies("alessio", 3, null, 10)).isEmpty()
  }

  // --- Verifiche (D14) ------------------------------------------------------------------------------------------------------------

  @Test
  fun leCopieDaRileggereSonoQuelleDellAreaPrivataPerStato() = runBlocking<Unit> {
    // Tre copie private verificate in momenti diversi, una mai riletta con hash noto, una con hash non noto, una nell'area comune.
    val verified = (1..3).map { n -> addPhone(n).also { id -> addPrivateCopy(n).also { c -> catalog.markVerified(c, smallFull(n).fullHash!!, T0 + (4 - n) * 1_000L) } } }
    val unverified = addPhone(4)
    val unverifiedCopy = addPrivateCopy(4)
    val noHash = catalog.recordAll(listOf(nasObservation("Vecchi/senzahash.jpg", smallQuick(5))), seenAt = T0).single().mediaId!!
    catalog.requestWork(listOf(noHash), WorkStage.FULL_HASH)
    catalog.recordAll(listOf(nasObservation("Famiglia/comune.jpg", smallFull(6), share = "NAS")), seenAt = T0)

    // Non verificate: solo con l'hash della versione noto (la senzahash e' per `FULL_HASH`), mai l'area comune.
    val pending = catalog.verifyCandidates(CatalogVerifyScope.Unverified, 10)
    assertThat(pending.map { it.copy.locationId }).containsExactly(unverifiedCopy)
    assertThat(pending.single().expected).isEqualTo(smallFull(4).fullHash)
    assertThat(pending.single().verifiedAtMillis).isNull()
    assertThat(pending.single().size).isEqualTo(2_000)
    assertThat(pending.single().copy.share).isEqualTo("alessio")

    // `FULL_HASH`: la copia del NAS di una versione senza hash completo, con la fase in coda.
    val fullHash = catalog.verifyCandidates(CatalogVerifyScope.FullHash, 10)
    assertThat(fullHash.map { it.copy.mediaId }).containsExactly(noHash)
    assertThat(fullHash.single().expected).isNull()

    // Rotazione: dalla verifica piu' vecchia, con il tetto.
    val rotation = catalog.verifyCandidates(CatalogVerifyScope.Rotation, 10)
    assertThat(rotation.map { it.verifiedAtMillis }).containsExactly(T0 + 1_000, T0 + 2_000, T0 + 3_000).inOrder()
    assertThat(rotation.map { it.copy.mediaId }).containsExactly(verified[2], verified[1], verified[0]).inOrder()
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.Rotation, 2).map { it.verifiedAtMillis }).containsExactly(T0 + 1_000, T0 + 2_000).inOrder()
    rotation.forEach { assertThat(it.expected).isNotNull() }

    // Sul momento: le copie private di quei media, verificate o no.
    val onTheSpot = catalog.verifyCandidates(CatalogVerifyScope.OnTheSpot(setOf(verified[0], unverified, noHash)), 10)
    assertThat(onTheSpot.map { it.copy.mediaId }).containsExactly(verified[0], unverified, noHash)
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.OnTheSpot(emptySet()), 10)).isEmpty()
    // Un tetto basso vale anche per i lotti di identificativi.
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.OnTheSpot(setOf(verified[0], unverified, noHash)), 2)).hasSize(2)
  }

  @Test
  fun laRotazioneRileggeUnaCopiaCheNonTornaEQuellaPerdeAlSicuro() = runBlocking<Unit> {
    val id = addPhone(1)
    val copy = addPrivateCopy(1)
    catalog.markVerified(copy, smallFull(1).fullHash!!, T0 + 1)
    val toRead = catalog.verifyCandidates(CatalogVerifyScope.Rotation, 1).single()
    // Un byte diverso: la copia non e' piu' integra.
    assertThat(catalog.markVerified(toRead.copy.locationId, smallFull(9).fullHash!!, T0 + 2)).isFalse()
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.PRIVATE_COPY_UNVERIFIED)
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.Rotation, 10)).isEmpty()
    // La copia non integra si ricorda: non esce di nuovo tra le "non verificate" finche' il file e' lo stesso.
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.Unverified, 10)).isEmpty()
    assertThat(db.nasCopyMismatchDao().get(copy.value)).isNotNull()
  }

  // --- Hash completo (D10) --------------------------------------------------------------------------------------------------------

  @Test
  fun unHashCompletoCalcolatoQuiSiImparaUnaVoltaESiConfrontaDopo() = runBlocking<Unit> {
    val id = catalog.recordAll(listOf(nasObservation("Vecchi/A.jpg", smallQuick(1))), seenAt = T0).single().mediaId!!
    val version = catalog.versionOf(id)
    catalog.requestWork(listOf(id), WorkStage.FULL_HASH)
    assertThat(catalog.pendingWork(WorkStage.FULL_HASH, WorkPlace.NAS, 10)).containsExactly(id)
    val sha = smallFull(1).fullHash!!

    assertThat(catalog.recordFullHash(id, version, sha)).isEqualTo(FullHashRecord.RECORDED)
    assertThat(catalog.media(id)!!.currentVersion.fingerprint.fullHash).isEqualTo(sha)
    assertThat(catalog.workStates(id).first { it.stage == WorkStage.FULL_HASH }.status).isEqualTo(WorkStatus.DONE)
    assertThat(catalog.recordFullHash(id, version, sha)).isEqualTo(FullHashRecord.SAME)
    // Byte diversi da quelli della versione (il file del telefono e' cambiato, E-B15): conflitto, niente cambia.
    val other = Sha256("e".repeat(64))
    assertThat(catalog.recordFullHash(id, version, other)).isEqualTo(FullHashRecord.CONFLICT)
    assertThat(catalog.media(id)!!.currentVersion.fingerprint.fullHash).isEqualTo(sha)
    // Una versione che non e' di quel media, o che non c'e'.
    val someoneElse = addPhone(2)
    assertThat(catalog.recordFullHash(someoneElse, version, sha)).isEqualTo(FullHashRecord.UNKNOWN_VERSION)
    assertThat(catalog.recordFullHash(id, VersionId("00000000-0000-4000-8000-0000000000ee"), sha)).isEqualTo(FullHashRecord.UNKNOWN_VERSION)
  }

  @Test
  fun gliHashCompletiRisolvonoUnGruppoProbabileDoppione() = runBlocking<Unit> {
    val quick = smallQuick(5)
    val phoneVideo = catalog.recordAll(
      listOf(phoneObservation("VID_1.mp4", 1, quick, T0, kind = MediaKind.VIDEO)),
      MediaCatalog.WhenUndecided.KEEP_SEPARATE,
    ).single().mediaId!!
    val nasVideo = catalog.recordAll(listOf(nasObservation("Video/VID_1.mp4", quick, kind = MediaKind.VIDEO)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = T0)
      .single().mediaId!!
    assertThat(phoneVideo).isNotEqualTo(nasVideo)
    val sha = smallFull(5).fullHash!!

    assertThat(catalog.recordFullHash(nasVideo, catalog.versionOf(nasVideo), sha)).isEqualTo(FullHashRecord.RECORDED)
    // Si aspetta anche quello del telefono; arrivato, stessi byte: un media solo.
    assertThat(db.mediaDao().count()).isEqualTo(2)
    assertThat(catalog.recordFullHash(phoneVideo, catalog.versionOf(phoneVideo), sha)).isEqualTo(FullHashRecord.RECORDED)
    assertThat(db.mediaDao().count()).isEqualTo(1)
    assertThat(catalog.media(minOf(phoneVideo, nasVideo))!!.locations).hasSize(2)
  }

  // --- Guardiano (D9) -----------------------------------------------------------------------------------------------------------

  @Test
  fun iMediaProprioConLaSolaCopiaComuneSonoPerIlGuardiano() = runBlocking<Unit> {
    val id = addPhone(1)
    catalog.recordAll(listOf(nasObservation("Famiglia/2025/IMG_1.jpg", smallFull(1), share = "NAS", fileId = 42)), seenAt = T0)
    val withPrivate = addPhone(2)
    catalog.recordAll(listOf(nasObservation("Famiglia/2025/IMG_2.jpg", smallFull(2), share = "NAS")), seenAt = T0)
    addPrivateCopy(2) // ha gia' una copia privata (non verificata): non e' piu' "solo comune"
    val unknownArea = addPhone(3)
    catalog.recordAll(listOf(nasObservation("Boh/IMG_3.jpg", smallFull(3), share = "boh")), seenAt = T0)
    val nothingOnNas = addPhone(4) // niente sul NAS

    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.ONLY_COMMON_COPY)
    // Chi ha la sola copia nell'area comune NON si ricarica dal telefono (§2.11 punto 3, Q4 (b)): ci pensa il guardiano. Si
    // ricarica chi non ha niente sul NAS e chi ha una copia privata da rileggere (D8).
    assertThat(candidates().map { it.mediaId }).containsExactly(withPrivate, nothingOnNas).inOrder()
    assertThat(candidates().map { it.mediaId }).containsNoneOf(id, unknownArea)
    assertThat(db.mediaDao().get(id.value)!!.backupTodo).isFalse()
    assertThat(catalog.safety(unknownArea)!!.status).isEqualTo(SafetyStatus.ONLY_COMMON_COPY) // area sconosciuta: non e' privata
    assertThat(catalog.safety(withPrivate)!!.status).isEqualTo(SafetyStatus.PRIVATE_COPY_UNVERIFIED)

    val found = catalog.onlyCommonCopies(null, 10)
    // Solo l'area comune dichiarata: un'area di cui non si sa niente potrebbe essere privata di un altro.
    assertThat(found.map { it.common.mediaId }).containsExactly(id)
    val candidate = found.single()
    assertThat(candidate.common.share).isEqualTo("NAS")
    assertThat(candidate.common.path).isEqualTo(NasPath.of("Famiglia/2025/IMG_1.jpg"))
    assertThat(candidate.fileName).isEqualTo("IMG_1.jpg")
    assertThat(candidate.size).isEqualTo(2_000)
    assertThat(candidate.fullHash).isEqualTo(smallFull(1).fullHash)
    assertThat(candidate.quickHash).isEqualTo(smallFull(1).quickHash)
    assertThat(candidate.localDay).isNotNull()
    assertThat(candidate.common.stamp.fileId).isEqualTo(42)
    // Per un insieme di media: solo quelli.
    assertThat(catalog.onlyCommonCopies(setOf(id), 10)).hasSize(1)
    assertThat(catalog.onlyCommonCopies(setOf(withPrivate, unknownArea), 10)).isEmpty()
    assertThat(catalog.onlyCommonCopies(emptySet(), 10)).isEmpty()

    // La copia privata ricreata (non ancora verificata): non e' piu' solo comune. Verificata: al sicuro.
    val created = AttachedCopy(id, catalog.versionOf(id), nasLocation("Backup/Area comune/2025/10/IMG_1.jpg"), FileStamp(2_000, T0), T0 + 1)
    catalog.attachCopies(listOf(created))
    assertThat(catalog.onlyCommonCopies(null, 10)).isEmpty()
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.SAFE)
    assertThat(candidates().map { it.mediaId }).doesNotContain(id)

    // Se la copia privata esce da "al sicuro" (non torna) il media ha ancora la copia comune e la privata: non e' "solo comune"
    // ne' un candidato da ricaricare, ma una copia da rileggere (la rotazione) e da rifare dal telefono se serve.
    val privateCopy = catalog.media(id)!!.locations.first { (it.location as? Location.Nas)?.share == "alessio" }.id
    catalog.markVerified(privateCopy, smallFull(77).fullHash!!, T0 + 5)
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.PRIVATE_COPY_UNVERIFIED)
    assertThat(candidates().map { it.mediaId }).contains(id)
  }

  @Test
  fun leCopieInCodaFullHashAncheDelleAreaComuneSiRileggonoMaNonFannoMaiAlSicuro() = runBlocking<Unit> {
    // Una copia nell'area comune di un file che nessuno ha ancora letto per intero, e una nell'area privata.
    val common = catalog.recordAll(listOf(nasObservation("Famiglia/IMG_70.jpg", smallQuick(70), share = "NAS")), seenAt = T0).single().mediaId!!
    val private = catalog.recordAll(listOf(nasObservation("Vecchi/IMG_71.jpg", smallQuick(71))), seenAt = T0).single().mediaId!!
    catalog.requestWork(listOf(common, private), WorkStage.FULL_HASH)

    val queue = catalog.verifyCandidates(CatalogVerifyScope.FullHash, 10)
    assertThat(queue.map { it.copy.mediaId }).containsExactly(common, private)
    assertThat(queue.first { it.copy.mediaId == common }.copy.share).isEqualTo("NAS")
    queue.forEach { assertThat(it.expected).isNull() }

    // Il verificatore impara l'hash: la fase si chiude e la copia esce dalla coda.
    queue.forEach {
      val seed = if (it.copy.mediaId == common) 70 else 71
      assertThat(catalog.recordFullHash(it.copy.mediaId, it.copy.versionId, smallFull(seed).fullHash!!)).isEqualTo(FullHashRecord.RECORDED)
    }
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.FullHash, 10)).isEmpty()

    // Mai "al sicuro" da una copia comune: anche riletta con l'hash giusto, resta "solo copia comune".
    val mine = addPhone(1)
    val commonCopy = catalog.recordAll(listOf(nasObservation("Famiglia/IMG_1.jpg", smallFull(1), share = "NAS")), seenAt = T0).single().locationId!!
    assertThat(catalog.markVerified(commonCopy, smallFull(1).fullHash!!, T0 + 1)).isTrue()
    assertThat(catalog.safety(mine)!!.status).isEqualTo(SafetyStatus.ONLY_COMMON_COPY)
    assertThat(CellFlags.has(flags(mine), CellFlags.SAFE)).isFalse()
    assertThat(candidates()).isEmpty() // e non si ricarica dal telefono
  }

  @Test
  fun leCopieNonIntegreOConUnProblemaNonSiRileggonoAOgniGiro() = runBlocking<Unit> {
    val a = addPhone(1)
    val copyA = addPrivateCopy(1)
    val b = addPhone(2)
    val copyB = addPrivateCopy(2)
    val c = addPhone(3)
    val copyC = addPrivateCopy(3)
    suspend fun unverified() = catalog.verifyCandidates(CatalogVerifyScope.Unverified, 10).map { it.copy.locationId }
    assertThat(unverified()).containsExactly(copyA, copyB, copyC)

    // La rilettura non torna: la copia e' segnata non integra e non si rilegge finche' il file e' lo stesso.
    assertThat(catalog.markVerified(copyA, smallFull(99).fullHash!!, T0 + 1)).isFalse()
    assertThat(db.nasCopyMismatchDao().get(copyA.value)!!.let { it.size to it.modifiedAt }).isEqualTo(2_000L to T0)
    assertThat(unverified()).containsExactly(copyB, copyC)
    assertThat(unverified()).containsExactly(copyB, copyC) // e al giro dopo
    // Una rilettura senza hash noto non e' una prova che la copia sia rovinata: nessun segno nuovo.
    val noHash = catalog.recordAll(listOf(nasObservation("Vecchi/senza.jpg", smallQuick(5))), seenAt = T0).single().locationId!!
    assertThat(catalog.markVerified(noHash, smallFull(5).fullHash!!, T0 + 2)).isFalse()
    assertThat(db.nasCopyMismatchDao().get(noHash.value)).isNull()

    // Un media con un problema che chiede l'utente: le sue copie non si rileggono.
    catalog.markBackupProblem(b, "verify_failed")
    assertThat(unverified()).containsExactly(copyC)
    // Un problema che non marca la cella non e' un problema del media.
    catalog.markBackupProblem(c, "phone_storage_low", marksCell = false)
    assertThat(unverified()).containsExactly(copyC)
    // Tolto il problema, tornano.
    catalog.markBackupProblem(b, null)
    assertThat(unverified()).containsExactly(copyB, copyC)

    // La verifica sul momento (la chiede l'utente) rilegge comunque la copia segnata.
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.OnTheSpot(setOf(a)), 10).map { it.copy.locationId }).containsExactly(copyA)

    // Il file cambia (altro timbro): il segno non vale piu' e la copia torna da verificare.
    val moved = AttachedCopy(a, catalog.versionOf(a), nasLocation("Backup/S25/2025/10/IMG_1.jpg"), FileStamp(2_000, T0 + 1_000), null)
    catalog.attachCopies(listOf(moved))
    assertThat(unverified()).containsExactly(copyA, copyB, copyC)
    // Una rilettura che torna toglie il segno.
    assertThat(catalog.markVerified(copyA, smallFull(1).fullHash!!, T0 + 3)).isTrue()
    assertThat(db.nasCopyMismatchDao().get(copyA.value)).isNull()
    assertThat(unverified()).containsExactly(copyB, copyC)
    assertThat(catalog.safety(a)!!.status).isEqualTo(SafetyStatus.SAFE)
    // La posizione sparita porta via il suo segno (cascata).
    assertThat(catalog.markVerified(copyB, smallFull(98).fullHash!!, T0 + 4)).isFalse()
    assertThat(db.nasCopyMismatchDao().get(copyB.value)).isNotNull()
    db.locationDao().delete(copyB.value)
    assertThat(db.nasCopyMismatchDao().get(copyB.value)).isNull()
  }

  /** Mette in coda `FULL_HASH` per il media, comunque sia messa la sua fase (anche con l'hash gia' noto). */
  private suspend fun forcePendingFullHash(media: MediaId, version: VersionId?) {
    db.workStateDao().upsert(listOf(WorkRules.pending(WorkStage.FULL_HASH, version, T0).toEntity(media)))
  }

  private suspend fun fullHashStatus(media: MediaId) = catalog.workStates(media).first { it.stage == WorkStage.FULL_HASH }.status

  @Test
  fun unHashCompletoChiudeSempreLaFaseFullHashAncheConConflittoOVersioneSconosciuta() = runBlocking<Unit> {
    val sha = smallFull(1).fullHash!!
    val other = Sha256("e".repeat(64))

    // Conflitto: la versione ha gia' un hash diverso da quello letto; la fase non resta in coda a ripetersi.
    val known = catalog.recordAll(listOf(nasObservation("Vecchi/A.jpg", smallFull(1))), seenAt = T0).single().mediaId!!
    val knownVersion = catalog.versionOf(known)
    forcePendingFullHash(known, knownVersion)
    assertThat(catalog.pendingWork(WorkStage.FULL_HASH, WorkPlace.NAS, 10)).contains(known)
    assertThat(catalog.recordFullHash(known, knownVersion, other)).isEqualTo(FullHashRecord.CONFLICT)
    assertThat(fullHashStatus(known)).isEqualTo(WorkStatus.DONE)
    assertThat(catalog.pendingWork(WorkStage.FULL_HASH, WorkPlace.NAS, 10)).doesNotContain(known)
    assertThat(catalog.media(known)!!.currentVersion.fingerprint.fullHash).isEqualTo(sha) // l'hash noto non cambia

    // Stesso hash: anche qui la fase si chiude.
    forcePendingFullHash(known, knownVersion)
    assertThat(catalog.recordFullHash(known, knownVersion, sha)).isEqualTo(FullHashRecord.SAME)
    assertThat(fullHashStatus(known)).isEqualTo(WorkStatus.DONE)

    // Versione sconosciuta (unita nel frattempo): la fase del media, che non punta a nessuna versione, esce dalla coda.
    val orphan = catalog.recordAll(listOf(nasObservation("Vecchi/B.jpg", smallQuick(2))), seenAt = T0).single().mediaId!!
    forcePendingFullHash(orphan, null)
    val ghost = VersionId("00000000-0000-4000-8000-0000000000dd")
    assertThat(catalog.recordFullHash(orphan, ghost, sha)).isEqualTo(FullHashRecord.UNKNOWN_VERSION)
    assertThat(fullHashStatus(orphan)).isEqualTo(WorkStatus.GAVE_UP)
    assertThat(catalog.pendingWork(WorkStage.FULL_HASH, WorkPlace.NAS, 10)).doesNotContain(orphan)
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.FullHash, 10)).isEmpty()
    // Non e' fatta per davvero: una nuova richiesta la rimette in coda.
    assertThat(catalog.requestWork(listOf(orphan), WorkStage.FULL_HASH)).isEqualTo(1)
    assertThat(fullHashStatus(orphan)).isEqualTo(WorkStatus.PENDING)

    // Versione di un altro media: la fase di quella versione si chiude (e il suo hash non si impara dal media sbagliato).
    val owner = catalog.recordAll(listOf(nasObservation("Vecchi/C.jpg", smallQuick(3))), seenAt = T0).single().mediaId!!
    val ownerVersion = catalog.versionOf(owner)
    catalog.requestWork(listOf(owner), WorkStage.FULL_HASH)
    assertThat(catalog.recordFullHash(orphan, ownerVersion, sha)).isEqualTo(FullHashRecord.UNKNOWN_VERSION)
    assertThat(fullHashStatus(owner)).isEqualTo(WorkStatus.GAVE_UP)
    assertThat(catalog.media(owner)!!.currentVersion.fingerprint.fullHash).isNull()
    assertThat(catalog.pendingWork(WorkStage.FULL_HASH, WorkPlace.NAS, 10)).doesNotContain(owner)
  }

  // --- Stato di un media e posizioni delle versioni -----------------------------------------------------------------------------------

  @Test
  fun loStatoDiUnMediaEDoveStannoLeSueVersioni() = runBlocking<Unit> {
    val id = addPhone(1)
    val copy = addPrivateCopy(1)
    catalog.recordAll(listOf(nasObservation("Famiglia/IMG_1.jpg", smallFull(1), share = "NAS")), seenAt = T0)
    assertThat(catalog.mediaSafety(id)).isEqualTo(CatalogMediaSafety(SafetyStatus.PRIVATE_COPY_UNVERIFIED, null, null))
    catalog.markVerified(copy, smallFull(1).fullHash!!, T0 + 7)
    assertThat(catalog.mediaSafety(id)).isEqualTo(CatalogMediaSafety(SafetyStatus.SAFE, T0 + 7, null))
    assertThat(catalog.mediaSafety(MediaId("99999999-9999-4999-8999-999999999999"))).isNull()

    val versions = catalog.versionLocations(id)
    assertThat(versions).hasSize(1)
    val only = versions.single()
    assertThat(only.isCurrent).isTrue()
    assertThat(only.size).isEqualTo(2_000)
    assertThat(only.fullHash).isEqualTo(smallFull(1).fullHash)
    assertThat(only.locations.map { it.location.isPhone to it.area }).containsExactly(true to null, false to NasArea.PRIVATE, false to NasArea.COMMON)
      .inOrder()
    assertThat(only.locations.all { it.location.state == LocationState.PRESENT }).isTrue()
    assertThat(catalog.versionLocations(MediaId("99999999-9999-4999-8999-999999999999"))).isEmpty()
  }
}
