package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.free.BootCounter
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.upload.UploadControl
import dev.pampa.mycloudgallery.core.backup.versions.VersionHold
import dev.pampa.mycloudgallery.core.backup.versions.VersionNames
import dev.pampa.mycloudgallery.core.backup.versions.VersionWorld
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * I tre DIFETTI trovati dalla prova delle 500 interruzioni della 5B (P13, `docs/prova-500.md` Parte C §C6), **corretti il 19/10** (decisioni.md,
 * "Correzioni della prova 5B: F1, F2, F3"). Per ognuno:
 * - lo scenario di P13, senza giri casuali, con il **comportamento giusto** (il test che P13 aveva lasciato `@Ignore`), sul NAS finto e sul
 *   simulatore SMB con la sessione e le primitive vere;
 * - i casi vicini che la correzione deve lasciare in pace (o sistemare anche loro);
 * - **il giro del banco che lo trovava**, rifatto dal suo seme con gli evitamenti spenti: ora e' verde.
 *
 * Gli evitamenti del banco (`Backup5BConfig.avoid`, [KnownDefect]) sono stati tolti: il banco prova davvero questi casi a ogni `check.ps1`.
 */
class ChaosKnownDefectsTest {

  @get:Rule val folder = TemporaryFolder()

  // =============================================================================================================================================
  // F1 (P8, `NasVersionEngine.refreshPhone`): un lavoro dal telefono non resta mai appeso a una riga che non tornera'
  // =============================================================================================================================================

  /**
   * Lo scenario di F1: un lavoro `VERSION` pianificato dalla riga del telefono, poi il MediaStore si ricostruisce (`_ID` rinumerati, E-P12) e la foto
   * cambia di nuovo prima che il lavoro la legga. La versione del lavoro non e' piu' sul telefono. Prima della correzione `refreshPhone` aspettava
   * per sempre (`PLANNED`): "Ripristina originale" non era mai disponibile e "Libera spazio" escludeva il media. Restituisce lo stato del lavoro.
   */
  private suspend fun f1SupersededJob(w: VersionWorld): UploadState {
    val seed = w.seedSafe()
    val v1 = w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    val planned = w.journal.all().single()
    assertThat(planned.state).isEqualTo(UploadState.PLANNED)
    val v2 = rebuildAndEditAgain(w, seed)
    w.runUntilQuiet(maxRounds = 20)
    assertThat(w.journal.all().first { it.versionId == v2 }.state).isEqualTo(UploadState.DONE)
    return w.journal.all().first { it.versionId == v1 }.state
  }

  /** Il MediaStore si ricostruisce (il catalogo ritrova la riga alla lettura del telefono) e Samsung salva ancora: versione nuova, lavoro nuovo. */
  private suspend fun rebuildAndEditAgain(w: VersionWorld, seed: VersionWorld.Seeded): VersionId {
    val fresh = 900L
    w.phone.rebuild("v2", mapOf(seed.phoneId!! to fresh))
    w.catalog.phoneRenumbered(seed.mediaId, seed.phoneId, w.phone.ref(fresh)!!)
    val bytes = w.bytes(seed.content.size + 2_000)
    w.knownContents += Sha256.of(bytes)
    w.phone.rewrite(fresh, NasContent.of(bytes), 999_999L)
    val v2 = w.catalog.phoneChanged(seed.mediaId, w.phone.ref(fresh)!!, w.phone.stamp(fresh)!!, bytes.size.toLong(), null)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, fresh, v2)))
    return v2
  }

  @Test
  fun f1_ilLavoroDellaVersioneSuperataFinisceGoneENessunLavoroRestaAperto() = runTest {
    val w = VersionWorld(folder.newFolder())
    assertThat(f1SupersededJob(w)).isEqualTo(UploadState.GONE)
    // Nessun lavoro aperto sul media: "Ripristina originale" e "Libera spazio" non lo escludono piu'.
    assertThat(w.journal.all().filter { !it.state.terminal }).isEmpty()
  }

  /** Un giro che si ferma dopo [limit] byte caricati (la fine di una tappa): il lavoro resta a meta' con il suo temporaneo e il punto di ripresa. */
  private class StopAfter(private val limit: Long) : UploadControl {
    private var seen = 0L

    override suspend fun proceed(): Boolean = seen < limit

    override suspend fun traffic(bytes: Long) {
      seen += bytes
    }
  }

  @Test
  fun f1_unVideoAMetaTogliePrimaIlSuoTemporaneoPoiFinisceGone() = runTest {
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe(name = "VID_0001.mp4", kind = MediaKind.VIDEO, mime = "video/mp4")
    val v1 = w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    // La prima tappa scrive una parte del temporaneo e si ferma (il punto di ripresa e' nel diario).
    w.engine.run(1.hours, StopAfter(40_000))
    val half = w.journal.all().single()
    assertThat(half.state).isEqualTo(UploadState.UPLOADING)
    assertThat(half.resumeAt).isGreaterThan(0L)
    val temp = half.temp!!
    assertThat(w.nas.bytes(w.share, temp.value)).isNotNull()
    // Il MediaStore si ricostruisce e la foto cambia ancora; un processo nuovo riprende.
    val v2 = rebuildAndEditAgain(w, seed)
    w.restart()
    w.runUntilQuiet(maxRounds = 20)
    assertThat(w.journal.all().first { it.versionId == v1 }.state).isEqualTo(UploadState.GONE)
    assertThat(w.journal.all().first { it.versionId == v2 }.state).isEqualTo(UploadState.DONE)
    assertWithMessage("il temporaneo del lavoro finito GONE").that(w.nas.bytes(w.share, temp.value)).isNull()
    assertThat(w.nas.filePaths(w.share).filter { it.startsWith(".mycloudgallery/") }).isEmpty()
  }

  @Test
  fun f1_conLaRigaDiPrimaAncoraNelCatalogoIlLavoroAspettaLaLetturaDelTelefono() = runTest {
    // Il catalogo non ha ancora visto il cambio di versione del MediaStore: niente `GONE` in anticipo, si aspetta la lettura del telefono.
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    w.phone.rebuild("v2", mapOf(seed.phoneId to 900L))
    w.engine.run(1.hours)
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.PLANNED)
    // La lettura del telefono ritrova la riga (stesso timbro): il lavoro la segue e finisce.
    w.catalog.phoneRenumbered(seed.mediaId, seed.phoneId, w.phone.ref(900L)!!)
    w.runUntilQuiet()
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.DONE)
  }

  @Test
  fun ilGiroCheTrovavaF1OraEVerde() {
    // Seme 20261016, giro 116: un lavoro `VERSION` era fermo in `planned` a lavoro finito.
    assertThat(roundWithoutAvoiding(20261016L, 116)).isEmpty()
  }

  // =============================================================================================================================================
  // F2 (P3 `BackupPrivateCopyGuardian.link`, innescato da P8): nessun doppione della copia del guardiano
  // =============================================================================================================================================

  /** Il mondo di F2: il guardiano ha pubblicato e attaccato al catalogo la copia privata di un media con la sola copia comune, poi e' morto. */
  private inner class F2Scene(nas: SceneNas) : AutoCloseable {
    val clock = FakeBackupClock()
    val world = BackupChaosWorld(
      clock,
      when (nas) {
        SceneNas.FAKE -> FakeNasSide(clock, BackupChaosWorld.DEVICE)
        SceneNas.SMB -> SimSmbNasSide(clock, BackupChaosWorld.DEVICE)
      },
    )
    val fb = FiveBWorld(world, Backup5BConfig(), Random(5), folder.newFolder())
    val injector = Injector(null, onConnectionLost = {}, actions = WorldActions.NONE)
    private var incarnation = 0
    private var seeded: VersionId? = null
    lateinit var rec: MediaRec

    /** La versione che il guardiano protegge (l'attuale quando il media e' stato seminato). */
    val v0: VersionId get() = seeded!!

    /** I servizi di un "processo" nuovo. */
    fun next(): FiveBServices = services(world, fb, injector, Incarnation(incarnation++))

    /** Il guardiano copia, pubblica e attacca la copia al catalogo; il processo muore subito dopo, prima di `LINKED`. */
    suspend fun guardianDiesAfterAttach(): F2Scene {
      rec = fb.seed(FiveBCategory.COMMON_ONLY)
      seeded = fb.catalog.inner.currentOf(rec.mediaId)
      injector.load(listOf(FaultRule(Trigger.OfPrimitive(Primitive.CATALOG_ATTACH, 1), Moment.AFTER, FaultKind.KILL)))
      val died = runCatching { next().guardian.ensure(GuardScope.Media(setOf(rec.mediaId)), 1.hours) }.exceptionOrNull()
      assertThat(died).isInstanceOf(ChaosDeath::class.java)
      injector.disarm()
      world.nas.processDied()
      assertThat(fb.guardStore.all().single().state).isEqualTo(UploadState.PUBLISHED)
      return this
    }

    /** I file visibili dell'area privata con i byte della versione del guardiano. */
    suspend fun privateCopiesOfV0(): List<NasFileFact> {
      val sha = fb.registry.getValue(rec.mediaId).versionSha.getValue(v0)
      val scan = BackupNasScanner.ofShares(world.nas.reader, world.nas.shares).scan()
      return scan.files.filter { f -> !f.appArea && f.share.equals(world.share, ignoreCase = true) && f.sha == sha }
    }

    suspend fun temps(): List<NasFileFact> = BackupNasScanner.ofShares(world.nas.reader, world.nas.shares).scan().files.filter { it.appArea }

    override fun close() = world.close()
  }

  /**
   * Lo scenario di F2: mentre l'elemento del guardiano e' ancora `PUBLISHED`, un file preparato (come una correzione) diventa una versione nuova
   * del media e la sua copia andrebbe spostata in `Versioni/`. Prima della correzione la versione la spostava e il guardiano, riprendendo, ne
   * rifaceva una seconda ("nome (2)"). Ora il lavoro della versione aspetta che l'elemento del guardiano finisca, poi la sposta: una copia sola.
   */
  private suspend fun f2VersionWaitsForGuardian(nas: SceneNas) {
    F2Scene(nas).use { s ->
      s.guardianDiesAfterAttach()
      val final = s.fb.guardStore.all().single().finalPath!!
      val (v1, sha, size) = s.fb.stageEdit(s.rec)!!
      val versions = s.next().versions
      versions.planStaged(s.rec.mediaId, v1, sha, size)
      versions.run(1.hours)
      val waiting = s.fb.journal.all().single()
      assertWithMessage("il lavoro della versione aspetta prima di spostare la copia del guardiano").that(waiting.state).isEqualTo(UploadState.ARCHIVING)
      assertThat(waiting.archivedStamp).isNull()
      assertThat(s.privateCopiesOfV0().map { it.path }).containsExactly(final.value)
      // Il guardiano riprende dal suo diario e collega la sua copia; poi la versione la sposta in Versioni/ e pubblica la sua.
      s.next().guardian.ensure(GuardScope.Media(setOf(s.rec.mediaId)), 1.hours)
      assertThat(s.fb.guardStore.all().single().state).isEqualTo(UploadState.DONE)
      s.next().versions.run(1.hours)
      assertThat(s.fb.journal.all().single().state).isEqualTo(UploadState.DONE)
      val copies = s.privateCopiesOfV0()
      assertWithMessage("i file privati con i byte della versione del guardiano: ${copies.map { it.path }}").that(copies).hasSize(1)
      assertThat(VersionPaths.isInVersions(SharePath.parse(copies.single().path))).isTrue()
      assertThat(s.fb.nasSha(s.world.share, final)).isEqualTo(sha)
      assertThat(s.temps()).isEmpty()
    }
  }

  @Test
  fun f2_unaVersioneNuovaAspettaCheIlGuardianoCollegiLaSuaCopiaNessunDoppione() = runTest {
    f2VersionWaitsForGuardian(SceneNas.FAKE)
  }

  @Test
  fun f2_unaVersioneNuovaAspettaCheIlGuardianoCollegiLaSuaCopiaNessunDoppione_simulatoreSmb() = runTest {
    f2VersionWaitsForGuardian(SceneNas.SMB)
  }

  /**
   * L'altra meta' della correzione (P3): se la copia del guardiano e' stata comunque spostata, seguita dal catalogo, prima di `LINKED` (qui a mano,
   * come farebbe una versione), il guardiano riprendendo non la rifa': la cerca per byte nell'area privata (D8, riletta qui) e la collega dov'e'.
   */
  private suspend fun f2GuardianFindsItsMovedCopy(nas: SceneNas) {
    F2Scene(nas).use { s ->
      s.guardianDiesAfterAttach()
      val final = s.fb.guardStore.all().single().finalPath!!
      val copy = s.fb.catalog.media(s.rec.mediaId)!!.version(s.v0)!!.nas.single { it.present && it.share.equals(s.world.share, ignoreCase = true) }
      val moved = VersionNames.inVersions(final, VersionNames.ORIGINAL_LABEL)
      assertThat(s.world.nas.ops.mkdirs(s.world.share, moved.parent()!!, Purpose.BACKGROUND)).isInstanceOf(NasResult.Ok::class.java)
      val renamed = s.world.nas.ops.archiveVersion(s.world.share, final, moved, copy.stamp, Purpose.BACKGROUND)
      assertThat((renamed as NasResult.Ok).value).isInstanceOf(RenameOutcome.Renamed::class.java)
      s.fb.catalog.relocate(copy.locationId, s.world.share, moved, s.fb.stampOf(s.world.share, moved)!!)
      // Il guardiano riprende: il nome finale e' vuoto, la copia c'e' altrove e si collega li'.
      s.next().guardian.ensure(GuardScope.Media(setOf(s.rec.mediaId)), 1.hours)
      val item = s.fb.guardStore.all().single()
      assertThat(item.state).isEqualTo(UploadState.DONE)
      assertThat(item.finalPath).isEqualTo(moved)
      assertThat(s.privateCopiesOfV0().map { it.path }).containsExactly(moved.value)
      assertThat(s.temps()).isEmpty()
      val v0 = s.fb.catalog.media(s.rec.mediaId)!!.version(s.v0)!!
      assertThat(v0.nas.filter { it.present && it.verifiedAtMillis != null }.map { it.path }).containsExactly(moved)
    }
  }

  @Test
  fun f2_ilGuardianoRitrovaLaSuaCopiaSpostataELaCollegaSenzaRifarla() = runTest {
    f2GuardianFindsItsMovedCopy(SceneNas.FAKE)
  }

  @Test
  fun f2_ilGuardianoRitrovaLaSuaCopiaSpostataELaCollegaSenzaRifarla_simulatoreSmb() = runTest {
    f2GuardianFindsItsMovedCopy(SceneNas.SMB)
  }

  @Test
  fun ilGiroCheTrovavaF2OraEVerde() {
    // Seme 20261016, giro 633: gli stessi byte erano pubblicati due volte nell'area privata.
    assertThat(roundWithoutAvoiding(20261016L, 633)).isEmpty()
  }

  // =============================================================================================================================================
  // F3 (P8, `NasVersionEngine.restore` e `archive` dopo una risposta persa): nessun file con un timbro diverso da quello atteso resta "verificato"
  // =============================================================================================================================================

  /**
   * Lo scenario di F3 (`ChaosStaleScene.kt`): "Ripristina originale" interrotto dopo la rinomina, il PC modifica sul posto il file riportato
   * **conservandone la dimensione**, il lavoro riprende. Prima della correzione `sameIdentity` (`fileId` e dimensione) lo prendeva per il suo,
   * scriveva nel catalogo il timbro trovato e teneva la verifica; ora il timbro trovato non e' quello atteso e la rilettura dice altri byte: non e'
   * il suo, lo scambio finisce come con una modifica di un'altra dimensione (O1) e il catalogo non dice "verificato" niente con altri byte.
   */
  private suspend fun f3SwapDoesNotAdoptThePcEdit(nas: SceneNas) {
    staleWindow(folder.newFolder(), PcTouch.SAME_SIZE, nas).use { s ->
      val job = s.fb.journal.all().single()
      assertThat(job.state).isEqualTo(UploadState.FAILED)
      assertThat(job.hold).isEqualTo(VersionHold.USUAL_PATH_TAKEN)
      assertWithMessage("copie verificate con altri byte").that(s.verifiedWithOtherBytes()).isEmpty()
      // A meta' il solo ritardo del catalogo (la copia dell'originale in Versioni/ che li' non c'e' piu'); dopo la scansione tutto in ordine.
      assertThat(s.verdict(Phase.MID).map { it.invariant }.toSet() - Invariant.CATALOG).isEmpty()
      s.fb.rescan()
      assertThat(s.verdict(Phase.FINAL)).isEmpty()
    }
  }

  @Test
  fun f3_loScambioNonPrendeLaModificaDelPcDellaStessaDimensionePerIlSuoFile() = runTest {
    f3SwapDoesNotAdoptThePcEdit(SceneNas.FAKE)
  }

  @Test
  fun f3_loScambioNonPrendeLaModificaDelPcDellaStessaDimensionePerIlSuoFile_simulatoreSmb() = runTest {
    f3SwapDoesNotAdoptThePcEdit(SceneNas.SMB)
  }

  @Test
  fun f3_dopoLaScansioneIlCatalogoNonDiceVerificataUnaCopiaConAltriByte() = runTest {
    // Il test "giusto" di P13.
    val s = staleWindow(folder.newFolder(), keepSize = true)
    s.fb.rescan()
    assertThat(s.verdict(Phase.FINAL)).isEmpty()
  }

  @Test
  fun f3_liberaSpazioNonLiberaIlMedia() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = true)
    s.fb.rescan()
    assertThat(s.freedMedia()).doesNotContain(s.mediaId)
    assertThat(s.fb.currentRow(s.rec)).isNotNull()
  }

  @Test
  fun f3_unaVersioneNuovaNonArchiviaLaModificaDelPcComeSeFosseLOriginale() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = true)
    s.fb.rescan()
    s.fb.samsungSave(s.rec)
    s.fb.rescan()
    s.runVersions(s.services())
    assertWithMessage("copie verificate con altri byte dopo la versione nuova").that(s.verifiedWithOtherBytes()).isEmpty()
    // Niente in Versioni/ si chiama "(originale)" con byte che non sono quelli dell'originale.
    val original = s.fb.catalog.media(s.mediaId)!!.original!!
    val originalSha = s.fb.shaOf(s.mediaId, original.versionId)
    val named = s.copies().filter { (_, c) -> c.present && c.path.name.contains("(${VersionNames.ORIGINAL_LABEL})") }
    for ((_, c) in named) assertThat(s.fb.nasSha(c.share, c.path)).isEqualTo(originalSha)
  }

  /**
   * Il caso vicino che la correzione deve lasciare passare: il PC riscrive il file riportato **con gli stessi byte** (solo la data cambia). Il timbro
   * non e' quello atteso, ma la rilettura fatta qui dice i byte dell'originale: e' il file del lavoro, lo scambio finisce e il catalogo ha il timbro
   * dei byte riletti (la scansione non vede niente da registrare).
   */
  private suspend fun f3DateOnlyTouchIsStillMine(nas: SceneNas) {
    staleWindow(folder.newFolder(), PcTouch.DATE_ONLY, nas).use { s ->
      assertThat(s.fb.journal.all().single().state).isEqualTo(UploadState.DONE)
      val original = s.fb.catalog.media(s.mediaId)!!.original!!
      val atUsual = original.nas.single { it.present && it.path.value.equals(s.usual.value, ignoreCase = true) }
      assertThat(atUsual.verifiedAtMillis).isNotNull()
      assertThat(atUsual.stamp.sameAs(s.fb.stampOf(s.world.share, s.usual)!!)).isTrue()
      assertThat(s.verifiedWithOtherBytes()).isEmpty()
      s.fb.rescan()
      assertThat(s.verdict(Phase.FINAL)).isEmpty()
    }
  }

  @Test
  fun f3_unaRiscritturaConGliStessiByteSiRiconosceConLaRilettura() = runTest {
    f3DateOnlyTouchIsStillMine(SceneNas.FAKE)
  }

  @Test
  fun f3_unaRiscritturaConGliStessiByteSiRiconosceConLaRilettura_simulatoreSmb() = runTest {
    f3DateOnlyTouchIsStillMine(SceneNas.SMB)
  }

  /**
   * F3 vale anche per l'archiviazione (`archived(job, st.stamp)`): un lavoro `VERSION` muore dopo la rinomina che porta l'attuale in `Versioni/`
   * (risposta persa), il PC tocca il file archiviato e un processo nuovo riprende. Restituisce il mondo, il media e il nome in `Versioni/`.
   */
  private suspend fun f3ArchiveWindow(sameBytes: Boolean): Triple<VersionWorld, VersionWorld.Seeded, SharePath> {
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    val died = runCatching { w.newEngine(ops = DieAfterArchive(w.ops)).run(1.hours) }.exceptionOrNull()
    assertThat(died).isInstanceOf(ChaosDeath::class.java)
    val job = w.journal.all().single()
    assertThat(job.state).isEqualTo(UploadState.ARCHIVING)
    val archived = job.archivePath!!
    val stamp = w.statOf(w.share, archived)
    val bytes = if (sameBytes) w.nas.bytes(w.share, archived.value)!! else w.bytes(stamp.size.toInt()).also { w.knownContents += Sha256.of(it) }
    w.nas.modify(w.share, archived.value, NasContent.of(bytes), modifiedAt = stamp.modifiedAtMillis + 4_000L)
    w.restart()
    w.engine.run(1.hours)
    return Triple(w, seed, archived)
  }

  private suspend fun VersionWorld.verifiedWithOtherBytes(mediaId: MediaId) = catalog.media(mediaId)!!.versions.flatMap { v ->
    v.nas.filter { c -> c.present && c.verifiedAtMillis != null && nas.bytes(c.share, c.path.value)?.let { Sha256.of(it) != v.fullHash } == true }
  }

  @Test
  fun f3_lArchiviazioneNonPrendeLaModificaDelPcDellaStessaDimensionePerIlSuoFile() = runTest {
    val (w, seed, archived) = f3ArchiveWindow(sameBytes = false)
    val pcSha = w.sha(w.share, archived.value)
    val job = w.journal.all().single()
    assertThat(job.hold).isEqualTo(VersionHold.SOURCE_CHANGED)
    assertThat(job.archivedStamp).isNull()
    assertThat(w.verifiedWithOtherBytes(seed.mediaId)).isEmpty()
    // La scansione dichiara assente la copia che al solito percorso non c'e' piu' (D10): il piano si ricalcola e la versione va al solito percorso.
    w.catalog.nasVanished(seed.mediaId, w.share, seed.usualPath)
    w.runUntilQuiet()
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(w.sha(w.share, seed.usualPath.value)).isEqualTo(w.phone.sha256(seed.phoneId!!))
    // Il file del PC in Versioni/ non e' stato toccato.
    assertThat(w.sha(w.share, archived.value)).isEqualTo(pcSha)
    assertThat(w.verifiedWithOtherBytes(seed.mediaId)).isEmpty()
  }

  @Test
  fun f3_lArchiviazioneRiconosceConLaRiletturaIlSuoFileRiscrittoUguale() = runTest {
    val (w, seed, archived) = f3ArchiveWindow(sameBytes = true)
    w.runUntilQuiet()
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.DONE)
    val original = w.catalog.media(seed.mediaId)!!.version(seed.original)!!
    val copy = original.nas.single { it.present }
    assertThat(copy.path).isEqualTo(archived)
    assertThat(copy.verifiedAtMillis).isNotNull()
    assertThat(copy.stamp.sameAs(w.statOf(w.share, archived))).isTrue()
    assertThat(w.verifiedWithOtherBytes(seed.mediaId)).isEmpty()
  }

  @Test
  fun ilGiroCheTrovavaF3OraEVerde() {
    // Seme 777, giro 1306: una copia verificata dal catalogo non aveva i byte della sua versione, dopo la scansione.
    assertThat(roundWithoutAvoiding(777L, 1306)).isEmpty()
  }

  // =============================================================================================================================================
  // Il banco
  // =============================================================================================================================================

  /** Un giro preciso del banco JVM (stack vero, NAS finto) senza evitamenti: rifatto dal suo seme e dal suo numero. Le violazioni trovate. */
  private fun roundWithoutAvoiding(seed: Long, round: Int): List<String> = runBlocking {
    val result = BackupChaosBench.run(
      1, seed, FakeNasTarget(), ProductionStack, config = BackupChaosConfig(fiveB = Backup5BConfig(avoid = emptySet())), firstRound = round,
    )
    result.failures.flatMap { it.violations }.map { "${it.invariant}: ${it.detail}" }
  }

  private fun services(world: BackupChaosWorld, fb: FiveBWorld, injector: Injector, incarnation: Incarnation): FiveBServices {
    val env = FiveBEnv(
      world = world,
      fb = fb,
      ops = ChaosBackupOps(world.nas.ops, injector, incarnation),
      reader = ChaosReader(world.nas.reader, injector, incarnation),
      journal = ChaosVersionJournal(fb.journal, injector, incarnation),
      versionCatalog = ChaosVersionCatalog(fb.catalog, injector, incarnation),
      freeCatalog = ChaosFreeCatalog(fb.catalog, injector, incarnation),
      backupCatalog = ChaosCatalog(fb.catalog, injector, incarnation),
      freeDiary = ChaosFreeDiary(fb.freeDiary, injector, incarnation),
      guardStore = ChaosDiary(fb.guardStore, injector, incarnation, ArrayList(), ArrayList()),
      phone = ChaosPhone(world.phone.port, injector, incarnation),
      rewriter = ChaosRewriter(fb.rewriter, injector, incarnation),
      clock = world.clock,
      boot = BootCounter { fb.bootCount },
      random = Random(1),
    )
    return RealFiveBStack.create(env)
  }
}
