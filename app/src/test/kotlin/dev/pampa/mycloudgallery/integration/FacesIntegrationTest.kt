package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.HolderState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.faces.peopleSectionProvider
import dev.pampa.mycloudgallery.feature.people.nav.PeopleEntryTarget
import dev.pampa.mycloudgallery.work.FacesStages
import dev.pampa.mycloudgallery.work.TrustSyncOutcome
import dev.pampa.mycloudgallery.work.TrustSyncRun
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.content.ComponentCallbacks2
import dev.pampa.mycloudgallery.faces.AppCollectionFaceCrops
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fase 8, P13 ("Fatto quando" della riga P13 del §4.4): Persone **sul grafo vero dell'app** e sul NAS simulato, con **due account e tre dispositivi**: il telefono e
 * il tablet di `test` e il telefono di `test2`. Solo i motori dei volti sono finti (`FakeFaceEngines`) e i loro ingressi (scene sintetiche: nessun volto, nessuna
 * immagine, solo numeri); `FacesWiring.build`, il recinto, il cancello D34, l'archivio cifrato (chiavi in memoria della JVM), la replica, il pubblicatore, il custode
 * e la cancellazione sono quelli di produzione.
 *
 * 1. attivazione sul telefono (un solo dispositivo la accende); prima dell'attivazione nessun motore, nessun database, nessuna chiave, nessun file sul NAS;
 * 2. calcolo e istantanea del custode; 3. nomi che viaggiano fra i dispositivi dello stesso account; 4. il dispositivo dell'altro account non vede niente;
 * 5. "Disattiva e cancella" con un dispositivo spento che torna.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class FacesIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private var rig: FacesRig? = null

  @After
  fun tearDown() {
    rig?.close()
  }

  private class Scene(val rig: FacesRig, val phone: FacesNode, val tablet: FacesNode, val other: FacesNode, val photos: Int)

  private fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()

  /** Le foto nelle due aree private, i tre dispositivi montati (Persone spenta) e il catalogo di ognuno letto. */
  private suspend fun scene(photos: Int = 6): Scene {
    val faces = FacesRig(app, temp.root).also { rig = it }
    val phoneDevice = faces.ai.foundedDevice()
    val tabletDevice = faces.joinDevice(phoneDevice, "tablet", TestNas.ADMIN_USER, TestNas.ADMIN_PASSWORD)
    val otherDevice = faces.joinDevice(phoneDevice, "altro", TestNas.MEMBER_USER, TestNas.MEMBER_PASSWORD)
    faces.putPhotos(TestNas.ADMIN_USER, photos)
    faces.putPhotos(TestNas.MEMBER_USER, 3, folder = "Foto/Altro")
    val phone = faces.node("telefono", phoneDevice, TestNas.ADMIN_USER)
    val tablet = faces.node("tablet", tabletDevice, TestNas.ADMIN_USER)
    val other = faces.node("altro", otherDevice, TestNas.MEMBER_USER)
    faces.catalogue(phone, photos)
    faces.catalogue(tablet, photos)
    faces.catalogue(other, 3)
    return Scene(faces, phone, tablet, other, photos)
  }

  /** I giri di [nodes] (dati personali poi volti, l'ora del NAS avanza oltre la testa minima) finche' [condition] vale. */
  private suspend fun roundsUntil(rig: FacesRig, what: String, vararg nodes: FacesNode, condition: suspend () -> Boolean) {
    try {
      withTimeout(Attese.TETTO) {
        while (!condition()) {
          for (node in nodes) {
            node.device.graph.trustGuard.preflight(dev.pampa.mycloudgallery.core.nas.contract.Purpose.BACKGROUND)
            node.round()
          }
          rig.time.advance(6.minutes.inWholeMilliseconds)
        }
      }
    } catch (e: TimeoutCancellationException) {
      val parts = ArrayList<String>()
      for (node in nodes) parts += "${node.name}: ${try { node.describe() } catch (failure: Exception) { "illeggibile (${failure.javaClass.simpleName})" }}"
      val state = parts.joinToString(" || ")
      throw AssertionError("Dopo ${Attese.TETTO} non e' successo: $what. $state", e)
    }
  }

  /** Tutte le tappe del calcolo finche' il lavoro e' finito (`FacesStages` e' la strada del lavoro: regole, battito, segno della tappa). */
  private suspend fun compute(node: FacesNode) {
    val stages = FacesStages(node.device.graph, node.faces, node.noBackup)
    try {
      withTimeout(Attese.TETTO) {
        while (node.live().counters.facesSaved.get() < FACES_EXPECTED) {
          stages.compute(user = true)
        }
      }
    } catch (e: TimeoutCancellationException) {
      throw AssertionError("Il calcolo non e' arrivato a $FACES_EXPECTED volti (ne ha salvati ${node.live().counters.facesSaved.get()})", e)
    }
  }

  private fun filesUnder(share: String, dir: String): List<String> = buildList {
    val rig = checkNotNull(rig)
    for (name in rig.backend.children(share, dir)) {
      val path = if (dir.isEmpty()) name else "$dir/$name"
      if (rig.backend.bytes(share, path) != null) add(path) else addAll(filesUnder(share, path))
    }
  }

  private fun facesFiles(share: String): List<String> = filesUnder(share, ".mycloudgallery/faces")

  private suspend fun activate(node: FacesNode): FacesState {
    val outcome = node.live().switch.activate(FacesActivationConsent(FacesSwitch.CURRENT_TEXT_VERSION, nowNanos()))
    assertWithMessage("attivazione: $outcome").that(outcome).isInstanceOf(ActivateOutcome.Activated::class.java)
    return node.live().switch.currentState()
  }

  /** Le persone senza nome di "Altri volti" appena sono [count] (la vista si rifa' da sola dopo il calcolo o una replica: non si legge a un istante preciso). */
  private suspend fun waitOthers(node: FacesNode, count: Int): List<dev.pampa.mycloudgallery.core.faces.contract.PersonCard> = try {
    withTimeout(Attese.TETTO) { node.live().people.others().first { it.people.size == count } }.people
  } catch (e: TimeoutCancellationException) {
    throw AssertionError("Dopo ${Attese.TETTO} \"Altri volti\" non ha $count persone. ${node.name}: ${node.describe()}", e)
  }

  private suspend fun namedCards(node: FacesNode) = node.live().people.list.value.named

  @Test
  fun attivazioneCalcoloIstantaneaENomiCheViaggiano() = runBlocking<Unit> {
    val s = scene()
    val rig = s.rig

    // 0. Prima dell'attivazione: nessun motore, nessun database, nessuna chiave, niente sul NAS (D4, D8, E-F7).
    for (node in listOf(s.phone, s.tablet, s.other)) {
      val live = node.live()
      assertThat(live.switch.currentState()).isEqualTo(FacesState.Off)
      assertThat(live.engines.isCreated).isFalse()
      assertThat(node.engines.detectCalls.get()).isEqualTo(0)
      assertThat(node.databases()).isEmpty()
      assertThat(node.keys.aliases()).isEmpty()
    }
    for (share in listOf(TestNas.ADMIN_USER, TestNas.MEMBER_USER, TestNas.COMMON)) assertThat(facesFiles(share)).isEmpty()

    // 1. L'attivazione sul telefono: la generazione nasce una volta, il dispositivo e' attivo qui.
    val state = activate(s.phone) as FacesState.On
    assertThat(state.here).isEqualTo(HereState.ACTIVE)
    s.phone.round()

    // Il tablet dello stesso account vede l'avviso (non tratta niente prima di averlo letto); l'altro account non vede niente.
    roundsUntil(rig, "il tablet vede l'avviso", s.phone, s.tablet) { s.tablet.live().switch.currentState() is FacesState.AwaitingNotice }
    assertThat(s.tablet.live().engines.isCreated).isFalse()
    assertThat(s.tablet.databases()).isEmpty()
    assertThat(s.tablet.keys.aliases()).isEmpty()
    s.other.round()
    assertThat(s.other.live().switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(s.other.databases()).isEmpty()
    assertThat(s.other.keys.aliases()).isEmpty()

    // 2. Il calcolo sul telefono (la strada del lavoro) e l'istantanea del custode.
    compute(s.phone)
    s.phone.faces.nightly(30.seconds)
    assertThat(s.phone.engines.detectCalls.get()).isGreaterThan(0)
    assertThat(s.phone.live().engines.isCreated).isFalse() // fine tappa: i motori sono rilasciati
    assertThat(s.phone.keys.aliases()).hasSize(1)
    assertThat(s.phone.databases()).isNotEmpty()
    val others = waitOthers(s.phone, 2)
    assertThat(others.map { it.photos }.sortedDescending()).containsExactly(s.photos, s.photos - 1).inOrder()
    roundsUntil(rig, "il telefono e' il custode", s.phone) { s.phone.live().grouping.holder.value is HolderState.Holder }
    roundsUntil(rig, "i dati dei volti sono sul NAS", s.phone) { facesFiles(TestNas.ADMIN_USER).isNotEmpty() }

    // Solo l'area privata dell'account (L23): niente nell'area comune ne' nell'area dell'altro account.
    assertThat(facesFiles(TestNas.COMMON)).isEmpty()
    assertThat(facesFiles(TestNas.MEMBER_USER)).isEmpty()

    // 3. Il tablet accetta l'avviso (senza generazione nuova) e riceve le persone senza calcolare niente.
    s.tablet.live().switch.acceptNotice(FacesSwitch.CURRENT_TEXT_VERSION)
    assertThat(s.tablet.live().switch.currentState()).isEqualTo(FacesState.On(state.generation, HereState.ACTIVE))
    roundsUntil(rig, "il tablet riceve le persone", s.phone, s.tablet) { s.tablet.live().people.others().first().people.size == 2 }
    assertThat(s.tablet.engines.detectCalls.get()).isEqualTo(0)

    // I nomi viaggiano in tutte e due le direzioni (nomi inventati: nessuna persona vera).
    val first = others.first().id
    val second = others.last().id
    assertThat(s.phone.live().people.rename(first, NAME_ONE)).isEqualTo(dev.pampa.mycloudgallery.core.faces.contract.EditOutcome.Done)
    roundsUntil(rig, "il nome arriva sul tablet", s.phone, s.tablet) { namedCards(s.tablet).any { it.name == NAME_ONE } }
    assertThat(s.tablet.live().people.rename(second, NAME_TWO)).isEqualTo(dev.pampa.mycloudgallery.core.faces.contract.EditOutcome.Done)
    roundsUntil(rig, "il nome arriva sul telefono", s.phone, s.tablet) { namedCards(s.phone).map { it.name }.toSet() == setOf(NAME_ONE, NAME_TWO) }
    assertThat(namedCards(s.tablet).map { it.id }.toSet()).isEqualTo(namedCards(s.phone).map { it.id }.toSet())

    // 4. L'altro account, dopo tutti i giri, non ha visto niente: nessuna persona, nessun database, nessuna chiave, niente sul suo NAS.
    s.other.round()
    assertThat(s.other.live().switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(s.other.live().people.list.value.named).isEmpty()
    assertThat(s.other.live().people.others().first().people).isEmpty()
    assertThat(s.other.databases()).isEmpty()
    assertThat(s.other.keys.aliases()).isEmpty()
    assertThat(s.other.engines.detectCalls.get()).isEqualTo(0)
    assertThat(facesFiles(TestNas.MEMBER_USER)).isEmpty()
  }

  /**
   * AT-2, AT-1 e DR-1 (correzioni B) sul grafo vero: la sezione delle Raccolte **non c'e' finche' le persone non sono montate** (ne' "Attiva riconoscimento dei volti"
   * a chi ha gia' Persone accesa), montata e spenta dice "Attiva", accesa e senza persone dice sempre qualcosa (il primo calcolo che aspetta), e su un dispositivo che
   * non analizza le foto dice che qui le persone non si calcolano. Ogni riga e' consegnata dal fornitore vero delle Raccolte, quello di `FacesModule`.
   */
  @Test
  fun laSezioneDelleRaccolteNonCePrimaDelMontaggioEPoiDiceLaVerita() = runBlocking<Unit> {
    val faces = FacesRig(app, temp.root).also { rig = it }
    val node = faces.node("telefono", faces.ai.foundedDevice(), TestNas.ADMIN_USER)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val computes = MutableStateFlow(true)
      val provider = peopleSectionProvider(app.resources, node.faces, scope, computes)
      suspend fun codes(what: String, condition: (List<String>) -> Boolean): List<String> = try {
        withTimeout(Attese.TETTO) { provider.group.first { condition(it?.entries?.map { entry -> entry.code }.orEmpty()) } }?.entries?.map { it.code }.orEmpty()
      } catch (e: TimeoutCancellationException) {
        throw AssertionError("Dopo ${Attese.TETTO} la sezione non e' arrivata a: $what. Ora: ${provider.group.value?.entries?.map { it.code }}", e)
      }

      // 1. Prima del montaggio: niente (non "spenta").
      assertThat(node.faces.live.value).isNull()
      assertThat(provider.group.value).isNull()

      // 2. Montate, Persone e' spenta: la riga che attiva.
      node.live()
      assertThat(codes("la riga \"Attiva\"") { it.isNotEmpty() }).containsExactly(PeopleEntryTarget.ENABLE)

      // 3. Accesa, prima di qualunque tappa del calcolo e senza persone: il primo calcolo che aspetta, mai zero righe (AT-1).
      activate(node)
      assertThat(codes("il primo calcolo") { it == listOf(PeopleEntryTarget.PROGRESS) }).containsExactly(PeopleEntryTarget.PROGRESS)
      assertThat(node.live().people.list.value.named).isEmpty()

      // 4. Un dispositivo che non analizza le foto dice che qui le persone non si calcolano (DR-1), e torna al primo calcolo se il ruolo si accende.
      computes.value = false
      assertThat(codes("\"Qui le persone non si calcolano\"") { it == listOf(PeopleEntryTarget.NOT_COMPUTED) }).containsExactly(PeopleEntryTarget.NOT_COMPUTED)
      assertThat(PeopleEntryTarget.parse(PeopleEntryTarget.NOT_COMPUTED)).isEqualTo(PeopleEntryTarget.Settings)
      computes.value = true
      codes("di nuovo il primo calcolo") { it == listOf(PeopleEntryTarget.PROGRESS) }
    } finally {
      scope.cancel()
    }
  }

  /** Persone attive sul telefono e sul tablet, calcolate, con un nome: la base di "Disattiva e cancella". */
  private suspend fun running(): Scene {
    val s = scene()
    val state = activate(s.phone) as FacesState.On
    s.phone.round()
    roundsUntil(s.rig, "il tablet vede l'avviso", s.phone, s.tablet) { s.tablet.live().switch.currentState() is FacesState.AwaitingNotice }
    compute(s.phone)
    s.phone.faces.nightly(30.seconds)
    roundsUntil(s.rig, "i dati dei volti sono sul NAS", s.phone) { facesFiles(TestNas.ADMIN_USER).isNotEmpty() }
    s.tablet.live().switch.acceptNotice(FacesSwitch.CURRENT_TEXT_VERSION)
    assertThat(s.tablet.live().switch.currentState()).isEqualTo(FacesState.On(state.generation, HereState.ACTIVE))
    roundsUntil(s.rig, "il tablet riceve le persone", s.phone, s.tablet) { s.tablet.live().people.others().first().people.size == 2 }
    val first = waitOthers(s.phone, 2).first().id
    assertThat(s.phone.live().people.rename(first, NAME_ONE)).isEqualTo(dev.pampa.mycloudgallery.core.faces.contract.EditOutcome.Done)
    roundsUntil(s.rig, "il nome arriva sul tablet", s.phone, s.tablet) { namedCards(s.tablet).any { it.name == NAME_ONE } }
    return s
  }

  /**
   * I6 (fase I) con la review CG-I1, sul grafo vero e con ritagli veri: lo svuotamento dei ritagli per memoria non revoca i volti delle Raccolte; un'uscita
   * e un rientro di corsa con lo svuotamento in mezzo non fanno perdere il ritorno (CG-I1-01); ad app nascosta il ritaglio aspetta il primo piano e poi
   * si fa una volta; con poca memoria in primo piano niente attesa ne' revoca; Persone spenta (cancellazione) mentre un ritaglio aspetta: al ritorno
   * niente volto, ne' per chi aspettava ne' per chi chiede dopo.
   */
  @Test
  fun i6_svuotamentoPerMemoria_ritorno_attesa_ePersoneSpenta() = runBlocking<Unit> {
    // La scena delle altre prove: persone calcolate, raggruppate e una con il nome.
    val s = running()
    val node = s.phone
    val live = node.live()
    val face = checkNotNull(namedCards(node).first().cover)
    val app = node.device.graph.appContext
    val collection = AppCollectionFaceCrops(node.faces)
    val px = 96

    // 1. In primo piano, un ritaglio vero: la cache si riempie.
    app.update(inForeground = true, profileActive = true)
    assertThat(withTimeout(Attese.TETTO) { collection.crop(face, px) }).isNotNull()
    val token = withTimeout(Attese.TETTO) { collection.revocations.first() }
    val first = live.crops.produced
    assertThat(first).isGreaterThan(0L)
    assertThat(live.crops.cachedBytes).isGreaterThan(0L)

    // 2. CG-I1-01: fuori e dentro di corsa, lo svuotamento in mezzo. Nessuna revoca, e il ritorno non si perde: il ritaglio si rifa' e finisce.
    app.update(inForeground = false, profileActive = true)
    node.faces.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    app.update(inForeground = true, profileActive = true)
    assertThat(live.crops.cachedBytes).isEqualTo(0L)
    assertThat(withTimeout(Attese.TETTO) { collection.revocations.first() }).isEqualTo(token)
    assertThat(withTimeout(Attese.TETTO) { collection.crop(face, px) }).isNotNull()
    assertThat(live.crops.produced).isEqualTo(first + 1)

    // 3. Ad app nascosta: lo svuotamento non revoca e il ritaglio aspetta il primo piano; poi si fa, una volta.
    app.update(inForeground = false, profileActive = true)
    node.faces.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    val pending = async(Dispatchers.Default) { collection.crop(face, px) }
    assertThat(withTimeoutOrNull(HIDDEN_WAIT) { pending.await() }).isNull()
    assertThat(live.crops.produced).isEqualTo(first + 1)
    app.update(inForeground = true, profileActive = true)
    assertThat(withTimeout(Attese.TETTO) { pending.await() }).isNotNull()
    assertThat(live.crops.produced).isEqualTo(first + 2)
    assertThat(withTimeout(Attese.TETTO) { collection.revocations.first() }).isEqualTo(token)

    // 4. Poca memoria in primo piano: svuota, nessuna attesa, nessuna revoca.
    node.faces.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    assertThat(withTimeout(Attese.TETTO) { collection.crop(face, px) }).isNotNull()
    assertThat(live.crops.produced).isEqualTo(first + 3)
    assertThat(withTimeout(Attese.TETTO) { collection.revocations.first() }).isEqualTo(token)

    // 5. Un ritaglio aspetta ad app nascosta; intanto Persone si spegne (cancellazione). Al ritorno: niente volto, per nessuno.
    app.update(inForeground = false, profileActive = true)
    node.faces.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    val waiting = async(Dispatchers.Default) { collection.crop(face, px) }
    assertThat(withTimeoutOrNull(HIDDEN_WAIT) { waiting.await() }).isNull()
    val preview = live.eraser.preview()
    assertThat(live.eraser.erase(FacesEraseConfirmation(preview.digest, nowNanos()), PeopleAlbumsChoice.KEEP_PHOTOS)).isEqualTo(EraseStart.Started)
    withTimeout(Attese.TETTO) {
      while (live.switch.currentState() != FacesState.Off) {
        node.round()
        node.faces.eraseRound(30.seconds)
        s.rig.time.advance(6.minutes.inWholeMilliseconds)
      }
    }
    app.update(inForeground = true, profileActive = true)
    assertWithMessage("chi aspettava non riceve il volto").that(withTimeout(Attese.TETTO) { waiting.await() }).isNull()
    assertWithMessage("chi chiede dopo non lo riceve").that(withTimeout(Attese.TETTO) { collection.crop(face, px) }).isNull()
    assertThat(withTimeout(Attese.TETTO) { collection.revocations.first() }).isNotEqualTo(token)
  }

  @Test
  fun disattivaECancellaConUnDispositivoSpentoCheTorna() = runBlocking<Unit> {
    val s = running()
    val rig = s.rig
    val generation = state(s.phone)
    val photoBytes = (1..s.photos).associate { n -> "Foto/Prova/IMG_%04d.jpg".format(n) to checkNotNull(rig.backend.bytes(TestNas.ADMIN_USER, "Foto/Prova/IMG_%04d.jpg".format(n))) }
    val commonBefore = rig.backend.fileCount(TestNas.COMMON)
    val otherBefore = rig.backend.fileCount(TestNas.MEMBER_USER)
    assertThat(s.tablet.databases()).isNotEmpty()

    // Il tablet e' spento (non gira piu' nessun suo lavoro). Il telefono cancella: foglio, conferma, "Tieni le foto".
    val live = s.phone.live()
    val preview = live.eraser.preview()
    assertThat(preview.namedPeople).isEqualTo(1)
    assertThat(preview.devices.count { !it.thisDevice }).isEqualTo(1)
    val start = live.eraser.erase(FacesEraseConfirmation(preview.digest, nowNanos()), PeopleAlbumsChoice.KEEP_PHOTOS)
    assertThat(start).isEqualTo(EraseStart.Started)

    // Locale subito: la chiave per prima, il database poi, i motori fermi. Sul NAS i file spariscono; aspetta il tablet.
    try {
      withTimeout(Attese.TETTO) {
        while (true) {
          s.phone.round()
          val status = checkNotNull(live.eraser.status.value)
          if (status.waiting == EraseWait.DEVICES) break
          s.phone.faces.eraseRound(30.seconds)
          rig.time.advance(6.minutes.inWholeMilliseconds)
        }
      }
    } catch (e: TimeoutCancellationException) {
      throw AssertionError("La cancellazione non e' arrivata ad aspettare i dispositivi (stato ${live.eraser.status.value?.step}, attesa ${live.eraser.status.value?.waiting})", e)
    }
    assertThat(s.phone.databases()).isEmpty()
    assertThat(s.phone.keys.aliases()).isEmpty()
    // Finita la parte locale il segno `erasing` si toglie e la voce dice `off`: lo stato e' spento, il diario dice che qui e' fatto e che aspetta gli altri.
    assertThat(live.switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(checkNotNull(live.eraser.status.value).local).isEqualTo(StepState.DONE)
    // Sul NAS l'intera generazione e' stata tolta (anche i file del tablet spento): resta solo da aspettare che il tablet lo sappia.
    assertThat(facesFiles(TestNas.ADMIN_USER)).isEmpty()
    // Il tablet spento ha ancora tutto: nessuno ha toccato il suo disco.
    assertThat(s.tablet.databases()).isNotEmpty()
    assertThat(s.tablet.keys.aliases()).hasSize(1)

    // Il tablet torna: il suo primo giro legge `off`, cancella da solo (prima la chiave, poi i file), e lo dice.
    roundsUntil(rig, "il tablet cancella da solo", s.tablet) { s.tablet.databases().isEmpty() && s.tablet.keys.aliases().isEmpty() }
    assertThat(s.tablet.live().switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(s.tablet.engines.detectCalls.get()).isEqualTo(0)

    // Il telefono vede la conferma e chiude: ovunque fatto, nessun file dei volti sul NAS, nessuna foto toccata.
    // CE-2 (correzioni A): qui non si chiama piu' `eraseRound`. Dopo la parte locale lo stato e' `Off` e l'attesa dei dispositivi la riprende il giro dell'indice
    // (`syncRound`): senza, le conferme non si rileggevano fino al processo dopo e la pagina restava "in attesa" per sempre.
    roundsUntil(rig, "la cancellazione e' finita", s.phone, s.tablet) {
      live.eraser.status.value?.step == EraseStep.DONE
    }
    assertThat(facesFiles(TestNas.ADMIN_USER)).isEmpty()
    assertThat(s.phone.live().switch.currentState()).isEqualTo(FacesState.Off)
    for ((path, bytes) in photoBytes) assertThat(rig.backend.bytes(TestNas.ADMIN_USER, path)).isEqualTo(bytes)
    assertThat(rig.backend.fileCount(TestNas.COMMON)).isEqualTo(commonBefore)
    // L'altro account non ha mai saputo niente, e non ha perso niente.
    s.other.round()
    assertThat(rig.backend.fileCount(TestNas.MEMBER_USER)).isEqualTo(otherBefore)
    assertThat(s.other.live().switch.currentState()).isEqualTo(FacesState.Off)

    // "Attiva" di nuovo = una generazione nuova, nessun dato vecchio.
    val again = activate(s.phone) as FacesState.On
    assertThat(again.generation).isNotEqualTo(generation)
    assertThat(s.phone.live().people.list.value.named).isEmpty()
  }

  /**
   * Un dispositivo segnato perso dall'admin si azzera da solo (E-S2, C15) e **con lui le chiavi dei volti** (E-F35, D14): la fase delle credenziali di
   * `DeviceSelfWipe` le distrugge prima di qualunque dato; poi i database e i file. Il telefono e il NAS non cambiano.
   */
  @Test
  fun unDispositivoPersoSiAzzeraEPerdeLeChiaviDeiVolti() = runBlocking<Unit> {
    val s = running()
    val lost = s.tablet
    assertThat(lost.keys.aliases()).hasSize(1)
    assertThat(lost.databases()).isNotEmpty()
    val lostId = checkNotNull(lost.device.graph.engine.myDeviceId())

    val admin = s.phone.device.graph
    admin.registry.refresh(dev.pampa.mycloudgallery.core.nas.contract.Purpose.INTERACTIVE)
    assertThat(admin.registry.changeStatus(lostId, DeviceStatus.LOST)).isEqualTo(AdminActionResult.Done)
    // Il registro nuovo (con il dispositivo perso) e' sul NAS: da qui in poi l'azzeramento del tablet non deve cambiare niente.
    val nasBefore = nasState()
    s.rig.time.advance(PREFLIGHT_INTERVAL)
    assertThat(TrustSyncRun(lost.device.graph, notifications = null, notified = null).execute()).isEqualTo(TrustSyncOutcome.WIPE_STARTED)

    assertThat(lost.device.graph.selfWipe.progress.value).isEqualTo(WipePhase.DONE)
    assertThat(lost.keys.aliases()).isEmpty()
    assertThat(lost.databases()).isEmpty()
    for (name in dev.pampa.mycloudgallery.faces.FacesWiring.FILES) assertWithMessage(name).that(File(lost.noBackup, name).exists()).isFalse()
    assertThat(s.phone.keys.aliases()).hasSize(1)
    assertThat(s.phone.databases()).isNotEmpty()
    // L'azzeramento del dispositivo perso e' locale: sul NAS non cambia niente (i suoi file li toglie la pulizia di un altro dispositivo dell'account).
    assertThat(nasState()).isEqualTo(nasBefore)
  }

  /** Ogni file di ogni area del NAS di prova con l'impronta dei suoi byte: l'azzeramento di un dispositivo non lo cambia. */
  private fun nasState(): Map<String, Int> = buildMap {
    val rig = checkNotNull(rig)
    for (share in listOf(TestNas.ADMIN_USER, TestNas.MEMBER_USER, TestNas.COMMON)) {
      for (path in filesUnder(share, "")) put("$share/$path", checkNotNull(rig.backend.bytes(share, path)).contentHashCode())
    }
  }

  @Test
  fun azzeramentoCompletoFermaLePersonePerPrimeEToglieTuttoSenzaToccareIlNas() = runBlocking<Unit> {
    val s = running()
    val graph = s.phone.device.graph
    assertThat(s.phone.databases()).isNotEmpty()
    assertThat(s.phone.keys.aliases()).hasSize(1)
    assertThat(File(s.phone.noBackup, dev.pampa.mycloudgallery.faces.FacesWiring.HEARTBEAT).exists() || File(s.phone.noBackup, "faces-local").exists()).isTrue()
    val nasBefore = nasState()
    assertThat(nasBefore.keys.any { it.contains(".mycloudgallery/faces/") }).isTrue()

    // L'ordine: le persone per prime (motori fermi, niente si apre piu'), poi la galleria, poi chiavi e file.
    val order = java.util.concurrent.CopyOnWriteArrayList<String>()
    val facesStopper = checkNotNull(graph.facesStopper)
    graph.facesStopper = dev.pampa.mycloudgallery.graph.GalleryStopper {
      order += "volti"
      facesStopper.stop()
      order += "volti fermi"
    }
    graph.galleryStopper = dev.pampa.mycloudgallery.graph.GalleryStopper { order += "galleria" }

    assertThat(graph.eraser.eraseAll()).isTrue()

    assertThat(order.first()).isEqualTo("volti")
    assertThat(order.indexOf("volti fermi")).isLessThan(order.indexOf("galleria"))
    assertThat(s.phone.databases()).isEmpty()
    assertThat(File(s.phone.noBackup, "faces").exists()).isFalse()
    assertThat(s.phone.keys.aliases()).isEmpty()
    for (name in dev.pampa.mycloudgallery.faces.FacesWiring.FILES) assertWithMessage(name).that(File(s.phone.noBackup, name).exists()).isFalse()
    // Il NAS non si tocca: l'azzeramento di un dispositivo e' locale (la cancellazione sul NAS e' "Disattiva e cancella"); gli altri dispositivi non perdono niente.
    assertThat(nasState()).isEqualTo(nasBefore)
    assertThat(s.tablet.databases()).isNotEmpty()
    assertThat(s.tablet.keys.aliases()).hasSize(1)
  }

  private suspend fun state(node: FacesNode) = checkNotNull(node.live().switch.currentState().generationOrNull)

  private companion object {
    /** I6: quanto si guarda che un ritaglio ad app nascosta resti fermo (la prova deterministica dell'attesa sta in `GuardedPeopleCropsTest`). */
    private const val HIDDEN_WAIT = 500L

    /** La guardia rilegge il registro al massimo ogni 15 minuti. */
    const val PREFLIGHT_INTERVAL = 16 * 60 * 1000L

    /** I volti che le sei foto di prova portano: l'identita' 0 in tutte, la 1 in cinque. */
    const val FACES_EXPECTED = 11
    const val NAME_ONE = "Persona di prova uno"
    const val NAME_TWO = "Persona di prova due"
  }
}
