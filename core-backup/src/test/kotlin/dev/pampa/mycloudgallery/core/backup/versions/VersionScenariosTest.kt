package dev.pampa.mycloudgallery.core.backup.versions

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.backup.contract.VersionPlace
import dev.pampa.mycloudgallery.core.backup.contract.VersionRole
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B, P8: gli **scenari del §2.4** di docs/fase5-progetto.md, uno per test, sul NAS finto e sul telefono finto con il motore vero delle
 * versioni ([NasVersionEngine]). Ognuno controlla che cosa c'e' sul NAS (l'attuale al solito percorso, le precedenti in `Versioni/` con i
 * loro byte e la loro identita'), che cosa dice il catalogo e gli invarianti del §5.4 (1, 2, 3, 6, 8, 10, 12, 13). I guasti a ogni chiamata
 * e la morte del processo a ogni passo stanno in [VersionEveryCallTest]; "Ripristina originale" dall'app in [VersionKeeperTest]; le
 * correzioni in `corrections/CorrectionsTest`.
 */
class VersionScenariosTest {

  @get:Rule val folder = TemporaryFolder()

  private fun world() = VersionWorld(folder.newFolder())

  private val share = VersionWorld.PRIVATE_SHARE
  private val versions = "Versioni/Backup/S25 di prova/2026/10"

  /** L'ora del NAS dei file scritti nei test (FakeBackupClock) a Roma: 16/10/2026 alle 02.00. */
  private val atNasNow = "versione del 2026-10-16 02.00"

  @Test
  fun e_e1_piuModificheLUltimaAlSolitoPercorsoLOriginaleELeIntermedieInVersioni() = runTest {
    val w = world()
    val seed = w.seedSafe()
    val originalId = w.nas.reader.stat(share, seed.usualPath, dev.pampa.mycloudgallery.core.nas.contract.Purpose.BACKGROUND)
    val before = w.snapshot()
    val originalFileId = before.files.getValue(share).getValue(seed.usualPath.value).second

    // Prima modifica (Samsung "Salva"): una versione nuova; la 5A non ne crea un'altra (l'elemento e' aperto).
    val v1 = w.editOnPhone(seed)
    val v1Bytes = w.phone.sha256(seed.phoneId!!)!!
    val jobs = w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId)))
    assertThat(jobs.map { it.kind }).containsExactly(UploadKind.VERSION)
    assertThat(w.journal.busy(v1)).isTrue()
    // Pianificare due volte non crea un secondo lavoro (nessuna versione doppia).
    assertThat(w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId)))).isEmpty()
    w.runUntilQuiet()
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(v1Bytes)
    val archivedOriginal = "$versions/IMG_1234 (originale).jpg"
    assertThat(w.sha(share, archivedOriginal)).isEqualTo(Sha256.of(seed.content))
    // L'originale e' lo stesso file (stessa identita'): spostato, mai copiato.
    assertThat(w.files().getValue(archivedOriginal).second).isEqualTo(originalFileId)
    // Il catalogo: v1 al sicuro al solito percorso, l'originale in Versioni/ con la sua verifica.
    assertThat(w.catalog.nasCopiesOf(seed.mediaId, v1).single().second).isEqualTo(seed.usualPath)
    assertThat(w.catalog.nasCopiesOf(seed.mediaId, v1).single().third).isNotNull()
    assertThat(w.catalog.nasCopiesOf(seed.mediaId, seed.original).single().second.value).isEqualTo(archivedOriginal)
    assertThat(w.catalog.nasCopiesOf(seed.mediaId, seed.original).single().third).isNotNull()
    assertThat(w.violations(before, final = true)).isEmpty()
    assertThat(originalId).isNotNull()

    // Seconda modifica: v1 va in Versioni/ "(versione del …)", v2 al solito percorso, l'originale resta.
    val v2 = w.editOnPhone(seed)
    val v2Bytes = w.phone.sha256(seed.phoneId)!!
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId)))
    w.runUntilQuiet()
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(v2Bytes)
    assertThat(w.sha(share, "$versions/IMG_1234 ($atNasNow).jpg")).isEqualTo(v1Bytes)
    assertThat(w.sha(share, archivedOriginal)).isEqualTo(Sha256.of(seed.content))
    assertThat(w.violations(before, final = true)).isEmpty()

    // Viewer ⋮ → Versioni: tre versioni, l'ultima attuale; le intermedie si tolgono solo a mano (nessuna cancellazione qui).
    val plan = keeper(w).plan(seed.mediaId)
    assertThat(plan.versions.map { it.role }).containsExactly(VersionRole.ORIGINAL, VersionRole.EDITED, VersionRole.EDITED).inOrder()
    assertThat(plan.current!!.versionId).isEqualTo(v2)
    assertThat(plan.current!!.places).containsExactly(VersionPlace.PHONE, VersionPlace.NAS_USUAL_PATH)
    assertThat(plan.original!!.places).containsExactly(VersionPlace.NAS_VERSIONS_FOLDER)
    assertThat(plan.intermediates.map { it.versionId }).containsExactly(v1)
    assertThat(plan.versions.all { it.safe }).isTrue()
    assertThat(plan.restore).isEqualTo(RestoreAvailability.AVAILABLE)
  }

  @Test
  fun e_e2_ripristinaOriginaleInSamsungGalleryScambiaLeDueSenzaCaricareNiente() = runTest {
    val w = world()
    val seed = w.seedSafe()
    val v1 = w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    w.runUntilQuiet()
    val before = w.snapshot()
    val written = w.nas.backupBytesWritten.get()
    val temps = w.nas.opCount(FakeNasOp.OPEN_TEMP)
    val originalFileId = w.files().getValue("$versions/IMG_1234 (originale).jpg").second

    // Sul telefono "Ripristina originale": la lettura del telefono riconosce il ritorno all'originale (gia' attuale nel catalogo).
    w.revertOnPhone(seed)
    assertThat(w.catalog.currentOf(seed.mediaId)).isEqualTo(seed.original)
    val swap = w.engine.planRealign(seed.mediaId)!!
    assertThat(swap.kind).isEqualTo(UploadKind.SWAP)
    w.runUntilQuiet()
    assertThat(w.journal.job(swap.id)!!.state).isEqualTo(UploadState.DONE)
    // L'originale torna al solito percorso (lo stesso file), v1 in Versioni/; nessun byte caricato, nessun temporaneo.
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(seed.content))
    assertThat(w.files().getValue(seed.usualPath.value).second).isEqualTo(originalFileId)
    assertThat(w.nas.exists(share, "$versions/IMG_1234 (originale).jpg")).isFalse()
    assertThat(w.sha(share, "$versions/IMG_1234 ($atNasNow).jpg")).isNotNull()
    assertThat(w.nas.backupBytesWritten.get()).isEqualTo(written)
    assertThat(w.nas.opCount(FakeNasOp.OPEN_TEMP)).isEqualTo(temps)
    // Il catalogo segue i file: l'originale al solito percorso, v1 in Versioni/, entrambe ancora verificate.
    assertThat(w.catalog.nasCopiesOf(seed.mediaId, seed.original).single().second).isEqualTo(seed.usualPath)
    assertThat(w.catalog.nasCopiesOf(seed.mediaId, v1).single().second.value).startsWith("Versioni/")
    assertThat(w.catalog.nasCopiesOf(seed.mediaId, v1).single().third).isNotNull()
    assertThat(w.violations(before, final = true)).isEmpty()
    // Un secondo "riallinea" non serve piu'.
    assertThat(w.engine.planRealign(seed.mediaId)).isNull()
  }

  @Test
  fun e_e3_unSoloNasModificatoDaUnEditorEsternoDiventaUnaVersioneDallaCopiaInCache() = runTest {
    val w = world()
    val seed = w.seedSafe(onPhone = false)
    val before = w.snapshot()
    // L'editor ha restituito una copia (P11: sessione di modifica): file preparato e versione EDITED nel catalogo.
    val edited = w.bytes(81_000)
    val sha = Sha256.of(edited)
    val work = w.staged.newWorkFile().apply { writeBytes(edited) }
    w.staged.keep(work, sha, seed.mediaId)
    val v = w.catalog.addVersion(seed.mediaId, VersionKind.EDITED, edited.size.toLong(), sha)
    val job = w.engine.planStaged(seed.mediaId, v, sha, edited.size.toLong())!!
    assertThat(job.source).isInstanceOf(VersionSource.Staged::class.java)
    w.runUntilQuiet()
    assertThat(w.journal.job(job.id)!!.state).isEqualTo(UploadState.DONE)
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(sha)
    assertThat(w.sha(share, "$versions/IMG_1234 (originale).jpg")).isEqualTo(Sha256.of(seed.content))
    // Il file preparato non serve piu'.
    assertThat(w.staged.file(sha)).isNull()
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  @Test
  fun e_e4_ilVideoModificatoApreLAttivitaInSospesoEIgnorataLOriginaleResta() = runTest {
    val w = world()
    val seed = w.seedSafe(name = "VID_1.mp4", kind = MediaKind.VIDEO, mime = "video/mp4", size = 90_000)
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    w.runUntilQuiet()
    val task = w.pending.tasks.value.single()
    assertThat(task.versionId).isEqualTo(seed.original)
    assertThat(task.bytes).isEqualTo(seed.content.size.toLong())
    // Ignorato: l'originale resta in Versioni/ e l'attivita' resta in "Da decidere", senza doppioni a ogni giro.
    w.runUntilQuiet()
    assertThat(w.pending.tasks.value).hasSize(1)
    assertThat(w.sha(share, "$versions/VID_1 (originale).mp4")).isEqualTo(Sha256.of(seed.content))
    // "Tienilo": non si propone piu'; un'altra modifica sposta una versione intermedia, non l'originale: nessuna attivita' nuova.
    w.pending.dismiss(task.id)
    assertThat(w.pending.tasks.value).isEmpty()
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId)))
    w.runUntilQuiet()
    assertThat(w.pending.everything.map { it.state }).containsExactly(PendingTaskState.KEPT)
    assertThat(w.sha(share, "$versions/VID_1 (originale).mp4")).isEqualTo(Sha256.of(seed.content))
  }

  @Test
  fun e_e5_unaModificaDopoIlBackupDellOriginaleEUnaVersioneSeEraAMetaNo() = runTest {
    val w = world()
    // L'originale era gia' DONE (al sicuro): la modifica e' una versione (come E-E1).
    val done = w.seedSafe(name = "A.jpg")
    w.editOnPhone(done)
    assertThat(w.engine.planPhoneVersions(listOf(w.candidate(done.mediaId, done.phoneId!!)))).hasSize(1)
    // L'originale non e' mai arrivato al NAS (era a meta' e il file e' cambiato, E-B15): la modifica NON e' una versione; la carica la
    // 5A come copia nuova al suo posto (L38: l'originale non arriva al NAS).
    val (media, original) = w.catalog.addMedia(size = 50_000, fullHash = null)
    val id = 777L
    val ref = w.phone.add(id, NasContent.of(w.bytes(50_000)))
    w.catalog.putPhoneCopy(media, original, ref, w.phone.stamp(id)!!)
    w.phone.rewrite(id, NasContent.of(w.bytes(52_000)), 5_000)
    w.catalog.phoneChanged(media, w.phone.ref(id)!!, w.phone.stamp(id)!!, 52_000, null)
    assertThat(w.engine.planPhoneVersions(listOf(w.candidate(media, id)))).isEmpty()
  }

  @Test
  fun e_e6_unaModificaDalPcResteAttualeEIlTelefonoSalvaLOriginaleInVersioni() = runTest {
    val w = world()
    val seed = w.seedSafe()
    // Il PC modifica il file al suo posto; la scansione crea la versione del PC (attuale) e sposta li' la posizione.
    val fromPc = w.bytes(71_000)
    w.knownContents += Sha256.of(fromPc)
    w.nas.modify(share, seed.usualPath.value, NasContent.of(fromPc))
    val pcVersion = w.catalog.nasChanged(seed.mediaId, share, seed.usualPath, w.statOf(share, seed.usualPath))
    val before = w.snapshot()
    val pcFileId = before.files.getValue(share).getValue(seed.usualPath.value).second
    // Il telefono ha ancora l'originale: il backup lo salva DIRETTAMENTE in Versioni/, il solito percorso non si tocca.
    val jobs = w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!, seed.original)))
    assertThat(jobs.single().target).isEqualTo(VersionTarget.VERSIONS_FOLDER)
    assertThat(jobs.single().current).isNull()
    w.runUntilQuiet()
    assertThat(w.sha(share, "$versions/IMG_1234 (originale).jpg")).isEqualTo(Sha256.of(seed.content))
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(fromPc))
    assertThat(w.files().getValue(seed.usualPath.value).second).isEqualTo(pcFileId)
    assertThat(w.catalog.currentOf(seed.mediaId)).isEqualTo(pcVersion)
    assertThat(w.nas.opCount(FakeNasOp.ARCHIVE_VERSION)).isEqualTo(0)
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  @Test
  fun e_e7_laModificaDellaCopiaScaricataEUnaVersioneNellaCartellaDelFileDelNas() = runTest {
    val w = world()
    // Un media del NAS (non un backup) scaricato in Pictures/MyCloud Gallery: la copia del telefono e' della stessa versione.
    val seed = w.seedSafe(name = "Mare.jpg", folder = "Foto/2024/Estate")
    val mediaCount = w.catalog.allMedia().size
    val before = w.snapshot()
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    w.runUntilQuiet()
    // La versione va nella cartella del file del NAS (non in Backup/), l'originale nel suo specchio in Versioni/; nessun media nuovo.
    assertThat(w.sha(share, "Foto/2024/Estate/Mare.jpg")).isEqualTo(w.phone.sha256(seed.phoneId))
    assertThat(w.sha(share, "Versioni/Foto/2024/Estate/Mare (originale).jpg")).isEqualTo(Sha256.of(seed.content))
    assertThat(w.nas.filePaths(share).none { it.startsWith("Backup/") }).isTrue()
    assertThat(w.catalog.allMedia()).hasSize(mediaCount)
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  @Test
  fun e_e9_nellAreaComuneONellaSolaLetturaLeVersioniNonScrivono() = runTest {
    val w = world()
    // Un media proprio che sta solo nell'area comune: la modifica non diventa una versione scritta li' (D35: serve il foglio).
    val common = w.seedSafe(name = "Comune.jpg", targetShare = VersionWorld.COMMON_SHARE)
    val commonBefore = w.files(VersionWorld.COMMON_SHARE)
    w.editOnPhone(common)
    assertThat(w.engine.planPhoneVersions(listOf(w.candidate(common.mediaId, common.phoneId!!)))).isEmpty()
    w.runUntilQuiet()
    assertThat(w.files(VersionWorld.COMMON_SHARE)).isEqualTo(commonBefore)
    assertThat(keeper(w).plan(common.mediaId).area).isEqualTo(VersionArea.COMMON_NEEDS_NOTICE)

    // L'area privata in sola lettura (E-N9): il giro si ferma al cancello, niente si scrive.
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    w.gate.default = BackupGateState.READ_ONLY_AREA
    val before = w.files()
    val result = w.engine.run(kotlin.time.Duration.parse("1h"))
    assertThat(result.stoppedBy).isEqualTo(RunStop.GATE)
    assertThat(result.problem).isEqualTo(BackupProblemCode.READ_ONLY_AREA)
    assertThat(w.files()).isEqualTo(before)
    assertThat(keeper(w).plan(seed.mediaId).area).isEqualTo(VersionArea.READ_ONLY_NEW_MEDIA)
  }

  @Test
  fun e_x3_lEditorCheSalvaUnaCopiaDentroLaSessioneEUnaVersioneFuoriUnMediaNuovo() = runTest {
    val w = world()
    val seed = w.seedSafe()
    // Dentro la sessione (P11 l'attribuisce al media): una riga nuova del telefono e' una versione dello stesso media.
    val id = 555L
    val copy = w.bytes(72_000)
    w.knownContents += Sha256.of(copy)
    val ref = w.phone.add(id, NasContent.of(copy), 7_000)
    w.catalog.phoneCopyAsVersion(seed.mediaId, ref, w.phone.stamp(id)!!, copy.size.toLong())
    val before = w.snapshot()
    assertThat(w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, id)))).hasSize(1)
    w.runUntilQuiet()
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(copy))
    assertThat(w.sha(share, "$versions/IMG_1234 (originale).jpg")).isEqualTo(Sha256.of(seed.content))
    assertThat(w.violations(before, final = true)).isEmpty()
    // Fuori dalla sessione: un media nuovo, senza solito percorso: non e' una versione (lo salva la 5A).
    val (other, version) = w.catalog.addMedia(size = 10_000, fullHash = null)
    val otherRef = w.phone.add(556L, NasContent.of(w.bytes(10_000)))
    w.catalog.putPhoneCopy(other, version, otherRef, w.phone.stamp(556L)!!)
    assertThat(w.engine.planPhoneVersions(listOf(w.candidate(other, 556L)))).isEmpty()
  }

  /**
   * §2.4 `ARCHIVING`, D17: il PC modifica il file al solito percorso mentre la versione nuova e' nel temporaneo. **Niente si sposta**
   * (`SOURCE_CHANGED`), la versione resta nel temporaneo (citato dal diario), il file del PC non si tocca; quando la scansione registra la
   * versione del PC il piano si ricalcola e la versione del telefono va in `Versioni/`.
   */
  @Test
  fun sourceChanged_ilPcModificaIlFileDuranteIlLavoroENienteSiSposta() = runTest {
    val w = world()
    val hooked = HookedOps(w.ops)
    val engine = w.newEngine(ops = hooked)
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    val v1Bytes = w.phone.sha256(seed.phoneId!!)!!
    engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId)))
    val fromPc = w.bytes(73_000)
    w.knownContents += Sha256.of(fromPc)
    hooked.beforeArchive = {
      hooked.beforeArchive = {}
      w.nas.modify(share, seed.usualPath.value, NasContent.of(fromPc))
    }
    val first = engine.run(kotlin.time.Duration.parse("1h"))
    assertThat(first.held).isEqualTo(1)
    val job = w.journal.all().single()
    assertThat(job.state).isEqualTo(UploadState.ARCHIVING)
    assertThat(job.hold).isEqualTo(VersionHold.SOURCE_CHANGED)
    // Niente spostato: il file del PC al suo posto, nessuna copia in Versioni/, la versione nel suo temporaneo citato.
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(fromPc))
    assertThat(w.nas.filePaths(share).none { it.startsWith("Versioni/") }).isTrue()
    assertThat(w.sha(share, job.temp!!.value)).isEqualTo(v1Bytes)
    assertThat(w.journal.citedTemps()).containsExactly(job.temp)
    val before = w.snapshot()
    // Finche' la scansione non registra il file del PC, il lavoro aspetta (nessun tentativo che sposterebbe altro).
    assertThat(engine.run(kotlin.time.Duration.parse("1h")).held).isEqualTo(1)
    assertThat(w.files()).isEqualTo(before.files.getValue(share))
    // La scansione: la versione del PC e' l'attuale; quella del telefono va in Versioni/.
    w.catalog.nasChanged(seed.mediaId, share, seed.usualPath, w.statOf(share, seed.usualPath))
    w.runUntilQuietWith(engine)
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(fromPc))
    assertThat(w.sha(share, "$versions/IMG_1234 ($atNasNow).jpg")).isEqualTo(v1Bytes)
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  /** Il PC toglie il file al solito percorso durante il lavoro: niente si sposta; registrata l'assenza, la versione va al solito percorso. */
  @Test
  fun sourceChanged_ilPcToglieIlFileELaVersioneVaAlSolitoPercorsoLibero() = runTest {
    val w = world()
    val hooked = HookedOps(w.ops)
    val engine = w.newEngine(ops = hooked)
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    val v1Bytes = w.phone.sha256(seed.phoneId!!)!!
    engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId)))
    hooked.beforeArchive = {
      hooked.beforeArchive = {}
      w.nas.delete(share, seed.usualPath.value)
    }
    assertThat(engine.run(kotlin.time.Duration.parse("1h")).held).isEqualTo(1)
    assertThat(w.journal.all().single().hold).isEqualTo(VersionHold.SOURCE_CHANGED)
    val before = w.snapshot()
    w.catalog.nasVanished(seed.mediaId, share, seed.usualPath)
    w.runUntilQuietWith(engine)
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(v1Bytes)
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  /** Un file di altri compare al solito percorso fra l'archiviazione e la pubblicazione: mai sostituito, la versione va in `Versioni/`. */
  @Test
  fun ilSolitoPercorsoOccupatoDaUnAltroFileNonSiSostituisceMai() = runTest {
    val w = world()
    val hooked = HookedOps(w.ops)
    val engine = w.newEngine(ops = hooked)
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    val v1Bytes = w.phone.sha256(seed.phoneId!!)!!
    engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId)))
    val stranger = w.bytes(5_000)
    w.knownContents += Sha256.of(stranger)
    hooked.beforePublish = {
      hooked.beforePublish = {}
      w.nas.putFile(share, seed.usualPath.value, NasContent.of(stranger))
    }
    w.runUntilQuietWith(engine)
    val job = w.journal.all().single()
    assertThat(job.state).isEqualTo(UploadState.DONE)
    assertThat(job.hold).isEqualTo(VersionHold.USUAL_PATH_TAKEN)
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(stranger))
    assertThat(w.sha(share, "$versions/IMG_1234 ($atNasNow).jpg")).isEqualTo(v1Bytes)
    assertThat(w.sha(share, "$versions/IMG_1234 (originale).jpg")).isEqualTo(Sha256.of(seed.content))
  }

  /** Un nome occupato in `Versioni/` da un altro file: mai sostituito, l'attuale va al nome dopo (scritto nel diario prima). */
  @Test
  fun unNomeOccupatoInVersioniPassaAlNomeDopo() = runTest {
    val w = world()
    val seed = w.seedSafe()
    val other = w.bytes(3_000)
    w.knownContents += Sha256.of(other)
    w.nas.putFile(share, "$versions/IMG_1234 (originale).jpg", NasContent.of(other))
    val before = w.snapshot()
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    w.runUntilQuiet()
    assertThat(w.sha(share, "$versions/IMG_1234 (originale).jpg")).isEqualTo(Sha256.of(other))
    assertThat(w.sha(share, "$versions/IMG_1234 (originale) (2).jpg")).isEqualTo(Sha256.of(seed.content))
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  /** D32: con la share congelata (o le scritture ferme) niente si muove; al disgelo il lavoro riprende e finisce. */
  @Test
  fun congelamento_conLaShareCongelataLeVersioniSiFermanoERipartonoDopo() = runTest {
    val w = world()
    val hooked = HookedOps(w.ops)
    val engine = w.newEngine(ops = hooked)
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    val v1Bytes = w.phone.sha256(seed.phoneId!!)!!
    engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId)))
    val before = w.snapshot()
    // Congelata prima del giro: nessuna chiamata al NAS.
    w.gate.default = BackupGateState.FROZEN
    val frozen = engine.run(kotlin.time.Duration.parse("1h"))
    assertThat(frozen.stoppedBy).isEqualTo(RunStop.GATE)
    assertThat(frozen.problem).isEqualTo(BackupProblemCode.FROZEN)
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.PLANNED)
    // Il congelamento arriva a meta' (la scansione scopre una cifratura di massa mentre si sposta l'attuale): il lavoro si ferma al passo
    // dopo, nessun file a meta' visibile.
    w.gate.default = BackupGateState.OPEN
    hooked.beforeArchive = {
      hooked.beforeArchive = {}
      w.gate.default = BackupGateState.WRITES_STOPPED
    }
    val stopped = engine.run(kotlin.time.Duration.parse("1h"))
    assertThat(stopped.problem).isEqualTo(BackupProblemCode.WRITES_STOPPED)
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.ARCHIVED)
    assertThat(w.violations(before, final = false)).isEmpty()
    w.gate.default = BackupGateState.OPEN
    w.runUntilQuietWith(engine)
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(v1Bytes)
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  // D32: la franchigia del congelamento (e R17: solo la rinomina attesa, per una settimana) e' in `Revisione5BVersioniTest`.

  private fun keeper(w: VersionWorld) = DefaultVersionKeeper(
    share = w.share,
    catalog = w.catalog,
    journal = w.journal,
    engine = w.engine,
    gate = w.gate,
    clock = w.clock,
    reader = w.nas.reader,
    rewriter = PhoneRowRewriter(w.phone),
    staged = w.staged,
  )
}

/** Giri con un motore dato (per i test con le primitive "agganciate"). */
suspend fun VersionWorld.runUntilQuietWith(engine: NasVersionEngine, maxRounds: Int = 12): VersionRunResult {
  var last = VersionRunResult.nothing(RunStop.COMPLETE)
  repeat(maxRounds) {
    last = engine.run(kotlin.time.Duration.parse("1h"))
    val open = journal.all().count { !it.state.terminal }
    if (open == 0 && last.stoppedBy == RunStop.COMPLETE) return last
  }
  assertWithMessage("il lavoro arriva in fondo").that(journal.all().count { !it.state.terminal }).isEqualTo(0)
  return last
}

/** Per i percorsi scritti a mano nei test. */
private fun p(path: String) = SharePath.parse(path)
