package dev.pampa.mycloudgallery.integration

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.albums.AlbumsWiring
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.testing.FakeFaceEngines
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticFace
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticScene
import dev.pampa.mycloudgallery.core.faces.contract.FaceInput
import dev.pampa.mycloudgallery.core.faces.contract.FaceInputs
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepControl
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.faces.FacesLive
import dev.pampa.mycloudgallery.faces.FacesWiring
import dev.pampa.mycloudgallery.faces.PeopleBridge
import dev.pampa.mycloudgallery.graph.GalleryStopper
import dev.pampa.mycloudgallery.nas.NasGraph
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// L'ATTREZZATURA DELLE PROVE D'INTEGRAZIONE DELLE PERSONE (Fase 8, P13): il grafo vero dell'app (galleria, Fase 6, album, persone) su ogni dispositivo, sul NAS
// simulato, con i soli motori dei volti finti (`FakeFaceEngines`) e i loro ingressi (le scene sintetiche: nessun volto, nessuna immagine, solo numeri). Tutto il resto
// e' quello di produzione: `FacesWiring.build`, recinto, cancello D34, archivio cifrato (con le chiavi in memoria della JVM), replica, pubblicatore, custode, cancellazione.

/** Il giorno in cui "oggi" per gli album e la ricerca. */
internal val FACES_TODAY = Today { LocalDate.of(2026, 10, 27) }

/**
 * Le scene sintetiche del NAS di prova: ogni contenuto nuovo (per chiave) prende la scena dopo. La identita' 0 compare in tutte, la 1 in tutte tranne ogni sesta:
 * con sei foto sono due persone che superano le soglie di nascita (3 volti in 2 foto), senza che nessuno sia mai un volto vero.
 */
internal class SceneBook(private val noise: Float = 0.4f) {
  private val scenes = HashMap<ContentKey, SyntheticScene>()
  private var next = 0

  @Synchronized
  fun sceneOf(item: FacesItem): SyntheticScene = scenes.getOrPut(item.contentKey) { make(next++) }

  @Synchronized
  fun count(): Int = scenes.size

  private fun make(n: Int): SyntheticScene {
    val faces = buildList {
      add(SyntheticFace(identity = 0, x0 = 0.10f, y0 = 0.20f, x1 = 0.40f, y1 = 0.70f, noise = noise))
      if (n % 6 != 5) add(SyntheticFace(identity = 1, x0 = 0.55f, y0 = 0.20f, x1 = 0.85f, y1 = 0.70f, noise = noise))
    }
    return SyntheticScene(id = n, width = 1024, height = 768, faces = faces)
  }
}

/** Gli ingressi del calcolo: la scena del contenuto, vista dal motore finto come anteprima di analisi (e HQ per la seconda passata, che qui non serve). */
internal class SceneInputs(private val engines: FakeFaceEngines, private val book: SceneBook) : FaceInputs {
  override suspend fun preview(item: FacesItem): FaceInput = FaceInput.Ready(engines.image(book.sceneOf(item)), FaceSource.ANALYSIS)

  override suspend fun hq(item: FacesItem): FaceInput = FaceInput.Ready(engines.image(book.sceneOf(item), longSide = 2048), FaceSource.HQ_TILES)

  override suspend fun frames(item: FacesItem): List<FaceInput> = emptyList()

  override suspend fun sharpest(item: FacesItem): FaceInput = preview(item)
}

/** Un dispositivo con le persone montate sul grafo vero. */
internal class FacesNode(
  val name: String,
  val mounted: AiRig.Mounted,
  val albums: AlbumsGraph,
  val bridge: PeopleBridge,
  val faces: FacesGraph,
  val engines: FakeFaceEngines,
  val book: SceneBook,
) {
  val device get() = mounted.test
  val nas: NasGraph get() = checkNotNull(mounted.gallery.nas)
  val noBackup: File get() = device.context.noBackupFilesDir

  /** Le chiavi dei volti di questo dispositivo (gli alias `mcg-faces-<G>`). */
  val keys get() = device.platform.faceKeys

  suspend fun live(): FacesLive = checkNotNull(faces.ensureLive()) { "$name: persone non montate" }

  /** I file `faces-*.db` (e compagni) che il dispositivo ha sul disco. */
  fun databases(): List<File> = File(noBackup, "faces").listFiles { f -> f.name.startsWith("faces-") }.orEmpty().toList()

  /** Lo stato del dispositivo in numeri e codici, per il messaggio di un'attesa scaduta (mai nomi, id o generazioni). */
  suspend fun describe(): String {
    val live = live()
    return "stato=${live.switch.currentState()} fresca=${live.switch.fresh.value} calcolo=${live.status.value} custode=${live.grouping.holder.value} " +
      "persone=${live.people.list.value} altri=${live.people.others().first()} replica=${faces.lastSync.value} pulizia=${faces.lastJanitor.value} " +
      "errori=${faces.roundErrors} fermo=${faces.holdCode()} salvati=${live.counters.facesSaved.get()} archivio=${archiveNumbers(live)}"
  }

  /** Quanti volti e quante persone ha l'archivio aperto (solo numeri). */
  private suspend fun archiveNumbers(live: FacesLive): String = try {
    val archive = live.archives.opened()
    if (archive == null) "chiuso" else "volti=${archive.groupFaces().size} persone=${archive.grouping()?.persons?.size}"
  } catch (e: Exception) {
    "illeggibile (${e.javaClass.simpleName})"
  }

  /** Un giro come il lavoro dell'indice: i dati personali (la generazione e' fresca), poi i volti (replica, pubblicazione, pulizia del NAS). */
  suspend fun round() {
    nas.organizer.syncPersonal(30.seconds)
    faces.syncRound(30.seconds)
  }
}

internal class FacesRig(private val app: Application, root: File) {
  val ai = AiRig(app, root)
  val time get() = ai.time
  val backend get() = ai.backend
  private val albumGraphs = mutableListOf<AlbumsGraph>()
  private val faceGraphs = mutableListOf<FacesGraph>()

  /** Il dispositivo [test] con la galleria avviata, il NAS agganciato e le persone costruite (non ancora attive: Off). */
  suspend fun node(name: String, test: TestDevice, account: String): FacesNode {
    val bridge = PeopleBridge()
    val mounted = ai.startMounted(ai.mount(test, account, people = bridge))
    test.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val albums = AlbumsWiring.build(test.context, test.graph, mounted.gallery, search = null, today = FACES_TODAY, people = bridge).also { albumGraphs += it }
    val engines = FakeFaceEngines()
    val book = SceneBook()
    val testing = FacesWiring.Testing(
      engines = { engines.engines },
      inputs = { SceneInputs(engines, book) },
      modelsReady = { true },
    )
    val faces = FacesWiring.build(test.context, test.graph, mounted.gallery, mounted.stack, albums, bridge, testing).also { faceGraphs += it }
    // Come `FacesModule`: all'azzeramento le persone si fermano per prime.
    test.graph.facesStopper = GalleryStopper {
      bridge.unbind()
      faces.stop()
    }
    return FacesNode(name, mounted, albums, bridge, faces, engines, book)
  }

  /** Le foto di prova nell'area [share] del NAS, con byte tutti diversi (la chiave di contenuto e' per impronta). */
  fun putPhotos(share: String, count: Int, folder: String = "Foto/Prova") {
    repeat(count) { n ->
      val bytes = AiRig.jpeg(tone = 20 + n * 30) + ByteArray(64 * (n + 1)) { (n * 17 + it).toByte() }
      backend.put(share, "$folder/IMG_%04d.jpg".format(n + 1), bytes, lastWriteMs = time.now() - 2 * AiRig.DAY)
    }
  }

  /** La scansione, la preparazione delle anteprime e l'uscita dell'indice: il catalogo del dispositivo conosce le foto e le loro chiavi di contenuto. */
  suspend fun catalogue(node: FacesNode, expected: Int) {
    val nas = node.nas
    checkNotNull(nas.scan(ScanMode.Full, 5.minutes))
    node.device.graph.trustGuard.preflight(Purpose.BACKGROUND)
    checkNotNull(nas.prepare(5.minutes, PrepControl.ALWAYS))
    nas.flushOutbox()
    withTimeout(Attese.TETTO) { node.mounted.gallery.repository.snapshot.first { (it?.size ?: 0) == expected } }
  }

  /** Il familiare che entra: stessa famiglia, account [user] con la sua password, approvato dall'admin. */
  suspend fun joinDevice(admin: TestDevice, name: String, user: String, password: String): TestDevice {
    val test = ai.device(name)
    val invite = admin.graph.registry.createInvite(
      dev.pampa.mycloudgallery.core.family.contract.InviteParams(user, "Dispositivo di prova $name", user, presetName = null),
    )
    test.graph.start()
    val awaiting = test.graph.joinAs(invite, password)
    admin.graph.registry.refresh(Purpose.INTERACTIVE)
    val request = admin.graph.registry.pendingRequests.value.single()
    admin.graph.registry.approve(request.id, dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof.TypedCode(awaiting.code))
    test.graph.approvalWatcher.watch().first { it !is dev.pampa.mycloudgallery.core.family.contract.TrustStatus.Pending && it !is dev.pampa.mycloudgallery.core.family.contract.TrustStatus.Stale }
    test.graph.awaitSetup { it is dev.pampa.mycloudgallery.core.family.contract.SetupState.Ready }
    admin.graph.registry.refresh(Purpose.INTERACTIVE)
    return test
  }

  fun close() {
    runBlocking {
      faceGraphs.forEach { runCatching { it.stop() } }
      albumGraphs.forEach { runCatching { it.stop() } }
    }
    ai.close()
  }

  companion object {
    /** Il tetto di un'attesa: il lavoro e' sempre lo stesso, ma con piu' build sul PC diventa molto piu' lento (vedi [Attese]). */
    val TIMEOUT = Attese.TETTO
  }
}

/** Entra con l'invito sapendo la password dell'account [password] (di serie quella di `test2`): come `joinWith`, ma per qualunque account. */
internal suspend fun dev.pampa.mycloudgallery.graph.AppGraph.joinAs(
  invite: dev.pampa.mycloudgallery.core.family.contract.FamilyInvite,
  password: String,
): dev.pampa.mycloudgallery.core.family.contract.JoinStep.AwaitingApproval {
  awaitSetup { it == dev.pampa.mycloudgallery.core.family.contract.SetupState.NotConfigured }
  settle(coordinator.submit(dev.pampa.mycloudgallery.core.family.contract.StepInput.ChoosePath(dev.pampa.mycloudgallery.core.family.contract.OnboardingPath.JOIN_QR)))
  assertThat(joinFlow.start(invite)).isEqualTo(dev.pampa.mycloudgallery.core.family.contract.JoinStep.NeedsPassword)
  assertThat(joinFlow.authenticate(password.toCharArray())).isInstanceOf(dev.pampa.mycloudgallery.core.family.contract.JoinStep.NeedsName::class.java)
  val awaiting = joinFlow.register("Dispositivo di prova") as dev.pampa.mycloudgallery.core.family.contract.JoinStep.AwaitingApproval
  coordinator.progress.await { it.current == dev.pampa.mycloudgallery.core.family.contract.OnboardingStep.ROLES }
  localSteps(until = dev.pampa.mycloudgallery.core.family.contract.OnboardingStep.SUMMARY)
  settle(coordinator.submit(dev.pampa.mycloudgallery.core.family.contract.StepInput.Finish))
  awaitSetup { it is dev.pampa.mycloudgallery.core.family.contract.SetupState.AwaitingApproval }
  return awaiting
}
