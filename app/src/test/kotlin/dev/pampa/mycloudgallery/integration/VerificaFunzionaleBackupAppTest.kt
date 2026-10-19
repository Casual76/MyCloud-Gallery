package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.backup.BackupActionsHost
import dev.pampa.mycloudgallery.backup.BackupGraph
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.TrafficEstimate
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.work.BackupJobEnd
import dev.pampa.mycloudgallery.work.BackupJobs
import dev.pampa.mycloudgallery.work.BackupWorkScheduling
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Le correzioni della verifica funzionale del **backup 5A** (`docs/review/funzionale-fasi-1-5a.md`, sezione 2.5) sul grafo vero: `GalleryGraph`
 * con NAS e backup sopra il NAS finto, le azioni della pagina e il lavoro dell'utente come li monta l'app. Ogni test prova una voce BK; quelli
 * che si possono provare senza il grafo stanno accanto (`RunProgressTest`, `SummaryCacheTest`, `BackupSchedulingTest`, `BackupPermissionStepTest`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class VerificaFunzionaleBackupAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  }

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  /** I lavori avviati dall'utente registrati (JobScheduler non c'e'). */
  private class RecordingJobs : BackupJobs {
    val started = AtomicInteger()

    override fun userInitiated(trigger: BackupTrigger, confirmed: Boolean) {
      started.incrementAndGet()
    }

    override fun cancelAll() = Unit
  }

  /** Un telefono con [photos] foto (la prima e' la piu' recente), il backup aperto e la cartella scelta. */
  private suspend fun openRig(
    photos: Int = 1,
    jobs: BackupJobs = BackupJobs.NONE,
    monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    chooseFolder: Boolean = true,
    hoursAgo: (Int) -> Long = { 3 + it.toLong() },
    /** Altre righe del telefono, prima della prima lettura. */
    extra: (BackupRig) -> Unit = {},
  ): BackupRig {
    val backend = TestNas.backend(time)
    val phone = TestDevice(app, File(temp.root, "telefono-${devices.size}"), TestPlatform(time, backend)).also { devices += it }
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER, jobs = jobs, monotonicMs = monotonicMs).also { rigs += it }
    repeat(photos) { i ->
      BackupRig.photo(rig.store, id = i + 1L, bytes = BackupRig.jpeg(tone = 20 + i * 11), takenAt = time.now() - hoursAgo(i) * HOUR)
    }
    extra(rig)
    rig.open()
    if (chooseFolder) rig.chooseFolder("Pixel di prova")
    return rig
  }

  private suspend fun BackupGraph.await(predicate: (BackupStatus) -> Boolean): BackupStatus = withTimeout(20.seconds) { status.first(predicate) }

  /** Aspetta che il problema ricordato (l'ultimo che ha fermato un giro) sia stato tolto: lo stato in cache puo' essere ancora quello di prima. */
  private suspend fun BackupGraph.awaitNoRememberedProblem() {
    withTimeout(20.seconds) {
      while (memory.lastProblem() != null) {
        kick()
        delay(50)
      }
    }
  }

  private val share = TestNas.ADMIN_USER

  // --- BK-3, BK-13: un permesso concesso toglie il problema dalla pagina --------------------------------------------------------------

  /**
   * L'utente ritira l'accesso ai media: il giro si ferma e la pagina dice "accesso alle foto ritirato" (`MEDIA_PERMISSION`). Lo concede di nuovo
   * dal dialogo di sistema: il problema era **ricordato** (`lastProblem`) e restava sulla pagina fino a un giro riuscito, cioe' fino alla
   * notte, con "Consenti" che non faceva piu' niente (il permesso c'e' gia'). Adesso il problema si confronta col mondo di adesso.
   */
  @Test
  fun bk3_unPermessoConcessoTogliIlProblemaDallaPagina() = runBlocking<Unit> {
    val rig = openRig(photos = 2)
    val backup = rig.backup
    rig.access.set(PhoneAccess(MediaAccess.NONE, mediaLocation = false, volumes = setOf(BackupRig.PRIMARY)))
    val stopped = checkNotNull(rig.round()) { "il giro non e' partito" }
    assertThat(stopped.last?.stoppedBy).isEqualTo(RunStop.PHONE)
    assertThat(backup.memory.lastProblem()).isEqualTo(BackupProblemCode.MEDIA_PERMISSION)

    rig.access.set(PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf(BackupRig.PRIMARY)))

    // Il problema ricordato non vale piu' e si toglie (prima restava fino a un giro riuscito); poi lo stato non e' piu' un problema.
    backup.awaitNoRememberedProblem()
    val status = backup.await { it !is BackupStatus.Problem && it !is BackupStatus.NotConfigured }
    assertWithMessage("stato: $status").that(status).isNotInstanceOf(BackupStatus.Problem::class.java)
    // E il backup riprende davvero: le due foto arrivano al sicuro.
    val done = checkNotNull(rig.round())
    assertThat(done.last?.done).isEqualTo(2)
  }

  @Test
  fun bk3_laPosizioneDeiMediaConcessaTogliIlProblemaEUnAccessoParzialeRestaFinoAlTuttoConsentito() = runBlocking<Unit> {
    val rig = openRig()
    val backup = rig.backup
    backup.memory.setProblem(BackupProblemCode.MEDIA_LOCATION)
    backup.kick()
    // L'accesso adesso c'e' per intero (posizione compresa): il problema ricordato non vale piu'.
    backup.awaitNoRememberedProblem()
    backup.await { it !is BackupStatus.Problem }

    // Un accesso parziale e' vero finche' e' parziale: il problema resta, e passa solo con "tutto".
    rig.access.set(PhoneAccess(MediaAccess.PARTIAL, mediaLocation = true, volumes = setOf(BackupRig.PRIMARY)))
    backup.memory.setProblem(BackupProblemCode.PARTIAL_ACCESS)
    backup.kick()
    backup.await { it == BackupStatus.Problem(BackupProblemCode.PARTIAL_ACCESS, 0) }
    rig.access.set(PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf(BackupRig.PRIMARY)))
    backup.awaitNoRememberedProblem()
    backup.await { it !is BackupStatus.Problem }
  }

  /**
   * Un problema che l'app non puo' verificare da sola (la password del NAS): l'utente esce dalla pagina per sistemarlo e, tornando, il problema
   * ricordato si toglie e il giro si rifa'. Senza un'uscita non si toglie niente (aprire la pagina non cancella i problemi).
   */
  @Test
  fun bk3_alRitornoDaUnAzioneIlProblemaSiTogliEIlGiroSiRifa() = runBlocking<Unit> {
    val rig = openRig(photos = 1)
    val backup = rig.backup
    val actions = rig.actions()
    val host = RecordingHost()
    actions.host = host
    backup.memory.setProblem(BackupProblemCode.AUTH)
    backup.kick()
    backup.await { it == BackupStatus.Problem(BackupProblemCode.AUTH, 0) }

    // La pagina si riapre senza che sia successo niente: il problema e' ancora vero.
    actions.onPageResumed()
    assertThat(backup.memory.lastProblem()).isEqualTo(BackupProblemCode.AUTH)

    // L'utente tocca "Inserisci la password", la cambia e torna.
    actions.perform(BackupAction.ENTER_PASSWORD)
    assertThat(host.opened).containsExactly("password")
    actions.onPageResumed()
    withTimeout(20.seconds) { while (backup.memory.lastProblem() != null) delay(50) }
    backup.await { it !is BackupStatus.Problem }
    // Un secondo ritorno non toglie piu' niente: il tocco e' stato consumato.
    backup.memory.setProblem(BackupProblemCode.NAS_FULL)
    actions.onPageResumed()
    delay(300)
    assertThat(backup.memory.lastProblem()).isEqualTo(BackupProblemCode.NAS_FULL)
  }

  @Test
  fun bk3_riprovaTogliIlProblemaDiUnGiroChiTrovaUnNasPienoOUnaQuota() = runBlocking<Unit> {
    for (code in listOf(BackupProblemCode.NAS_FULL, BackupProblemCode.QUOTA, BackupProblemCode.AUTH, BackupProblemCode.READ_ONLY_AREA)) {
      assertWithMessage("$code offre Riprova").that(code.actions).contains(BackupAction.RETRY)
    }
    val rig = openRig(photos = 1)
    val backup = rig.backup
    backup.memory.setProblem(BackupProblemCode.QUOTA)
    backup.kick()
    backup.await { it == BackupStatus.Problem(BackupProblemCode.QUOTA, 0) }

    rig.actions().perform(BackupAction.RETRY)

    withTimeout(20.seconds) { while (backup.memory.lastProblem() != null) delay(50) }
    backup.await { it !is BackupStatus.Problem }
  }

  // --- BK-7: un'area in sola lettura si rimette alla prova -------------------------------------------------------------------------

  /**
   * Il NAS aveva rifiutato una scrittura (area "in sola lettura"); l'admin ha corretto i permessi. Il giro rimette alla prova la share
   * **prima** di guardare il cancello: prima la prova veniva dopo, e il cancello chiuso dalla sola lettura la saltava, quindi il backup restava
   * fermo fino alle verifiche giornaliere.
   */
  @Test
  fun bk7_unAreaInSolaLetturaSiRimetteAllaProvaEIlGiroCarica() = runBlocking<Unit> {
    val rig = openRig(photos = 2)
    val backup = rig.backup
    backup.noteReadOnly(share)
    assertThat(backup.gate.stateOf(share)).isEqualTo(BackupGateState.READ_ONLY_AREA)

    val outcome = checkNotNull(rig.round()) { "il giro non e' partito" }

    assertWithMessage("giro: ${outcome.last}").that(outcome.last?.done).isEqualTo(2)
    assertThat(backup.gate.stateOf(share)).isEqualTo(BackupGateState.OPEN)
  }

  // --- BK-4: la pausa si toglie solo quando il lavoro parte ------------------------------------------------------------------------

  /**
   * "Ferma", poi "Riprendi" sui dati mobili: il foglio chiede la conferma con la stima. Se l'utente tocca "Annulla" il backup deve restare **in
   * pausa**, come la pagina dice. Prima la pausa si toglieva al tocco su "Riprendi", prima della decisione, e il backup ripartiva al primo
   * Wi-Fi mentre la pagina diceva ancora "In pausa". La pausa finisce solo quando il lavoro parte davvero.
   */
  @Test
  fun bk4_annullareLaConfermaDeiDatiMobiliLasciaIlBackupInPausa() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val rig = openRig(photos = 1, jobs = jobs)
    val backup = rig.backup
    val mobile = RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.MOBILE_DATA), TrafficEstimate(bytes = 1_000_000, monthlyUsedBytes = 0, monthlyCapBytes = null))
    val actions = rig.actions { _, confirmed -> if (confirmed) RuleDecision.Run(network = NetworkClass.MOBILE) else mobile }

    backup.requestStop(StopBy.USER)
    backup.await { it == BackupStatus.Stopped(StopBy.USER) }
    assertThat(backup.isPaused()).isTrue()

    // "Riprendi" sui dati mobili: il foglio chiede la conferma. "Annulla".
    actions.backupNow()
    withTimeout(20.seconds) { actions.prompt.first { it != null } }
    actions.dismissPrompt()
    delay(300)
    assertWithMessage("la pausa resta").that(backup.isPaused()).isTrue()
    assertThat(backup.status.value).isEqualTo(BackupStatus.Stopped(StopBy.USER))
    assertThat(jobs.started.get()).isEqualTo(0)

    // Lo stesso tocco, questa volta confermato: il lavoro parte e la pausa finisce.
    actions.backupNow()
    withTimeout(20.seconds) { actions.prompt.first { it != null } }
    actions.confirm()
    withTimeout(20.seconds) { while (jobs.started.get() == 0) delay(50) }
    assertThat(backup.isPaused()).isFalse()
  }

  /** Senza dati mobili da confermare "Riprendi" finisce la pausa e programma il lavoro, come sempre; "Stanotte" la finisce senza lavoro dell'utente. */
  @Test
  fun bk4_riprendiESenzaConfermaFinisceLaPausaEProgrammaIlLavoro() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val rig = openRig(photos = 1, jobs = jobs)
    val backup = rig.backup
    val actions = rig.actions { _, _ -> RuleDecision.Run(network = NetworkClass.HOME_WIFI) }
    backup.requestStop(StopBy.USER)
    assertThat(backup.isPaused()).isTrue()

    actions.backupNow()

    withTimeout(20.seconds) { while (jobs.started.get() == 0) delay(50) }
    assertThat(backup.isPaused()).isFalse()
    // "Stanotte" del primo backup: niente lavoro dell'utente, ma la pausa e' finita.
    backup.requestStop(StopBy.USER)
    assertThat(backup.isPaused()).isTrue()
    actions.startFirstBackup(tonight = true)
    withTimeout(20.seconds) { while (backup.isPaused()) delay(50) }
    assertThat(jobs.started.get()).isEqualTo(1)
  }

  // --- BK-5: l'avanzamento non torna indietro ------------------------------------------------------------------------------------

  /**
   * Il primo backup su un grafo vero: gli stati `Running` che la pagina e la notifica vedono sono **cumulativi** (fatti piu' in coda): il totale
   * non cambia e i fatti non scendono mai. Il motore pubblica i numeri di un lotto da 60 secondi (ripartono da zero a ogni lotto); la parte che
   * lo prova a lotti veri sta in `RunProgressTest`.
   */
  @Test
  fun bk5_glIStatiInCorsoSonoCumulativiENonTornanoIndietro() = runBlocking<Unit> {
    val total = 24
    val rig = openRig(photos = total, hoursAgo = { 30L + it })
    val backup = rig.backup
    val seen = java.util.Collections.synchronizedList(ArrayList<BackupStatus.Running>())
    val collector = launch(Dispatchers.Default) { backup.status.collect { if (it is BackupStatus.Running) seen += it } }

    val outcome = checkNotNull(rig.round(BackupKind.PHOTOS, BackupTrigger.FIRST_BACKUP))
    delay(1_500)
    collector.cancel()

    assertThat(outcome.last?.done).isEqualTo(total)
    val states = seen.toList()
    var lastDone = 0
    for (state in states) {
      assertWithMessage("stato: $state").that(state.total).isEqualTo(total)
      assertWithMessage("stato: $state").that(state.done).isAtLeast(lastDone)
      assertThat(state.done).isAtMost(state.total)
      lastDone = state.done
    }
  }

  // --- BK-6: un tocco dell'utente non si perde -----------------------------------------------------------------------------------

  /**
   * Il turno e' tenuto da un altro lavoro del backup (le verifiche, il guardiano) e non si libera: "Inizia ora" non finisce piu' come "niente da
   * fare" (`DONE`, il lavoro sparisce) ma come "occupato" e si fa riprogrammare (`RESCHEDULE`). L'orologio monotono corre perche' il test non
   * aspetti i quindici minuti veri del turno.
   */
  @Test
  fun bk6_seIlTurnoEOccupatoIlLavoroDellUtenteSiFaRiprogrammareENonSparisce() = runBlocking<Unit> {
    val fast = AtomicBoolean(false)
    val ticks = AtomicLong(System.nanoTime() / 1_000_000)
    val rig = openRig(photos = 1, monotonicMs = { if (fast.get()) ticks.addAndGet(10 * 60_000L) else System.nanoTime() / 1_000_000 })
    val backup = rig.backup
    val release = CompletableDeferred<Unit>()
    val holder = launch(Dispatchers.Default) { backup.exclusive { release.await() } }
    withTimeout(10.seconds) { while (!isLocked(backup)) delay(20) }
    fast.set(true)

    val end = rig.userJob()

    assertThat(end).isEqualTo(BackupJobEnd.RESCHEDULE)
    assertWithMessage("la foto non e' stata toccata").that(backup.store.counts().open).isEqualTo(0)
    fast.set(false)
    release.complete(Unit)
    holder.join()
  }

  /** Il tocco aspetta il suo turno invece di arrendersi dopo 90 secondi: appena il turno si libera il lavoro parte e la foto va al sicuro. */
  @Test
  fun bk6_unTocco_aspettaIlTurnoEPoiLavora() = runBlocking<Unit> {
    val rig = openRig(photos = 1)
    val backup = rig.backup
    val release = CompletableDeferred<Unit>()
    val holder = launch(Dispatchers.Default) { backup.exclusive { release.await() } }
    withTimeout(10.seconds) { while (!isLocked(backup)) delay(20) }
    assertThat(backup.userIsWaiting).isFalse()

    val job = launch(Dispatchers.Default) { rig.userJob() }
    // Mentre aspetta, lo dice agli altri lavori: cedano il passo.
    withTimeout(10.seconds) { while (!backup.userIsWaiting) delay(20) }
    release.complete(Unit)
    holder.join()
    job.join()

    assertThat(backup.userIsWaiting).isFalse()
    val safe = backup.catalogOrNull()!!.safetyCounts(backup.settings.sources.value)
    assertWithMessage("conti: $safe").that(safe.safe).isEqualTo(1)
  }

  // Il turno e' preso? Una prova a vuoto con un'attesa nulla: se il blocco non gira, il turno e' di qualcun altro.
  private suspend fun isLocked(backup: BackupGraph): Boolean = backup.exclusive(waitFor = kotlin.time.Duration.ZERO) { Unit } == null

  // --- BK-8: il riepilogo del primo backup si ricalcola ----------------------------------------------------------------------------

  @Test
  fun bk8_ilRiepilogoSiRicalcolaQuandoIlBackupHaFattoStrada() = runBlocking<Unit> {
    val rig = openRig(photos = 3, chooseFolder = false)
    val backup = rig.backup
    val before = backup.summary()
    assertThat(before.toUpload.items).isEqualTo(3)

    rig.chooseFolder("Pixel di prova")
    val outcome = checkNotNull(rig.round())
    assertThat(outcome.last?.done).isEqualTo(3)

    // Prima restava quello di un'ora fa ("3 da caricare") finche' non cambiavano le sorgenti: la stima di "Fai ora" sui dati mobili era
    // quella dell'intero primo backup.
    val after = backup.summary()
    assertWithMessage("riepilogo: $after").that(after.toUpload.items).isEqualTo(0)
    assertThat(backup.uploadEstimateBytes()).isEqualTo(0L)
  }

  // --- BK-10 e BK-2: tutti i giri che servono ----------------------------------------------------------------------------------------

  /**
   * Un video rimasto aperto nel diario (un giro interrotto, un file aperto dal PC): prima `next()` lo mandava al lavoro delle **foto**, che lo salta,
   * e il video riprendeva solo con il lavoro periodico delle sei ore. Adesso riprende dal lavoro dei video.
   */
  @Test
  fun bk10_unVideoInterrottoRiprendeDalLavoroDeiVideo() = runBlocking<Unit> {
    val rig = openRig(photos = 1) { r ->
      r.store.put(
        MediaStoreRow(
          volume = BackupRig.PRIMARY, id = 77, type = RowType.VIDEO, displayName = "VID_77.mp4", relativePath = "DCIM/Camera/", mime = "video/mp4",
          size = 40_000, dateModifiedMs = time.now() - HOUR, dateTakenMs = time.now() - 2 * HOUR, durationMs = 3_000,
        ),
      )
    }
    val backup = rig.backup
    // La foto e' gia' al sicuro; il video e' pianificato nel diario e un giro lo ha lasciato a meta'.
    assertThat(checkNotNull(rig.round(BackupKind.PHOTOS, BackupTrigger.USER_NOW)).last?.done).isEqualTo(1)
    val db = rig.gallery.database.openHelper.writableDatabase
    val ids = db.query("SELECT media_id, current_version_id FROM media WHERE kind = 'video' LIMIT 1").use { c -> check(c.moveToFirst()); c.getString(0) to c.getString(1) }
    db.execSQL(
      "INSERT INTO backup_item (kind, state, is_open, media_id, version_id, size, share, planned_path, name_attempt, temp_path, resume_at, " +
        "verify_attempts, created_at, updated_at) VALUES ('new_copy', 'uploading', 1, ?, ?, 40000, ?, 'Backup/x/VID_77.mp4', 0, NULL, 0, 0, 0, 0)",
      arrayOf<Any?>(ids.first, ids.second, share),
    )

    assertThat(backup.nextAll()).containsExactly(BackupGraph.Next.Videos)
    assertThat(backup.hasWork(BackupKind.VIDEOS, BackupTrigger.SCHEDULED)).isTrue()
    // Il lavoro delle foto non ha niente da fare per un video: non si chiede a vuoto.
    assertThat(backup.hasWork(BackupKind.PHOTOS, BackupTrigger.SCHEDULED)).isFalse()
    assertThat(BackupWorkScheduling.afterPhoneRead(app, backup)).containsExactly(BackupWorkScheduling.Kind.VIDEOS)
  }

  /**
   * Una foto appena scattata **e** altre foto da fare: il lavoro "foto nuove" non toglie quello delle foto. Prima `next()` ne dava uno solo
   * (`Quick`) e, se il giro rapido aspettava il Wi-Fi di casa, niente chiedeva il resto.
   */
  @Test
  fun bk2_laFotoNuovaNonToglieIlLavoroDelleFoto() = runBlocking<Unit> {
    // La prima foto di due minuti fa (D34), le altre di ieri.
    val rig = openRig(photos = 4, hoursAgo = { if (it == 0) 0L else 24L + it })
    val backup = rig.backup

    val all = backup.nextAll()
    assertThat(all).containsAtLeast(BackupGraph.Next.Quick, BackupGraph.Next.Photos).inOrder()
    assertThat(BackupWorkScheduling.afterPhoneRead(app, backup))
      .containsAtLeast(BackupWorkScheduling.Kind.QUICK, BackupWorkScheduling.Kind.PHOTOS)
  }

  // --- BK-11: le scelte sulle copie simili chiedono la sincronizzazione --------------------------------------------------------------

  @Test
  fun bk11_unaSceltaSulleCopieSimiliChiedeLaSincronizzazioneDeiDatiPersonali() = runBlocking<Unit> {
    val rig = openRig()
    val similar = checkNotNull(rig.gallery.similarCopies)
    assertThat(rig.backup.personalSyncRequests.get()).isEqualTo(0)

    similar.onChoiceSaved()

    assertThat(rig.backup.personalSyncRequests.get()).isEqualTo(1)
  }

  // --- BK-12: i codici che non arrivavano alla pagina ----------------------------------------------------------------------------------

  @Test
  fun bk12_unBackupFermoDaTreGiorniDiceStaleConLaGuidaDellaBatteria() = runBlocking<Unit> {
    val rig = openRig(photos = 2)
    val backup = rig.backup
    // L'ultimo giro che ha fatto strada e' un'eternita' fa (ora del dispositivo); due foto in coda.
    backup.memory.photoBeat.beat(1_000L)
    backup.kick()

    val status = backup.await { it is BackupStatus.Problem && it.code == BackupProblemCode.STALE }

    assertThat((status as BackupStatus.Problem).actions).containsExactly(BackupAction.BATTERY_GUIDE)
    // Appena il backup fa strada (due foto al sicuro, e il giro batte) lo stato torna "tutto al sicuro".
    checkNotNull(rig.round())
    backup.await { it is BackupStatus.UpToDate }
  }

  // --- BK-13: lo stato si ricalcola quando cambia il mondo -----------------------------------------------------------------------------

  @Test
  fun bk13_unCambioDeiPermessiCambiaLoStatoSenzaAltriEventi() = runBlocking<Unit> {
    val rig = openRig(photos = 1)
    val backup = rig.backup
    backup.await { it is BackupStatus.Waiting || it is BackupStatus.UpToDate }

    rig.access.set(PhoneAccess(MediaAccess.NONE, mediaLocation = false, volumes = setOf(BackupRig.PRIMARY)))

    val status = backup.await { it is BackupStatus.NotConfigured }
    assertThat((status as BackupStatus.NotConfigured).missing).contains(dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep.MEDIA_PERMISSION)
    rig.access.set(PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf(BackupRig.PRIMARY)))
    backup.await { it !is BackupStatus.NotConfigured }
  }

  /**
   * "Riattiva le scritture" si fa in Diagnostica e non ha un evento che il backup ascolti: la pagina, ogni volta che torna davanti all'utente,
   * ricalcola lo stato.
   */
  @Test
  fun bk13_riattivareLeScrittureSiVedeAlRitornoSullaPagina() = runBlocking<Unit> {
    val rig = openRig(photos = 1)
    val backup = rig.backup
    val actions = rig.actions()
    rig.nas.emergency.set(true)
    backup.kick()
    backup.await { it == BackupStatus.Problem(BackupProblemCode.WRITES_STOPPED, 0) }

    // Dalla Diagnostica: "Riattiva le scritture". Nessun evento arriva al backup.
    rig.nas.emergency.set(false)
    delay(1_500)
    assertThat(backup.status.value).isEqualTo(BackupStatus.Problem(BackupProblemCode.WRITES_STOPPED, 0))

    actions.onPageResumed()

    backup.await { it !is BackupStatus.Problem }
  }

  // --- Gli altri: parita' fra il finto e il vero --------------------------------------------------------------------------------------

  /** `BackupActionsHost` che ricorda cosa gli e' stato chiesto. */
  private class RecordingHost : BackupActionsHost {
    val opened = mutableListOf<String>()

    override fun openScreen(screen: BackupScreen) {
      opened += "schermata"
    }

    override fun openDiagnostics() {
      opened += "diagnostica"
    }

    override fun openPassword() {
      opened += "password"
    }

    override fun openNas(screen: NasScreen) {
      opened += "nas"
    }

    override fun openGalleryTrash() {
      opened += "cestino"
    }

    override fun requestMediaAccess() {
      opened += "media"
    }

    override fun requestMediaLocation() {
      opened += "posizione"
    }

    override fun openStorageSettings() {
      opened += "memoria"
    }

    override fun openBatteryGuide() {
      opened += "batteria"
    }
  }

  private companion object {
    const val HOUR = 3_600_000L
  }
}
