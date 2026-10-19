package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.free.BootCounter
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.versions.VersionWorld
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * I DIFETTI NOTI trovati dalla prova delle 500 interruzioni della 5B (P13), `docs/prova-500.md` Parte C §C6. **Non sono corretti**: P13 non tocca il
 * codice di produzione (P8 versioni, P3 guardiano). Per ognuno:
 *
 * - un test del **comportamento di oggi** (verde): riproduce lo scenario senza giri casuali e dice cosa succede; **quando diventa rosso il difetto e'
 *   stato corretto**;
 * - un test del **comportamento giusto**, `@Ignore` con il motivo: lo stesso scenario con l'asserzione rovesciata. Va riacceso (e quello di oggi
 *   cancellato) quando il difetto si corregge;
 * - un test che il **banco lo trova** quando non lo evita (`Backup5BConfig.avoid`, [KnownDefect]): un giro preciso del banco, rifatto dal suo seme.
 *   Il banco li evita per default solo perche' `check.ps1` deve restare verde, non perche' non li sappia vedere.
 *
 * Quando un difetto si corregge si toglie anche la sua voce da [KnownDefect] e dal punto del banco che la usa (`BackupChaos5BPhase`,
 * `BackupChaos5BWorld.pcEdit`).
 */
class ChaosKnownDefectsTest {

  @get:Rule val folder = TemporaryFolder()

  // =============================================================================================================================================
  // F1
  // =============================================================================================================================================

  /**
   * F1 (P8, `NasVersionEngine.refreshPhone`): un lavoro `VERSION` pianificato dalla riga del telefono, poi il MediaStore si ricostruisce (`_ID`
   * rinumerati, E-P12) e il file cambia di nuovo prima che il lavoro lo legga. La riga ora contiene un'altra versione; quella del lavoro non e'
   * piu' sul telefono, ma il lavoro aspetta per sempre una riga che non tornera' (`Step.Wait`, mai `GONE`).
   *
   * Conseguenza: il lavoro e' "aperto" per sempre. Per il media "Ripristina originale" non e' mai disponibile (`NOT_AVAILABLE`: un'altra versione in
   * corso) e "Libera spazio" lo esclude per sempre (`IN_PROGRESS_OR_ERROR`). Nessun file perso, nessun doppione. Raro (serve una ricostruzione del
   * MediaStore nella finestra fra la pianificazione e la lettura, e una modifica nuova prima della prossima lettura del telefono).
   * Rimedio probabile: in `refreshPhone`, se il catalogo non ha nessuna copia presente del telefono per quella versione, `gone(job)`.
   */
  private suspend fun f1StuckJob(): UploadState {
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe()
    val v1 = w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    val planned = w.journal.all().single()
    assertThat(planned.state).isEqualTo(UploadState.PLANNED)
    // Il MediaStore si ricostruisce: la riga cambia `_ID`; il catalogo la ritrova alla prossima lettura del telefono.
    val fresh = 900L
    w.phone.rebuild("v2", mapOf(seed.phoneId!! to fresh))
    w.catalog.phoneRenumbered(seed.mediaId, seed.phoneId!!, w.phone.ref(fresh)!!)
    // Samsung salva ancora, prima che il lavoro abbia letto la riga.
    val bytes = w.bytes(seed.content.size + 2_000)
    w.knownContents += Sha256.of(bytes)
    w.phone.rewrite(fresh, NasContent.of(bytes), 999_999L)
    val v2 = w.catalog.phoneChanged(seed.mediaId, w.phone.ref(fresh)!!, w.phone.stamp(fresh)!!, bytes.size.toLong(), null)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, fresh, v2)))
    w.runUntilQuiet(maxRounds = 20)
    assertThat(w.journal.all().first { it.versionId == v2 }.state).isEqualTo(UploadState.DONE)
    return w.journal.all().first { it.versionId == v1 }.state
  }

  @Test
  fun f1_oggi_unLavoroDalTelefonoConLaRigaVecchiaEUnaModificaNuovaRestaPerSempreInPlanned() = runTest {
    assertWithMessage(
      "F1 e' stato corretto? Il lavoro della versione superata non e' piu' PLANNED: si tolga l'evitamento F1 dal banco (`KnownDefect.F1`) e si " +
        "riaccenda il test f1_giusto.",
    ).that(f1StuckJob()).isEqualTo(UploadState.PLANNED)
  }

  @Ignore("F1 (P8, NasVersionEngine.refreshPhone): il lavoro della versione superata resta PLANNED; dev'essere GONE. Si riaccende quando P8 corregge F1.")
  @Test
  fun f1_giusto_ilLavoroDellaVersioneSuperataFinisceGone() = runTest {
    assertThat(f1StuckJob()).isEqualTo(UploadState.GONE)
  }

  // =============================================================================================================================================
  // F2
  // =============================================================================================================================================

  /**
   * F2 (P3, `BackupPrivateCopyGuardian.link`, scoperto da una correzione di P8): il guardiano ha pubblicato la copia privata di un media con la sola
   * copia comune e l'ha gia' attaccata al catalogo (verificata), ma il processo muore prima di scrivere `LINKED` nel diario. Nel frattempo una versione
   * nuova (qui un file preparato, come una correzione) sposta quella copia in `Versioni/` e pubblica la sua al posto. Riprendendo, il guardiano trova
   * al nome finale altri byte e rifa' la copia: una **seconda copia privata** (`nome (2)`) della stessa versione, che aveva gia' la sua in
   * `Versioni/`. Nessun byte perso; un doppione (invariante 2) e spazio sprecato.
   *
   * Rimedio probabile: riprendendo un elemento `PUBLISHED`, controllare nel catalogo se la versione ha gia' una copia privata verificata (anche in
   * `Versioni/`), e in quel caso chiudere l'elemento (`LINKED_EXISTING`); oppure `DefaultCorrections`/`planStaged` che non partono con un elemento
   * del guardiano aperto per quella versione.
   */
  private suspend fun f2PrivateCopies(): List<NasFileFact> {
    val clock = FakeBackupClock()
    val world = BackupChaosWorld(clock, FakeNasSide(clock, BackupChaosWorld.DEVICE))
    val fb = FiveBWorld(world, Backup5BConfig(), Random(5), folder.newFolder())
    val rec = fb.seed(FiveBCategory.COMMON_ONLY)
    val v0 = fb.catalog.inner.currentOf(rec.mediaId)
    // Una prima incarnazione: il guardiano copia, pubblica e attacca; la morte cade subito dopo l'attacco.
    val injector = Injector(null, onConnectionLost = {}, actions = WorldActions.NONE)
    val dies = FaultRule(Trigger.OfPrimitive(Primitive.CATALOG_ATTACH, 1), Moment.AFTER, FaultKind.KILL)
    injector.load(listOf(dies))
    val first = Incarnation(0)
    val died = runCatching { services(world, fb, injector, first).guardian.ensure(GuardScope.Media(setOf(rec.mediaId)), 1.hours) }.exceptionOrNull()
    assertThat(died).isInstanceOf(ChaosDeath::class.java)
    injector.disarm()
    val item = fb.guardStore.all().single()
    assertThat(item.state).isEqualTo(UploadState.PUBLISHED)
    // Un file preparato (come una correzione) diventa una versione nuova: archivia la copia del guardiano e pubblica la sua al suo posto.
    val second = services(world, fb, injector, Incarnation(1))
    val (v1, sha, size) = fb.stageEdit(rec)!!
    second.versions.planStaged(rec.mediaId, v1, sha, size)
    second.versions.run(1.hours)
    assertThat(fb.journal.all().single().state).isEqualTo(UploadState.DONE)
    // Il guardiano riprende dal suo diario.
    services(world, fb, injector, Incarnation(2)).guardian.ensure(GuardScope.Media(setOf(rec.mediaId)), 1.hours)
    val v0Sha = fb.registry.getValue(rec.mediaId).versionSha.getValue(v0)
    val scan = BackupNasScanner.ofShares(world.nas.reader, world.nas.shares).scan()
    return scan.files.filter { f -> f.share.equals(world.share, ignoreCase = true) && f.sha == v0Sha }
  }

  @Test
  fun f2_oggi_unElementoDelGuardianoRimastoPubblicatoRifaLaCopiaDopoChePassaUnaVersione() = runTest {
    val copies = f2PrivateCopies()
    assertWithMessage(
      "F2 e' stato corretto? I file privati con i byte della versione sono ${copies.size}: se e' 1 si tolga l'evitamento F2 dal banco " +
        "(`KnownDefect.F2`) e si riaccenda il test f2_giusto.",
    ).that(copies).hasSize(2)
    assertThat(SharePath.parse(copies.first().path).components.first()).isAnyOf("Backup", "Versioni")
  }

  @Ignore("F2 (P3, BackupPrivateCopyGuardian.link, innescato da P8): il guardiano rifa' una seconda copia privata (doppione); dev'esserne una sola. Si riaccende quando F2 e' corretto.")
  @Test
  fun f2_giusto_ilGuardianoNonRifaUnaCopiaCheUnaVersioneHaGiaSpostato() = runTest {
    assertThat(f2PrivateCopies()).hasSize(1)
  }

  // =============================================================================================================================================
  // F3
  // =============================================================================================================================================

  /**
   * F3 (P8, `NasVersionEngine.restore`/`archive` per identita'): dopo una rinomina la cui risposta e' andata persa, il lavoro riconosce il suo file
   * per `fileId` e **dimensione** (`sameIdentity`). Se nella finestra fra la rinomina e la ripresa il PC modifica il file sul posto **conservandone la
   * dimensione** (un editor che riscrive i metadati, un byte cambiato), il lavoro lo prende per il suo, scrive nel catalogo **il timbro trovato**
   * (che include la modifica del PC: `restored(job, src, st.stamp)`, `archived(job, st.stamp)`) e **tiene la verifica**.
   *
   * Conseguenze, nello scenario di `ChaosStaleScene.kt` ("Ripristina originale" interrotto dopo la rinomina):
   * - il catalogo dice l'originale **al sicuro** (copia verificata al solito percorso) con **byte diversi** dalla versione (invariante 7);
   * - la scansione del NAS non lo corregge mai: il timbro del catalogo e' gia' quello del file, quindi non vede nessuna modifica;
   * - "Libera spazio" non ne risente: la verifica sul momento rilegge i byte e li confronta con l'impronta della versione (E-B8), il media esce;
   * - una versione nuova successiva archivia i byte del PC come se fossero l'originale (il timbro atteso torna: `archiveVersion` non puo' accorgersene).
   *
   * Rimedio probabile: dopo un ritrovamento per identita' non scrivere nel catalogo il timbro trovato ma quello atteso (la scansione vedra' la
   * differenza e registrera' la modifica del PC), o confrontare anche la data (`NasStamp.sameAs`), o rileggere l'impronta prima di dichiarare
   * `RESTORED`/`ARCHIVED` quando il timbro non e' quello atteso.
   */
  @Test
  fun f3_oggi_loScambioPrendeLaModificaDelPcDellaStessaDimensionePerIlSuoFile() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = true)
    assertWithMessage("F3 e' stato corretto? Il lavoro non e' piu' DONE: si tolga l'evitamento F3 dal banco (`KnownDefect.F3`).")
      .that(s.fb.journal.all().single().state).isEqualTo(UploadState.DONE)
    // Neanche dopo la scansione il catalogo e' in ordine: una copia verificata ha byte che non sono quelli della sua versione.
    s.fb.rescan()
    val verdict = s.verdict(Phase.FINAL)
    assertThat(verdict.map { it.invariant }.toSet()).containsExactly(Invariant.CATALOG)
    assertThat(verdict.single().detail).contains("non ha i byte della sua versione")
  }

  @Test
  fun f3_oggi_liberaSpazioLoFermaComunqueConLaVerificaSulMomento() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = true)
    s.fb.rescan()
    assertThat(s.freedMedia()).doesNotContain(s.mediaId)
    assertThat(s.fb.currentRow(s.rec)).isNotNull()
  }

  @Test
  fun f3_oggi_unaVersioneNuovaArchiviaLaModificaDelPcComeSeFosseLOriginale() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = true)
    s.fb.rescan()
    s.fb.samsungSave(s.rec)
    s.fb.rescan()
    s.runVersions(s.services())
    // Una copia che il catalogo dice verificata ha sul NAS byte diversi da quelli della sua versione: il lavoro non si e' accorto della modifica.
    val wrong = s.copies().filter { (version, copy) ->
      copy.present && copy.verifiedAtMillis != null &&
        s.fb.nasBytes(copy.share, copy.path)?.let { Sha256.of(it) != s.fb.shaOf(s.mediaId, version.versionId) } == true
    }
    assertWithMessage("copie verificate con altri byte dopo la versione nuova: $wrong").that(wrong).isNotEmpty()
  }

  @Ignore("F3 (P8, NasVersionEngine.restore/archive per identita'): la modifica del PC della stessa dimensione e' presa per il file del lavoro e la copia resta verificata con altri byte. Si riaccende quando F3 e' corretto.")
  @Test
  fun f3_giusto_dopoLaScansioneIlCatalogoNonDiceVerificataUnaCopiaConAltriByte() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = true)
    s.fb.rescan()
    assertThat(s.verdict(Phase.FINAL)).isEmpty()
  }

  // =============================================================================================================================================
  // Il banco li trova
  // =============================================================================================================================================

  /** Un giro preciso del banco JVM (stack vero, NAS finto), con i difetti noti non evitati: rifatto dal suo seme e dal suo numero. */
  private fun roundWithoutAvoiding(seed: Long, round: Int): Set<Invariant> = runBlocking {
    val result = BackupChaosBench.run(
      1, seed, FakeNasTarget(), ProductionStack, config = BackupChaosConfig(fiveB = Backup5BConfig(avoid = emptySet())), firstRound = round,
    )
    result.failures.flatMap { it.violations }.map { it.invariant }.toSet()
  }

  @Test
  fun ilBancoTrovaF1QuandoNonLoEvita() {
    // Seme 20261016, giro 116: un lavoro `VERSION` fermo in `planned` a lavoro finito.
    assertThat(roundWithoutAvoiding(20261016L, 116)).contains(Invariant.CONVERGENCE)
  }

  @Test
  fun ilBancoTrovaF2QuandoNonLoEvita() {
    // Seme 20261016, giro 633: gli stessi byte pubblicati due volte nell'area privata.
    assertThat(roundWithoutAvoiding(20261016L, 633)).contains(Invariant.DUPLICATE)
  }

  @Test
  fun ilBancoTrovaF3QuandoNonLoEvita() {
    // Seme 777, giro 1306: una copia verificata dal catalogo che non ha i byte della sua versione, dopo la scansione.
    assertThat(roundWithoutAvoiding(777L, 1306)).contains(Invariant.CATALOG)
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
