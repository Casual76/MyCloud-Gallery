package dev.pampa.mycloudgallery.core.ai.testing

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidates
import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.ai.contract.AiRoles
import dev.pampa.mycloudgallery.core.ai.contract.DeviceProbe
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

// I fake delle porte del calcolo e della lettura del testo (Fase 6, P0): in src/main per i test di P5, P8, P10 e P11. Mai nella
// release (tools/check.ps1).

/** Il buffer in memoria con il tetto dei byte e lo "spazio libero" scelto dal test (D10, E-A17). */
class FakeAiBuffer(var maxBytes: Long = AiBuffer.MAX_BYTES, var freeBytes: () -> Long = { Long.MAX_VALUE }) : AiBuffer {
  private val blobs = LinkedHashMap<Pair<ContentKey, SegmentClass>, ByteArray>()
  override val usedBytes: MutableStateFlow<Long> = MutableStateFlow(0L)
  val evicted: MutableList<ContentKey> = mutableListOf()

  override suspend fun put(key: ContentKey, cls: SegmentClass, bytes: ByteArray): Boolean = synchronized(this) {
    if (freeBytes() < AiBuffer.MIN_FREE_BYTES) return false
    val old = blobs[key to cls]?.size ?: 0
    if (usedBytes.value - old + bytes.size > maxBytes) return false
    blobs[key to cls] = bytes.copyOf()
    usedBytes.value = usedBytes.value - old + bytes.size
    true
  }

  override suspend fun get(key: ContentKey, cls: SegmentClass): ByteArray? = synchronized(this) { blobs[key to cls]?.copyOf() }

  override suspend fun evict(done: Collection<ContentKey>) {
    synchronized(this) {
      val gone = blobs.keys.filter { it.first in done }
      gone.forEach { usedBytes.value -= blobs.remove(it)?.size ?: 0 }
      evicted += done
    }
  }

  fun contains(key: ContentKey, cls: SegmentClass): Boolean = synchronized(this) { (key to cls) in blobs }
}

/**
 * I risultati salvati in memoria, per chiave di contenuto (D22, D23). [saves] registra l'ordine delle scritture: i test di ripresa e
 * delle interruzioni (P10) lo confrontano con le pubblicazioni ("salvare prima di pubblicare").
 */
class FakeAiResults : AiResults {
  val vectors: MutableMap<ContentKey, VecBody> = LinkedHashMap()
  val texts: MutableMap<ContentKey, OcrBody> = LinkedHashMap()
  val saves: MutableList<Pair<ContentKey, AiClass>> = mutableListOf()

  /** Se impostato, la prossima scrittura muore (simula la morte del processo durante il salvataggio). */
  var dieOnNextSave: Boolean = false

  override suspend fun saveVectors(key: ContentKey, body: VecBody) {
    dieIfAsked()
    synchronized(this) {
      vectors[key] = body
      saves += key to AiClass.VEC
    }
  }

  override suspend fun saveText(key: ContentKey, body: OcrBody) {
    dieIfAsked()
    synchronized(this) {
      texts[key] = body
      saves += key to AiClass.OCR
    }
  }

  override suspend fun saved(key: ContentKey, recipe: Int): Set<AiClass> = synchronized(this) {
    buildSet {
      if ((vectors[key]?.recipe ?: 0) >= recipe) add(AiClass.VEC)
      if ((texts[key]?.recipe ?: 0) >= recipe) add(AiClass.OCR)
    }
  }

  private fun dieIfAsked() {
    if (dieOnNextSave) {
      dieOnNextSave = false
      throw ProcessDeath()
    }
  }
}

/** La morte simulata del processo (non un'eccezione "normale": i test la lasciano risalire). */
class ProcessDeath : RuntimeException("morte simulata del processo")

/** I media da calcolare, scelti dal test per share e per il telefono, nell'ordine dato (dal piu' recente). */
class FakeAiCandidates : AiCandidates {
  private val byShare = LinkedHashMap<String, MutableList<AiCandidate>>()
  val phone: MutableList<AiCandidate> = mutableListOf()

  fun add(share: String, candidate: AiCandidate) {
    byShare.getOrPut(share.lowercase(Locale.ROOT)) { mutableListOf() } += candidate
  }

  override suspend fun forShare(share: String, recipe: Int, limit: Int): List<AiCandidate> =
    byShare[share.lowercase(Locale.ROOT)].orEmpty().take(limit)

  override suspend fun phoneOnly(recipe: Int, limit: Int): List<AiCandidate> = phone.take(limit)
}

/** Ruolo, lettura del testo e aree scelti dal test (D14, D15, D41). */
class FakeAiRoles(
  indexer: Boolean = true,
  textReadingHere: Boolean = false,
  var shares: List<String> = emptyList(),
) : AiRoles {
  override val indexer: MutableStateFlow<Boolean> = MutableStateFlow(indexer)
  override val textReadingHere: MutableStateFlow<Boolean> = MutableStateFlow(textReadingHere)

  override suspend fun shares(): List<String> = shares
}

/**
 * L'interruttore della lettura del testo in memoria (D41). Registra le pulizie allo spegnimento ([cleanups]: annullare i lavori di
 * DataTransport) come fara' quello vero; un consenso con una versione del testo vecchia non accende niente.
 */
class FakeTextReadingSwitch(initial: TextReadingState = TextReadingState.Off) : TextReadingSwitch {
  override val state: MutableStateFlow<TextReadingState> = MutableStateFlow(initial)
  override val excludedHere: MutableStateFlow<Boolean> = MutableStateFlow(initial is TextReadingState.OnForAccount && !initial.thisDevice)
  override val syncPending: MutableStateFlow<Boolean> = MutableStateFlow(false)
  var cleanups: Int = 0
    private set
  val consents: MutableList<TextReadingConsent> = mutableListOf()

  override suspend fun enableForAccount(consent: TextReadingConsent) {
    if (consent.textVersion < TextReadingSwitch.CURRENT_TEXT_VERSION) return
    consents += consent
    state.value = TextReadingState.OnForAccount(thisDevice = true)
  }

  override suspend fun excludeThisDevice() {
    // L'esclusione vale anche da spenta (R8): si da' prima, e se poi la lettura si accende altrove qui non parte.
    excludedHere.value = true
    if (state.value is TextReadingState.OnForAccount || state.value is TextReadingState.AwaitingNotice) {
      state.value = TextReadingState.OnForAccount(thisDevice = false)
      cleanups++
    }
  }

  override suspend fun includeThisDevice() {
    excludedHere.value = false
    if (state.value is TextReadingState.OnForAccount) state.value = TextReadingState.OnForAccount(thisDevice = true)
  }

  override suspend fun disable() {
    if (state.value == TextReadingState.Off) return
    state.value = TextReadingState.Disabling
    cleanups++
    state.value = TextReadingState.Off
  }
}

/** La misura breve finta (D15). */
class FakeDeviceProbe(var result: ProbeResult = ProbeResult(imagesPerSecond = 2.8, backend = AiBackend.GPU, coldStartMs = 3600)) : DeviceProbe {
  var measures: Int = 0
    private set

  override suspend fun measure(): ProbeResult {
    measures++
    return result
  }
}
