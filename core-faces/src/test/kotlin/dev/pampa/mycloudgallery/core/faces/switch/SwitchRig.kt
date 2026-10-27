package dev.pampa.mycloudgallery.core.faces.switch

import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocalStore
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesClock
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesRequirements
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesLocalStore
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.FacesRegister
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryFacesPersonalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * La cancellazione locale a mano: registra le richieste, puo' fallire (risponde falso o lancia) e tiene l'elenco dei dati che "esistono" su
 * questo dispositivo ([data]), cosi' i test guardano cosa resta e non solo cosa e' stato chiesto.
 */
internal class RecordingEraser(val data: MutableSet<FaceGeneration> = mutableSetOf()) : FacesLocalDataEraser {
  val calls: MutableList<Pair<FaceGeneration, LocalEraseWhy>> = mutableListOf()

  /** Falso = "qualcosa e' rimasto" (non toglie niente). */
  var succeeds: Boolean = true

  /** Se non nullo, lo lancia a ogni richiesta. */
  var throws: Throwable? = null

  override suspend fun erase(generation: FaceGeneration, why: LocalEraseWhy): Boolean {
    calls += generation to why
    throws?.let { throw it }
    if (!succeeds) return false
    data -= generation
    return true
  }

  val erased: List<FaceGeneration> get() = calls.map { it.first }
}

/**
 * Un dispositivo finto con l'interruttore VERO ([DefaultFacesSwitch]) sopra i fake delle porte: il registro dei dati personali condiviso
 * ([register]), lo stato locale in memoria, i requisiti a mano, la cancellazione registrata e l'orologio. Un riavvio del processo si prova
 * costruendone un altro con lo stesso [account], [localStore] ed [eraser].
 */
internal class SwitchRig(
  val register: FacesRegister = FacesRegister(),
  val name: String = "A",
  val clock: FakeFacesClock = FakeFacesClock(),
  val requirements: FakeFacesRequirements = FakeFacesRequirements(),
  val localStore: FacesLocalStore = InMemoryFacesLocalStore(),
  val eraser: RecordingEraser = RecordingEraser(),
  val account: InMemoryFacesPersonalSettings = InMemoryFacesPersonalSettings(register, name),
  /** Cio' che l'interruttore vede della voce: di serie [account]; un guasto lo mette in mezzo con un avvolgitore. */
  port: FacesPersonalSettings = account,
  /** Come [port], per i requisiti: di serie [requirements]. */
  requirementsPort: FacesRequirements = requirements,
  val textVersion: Int = FacesSwitch.CURRENT_TEXT_VERSION,
  seed: Int = 1,
) {
  val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + Job())
  private var counter = seed * 1_000

  val switch = DefaultFacesSwitch(
    scope = scope,
    account = port,
    localStore = localStore,
    requirements = requirementsPort,
    eraser = eraser,
    clock = clock,
    textVersion = textVersion,
    newGeneration = { generation(++counter) },
  )

  /** Le generazioni che questo dispositivo genera: la prima e' `generation(seed * 1000 + 1)`. */
  fun nextGeneration(): FaceGeneration = generation(counter + 1)

  fun consent(version: Int = textVersion): FacesActivationConsent = FacesActivationConsent(version, clock.monotonicNanos())

  val local: FacesLocal get() = localStore.local.value

  /** Un'area privata dichiarata e un indicizzatore: i requisiti di D5 soddisfatti (il predefinito dei fake). */
  fun requirementsMet() {
    requirements.privateArea = PrivateAreaState.Declared("test")
    requirements.indexers = IndexerState(accountIndexers = 1, thisDevice = true, canBecomeIndexer = true)
  }

  fun close() {
    scope.cancel()
  }

  companion object {
    fun generation(n: Int): FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { i -> ((n shr (8 * (i % 4))) + i).toByte() })
  }
}
