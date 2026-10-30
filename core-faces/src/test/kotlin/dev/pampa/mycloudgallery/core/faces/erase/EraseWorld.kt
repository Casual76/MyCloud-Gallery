package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseCheck
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.faces.sync.FacesTestFamily
import dev.pampa.mycloudgallery.core.faces.sync.MemoryShare
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesClock
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.HeadValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalHeadsView
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Collections
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// IL MONDO DELLE PROVE DI "DISATTIVA E CANCELLA" (Fase 8, P8; docs/fase8-progetto.md §2.8, §5.5). Una famiglia vera (registro firmato) con un
// account e piu' dispositivi; l'area privata in memoria ([MemoryShare]: i file dei volti scritti dal pubblicatore VERO di P3, piu' media e altri
// file dell'app che non si devono mai toccare); i dati personali della voce `faces` con le stesse regole del motore vero (l'ultima scrittura vince,
// "da datare" che prende l'ora del NAS al giro, il gancio di D30 prima di ogni pubblicazione, le teste lette per intero ricordate); la
// cancellazione stretta sul NAS con la regola di D31 (come l'esecutore di riferimento di core-nasops, che core-faces non vede). Ogni dispositivo
// ha l'interruttore VERO (P7), la cancellazione VERA (P8), il recinto, il pubblicatore VERO (P3). Solo nomi inventati.

/** La morte del processo: un `Error`, cosi' nessun `catch` del codice di produzione la intercetta. */
internal class Death : Error("morte del processo")

/** Il mondo: orologio, NAS, famiglia, teste personali pubblicate, cestino di rete. */
internal class EraseWorld {
  val clock = FakeFacesClock()
  val family = FacesTestFamily(clock)
  val share = MemoryShare("test")

  /** Ogni scrittura riuscita dei pubblicatori: dispositivo e percorso. */
  val written: MutableList<Pair<String, SharePath>> = Collections.synchronizedList(mutableListOf())

  /** Ogni file tolto dalla cancellazione sul NAS: dispositivo che ha chiesto e percorso. */
  val deleted: MutableList<Pair<String, SharePath>> = Collections.synchronizedList(mutableListOf())

  /** Le teste personali pubblicate: per dispositivo la voce `faces` (null = la testa non la contiene) e l'ora del NAS. */
  val heads = LinkedHashMap<String, HeadValue>()

  @Volatile var frozen = false

  /** Il cestino di rete dell'area privata (E-F34): i file tolti ci restano finche' l'utente non lo svuota. */
  @Volatile var recycleBin = false
  val binCopies: MutableSet<SharePath> = Collections.synchronizedSet(LinkedHashSet())

  /** File tolti al piu' per giro dalla cancellazione sul NAS. */
  var maxFilesPerRound = 1_000

  /** R13: il NAS non lascia togliere i file (tenuti aperti da un altro, per esempio dal PC): ogni giro non toglie niente e dice "altro al giro dopo". */
  @Volatile var filesHeldOpen = false

  /** I file che non sono dati dei volti: media e altri file dell'app. Mai toccati. */
  val untouchable: List<SharePath> = listOf(
    SharePath.parse("Foto/2025/IMG_0001.jpg"),
    SharePath.parse("Foto/head/0000000001"),
    SharePath.parse(".mycloudgallery/devices/aaaa/personal/head/0000000001"),
    SharePath.parse(".mycloudgallery/faces-note.txt"),
  )

  init {
    untouchable.forEach { share.plant(it, "non toccare".toByteArray()) }
  }

  val devices = ArrayList<EraseDevice>()

  fun device(name: String): EraseDevice = EraseDevice(this, name, family.approve(name, "test")).also { devices += it }

  /** I file dei volti della generazione [generation] sul NAS. */
  fun facesFiles(generation: FaceGeneration): List<SharePath> = share.paths().filter { it.isWithin(NasLayout.facesGenerationDir(generation)) }

  fun allFacesFiles(): List<SharePath> = share.paths().filter { it.isWithin(NasLayout.FACES_DIR) }

  /** Le generazioni con almeno un file sul NAS. */
  fun generationsOnNas(): Set<FaceGeneration> =
    allFacesFiles().mapNotNull { if (it.components.size > 2) FaceGeneration.parse(it.components[2]) else null }.toSet()

  fun close() = devices.forEach { it.shutdown() }
}

/**
 * La voce `faces` dei dati personali di UN dispositivo, con le regole del motore vero (`NasPersonalSync` + `SyncedFacesSettings`): la scelta fatta qui
 * vale subito ("da datare"); al giro prende l'ora del NAS; l'ultima scrittura vince (ora del NAS, poi l'autore minore); prima di pubblicare la propria
 * testa si chiama il gancio; le teste degli altri lette al giro si ricordano ([view], la `PersonalHeadsView`). Tutto cio' e' "su disco": sopravvive
 * alla morte del processo.
 */
internal class WorldPersonal(private val world: EraseWorld, val deviceId: String) : FacesPersonalSettings {
  var dated: PersonalEntry? = null
  var undated: PersonalValue? = null
  var lastPublished: PersonalEntry? = null
  var everPublished = false
  val view = LinkedHashMap<String, HeadValue>()

  private val flow = MutableStateFlow(current())
  override val setting: StateFlow<FacesSetting?> = flow
  override val syncPending = MutableStateFlow(false)

  override fun current(): FacesSetting? = FacesSettingCodec.parse(undated ?: dated?.value)

  override suspend fun set(setting: FacesSetting): Boolean {
    if (current() == FacesSetting.Unknown) return false
    undated = FacesSettingCodec.valueOf(setting)
    emit()
    return true
  }

  fun emit() {
    flow.value = current()
    syncPending.value = undated != null
  }

  /** Una voce che arriva da fuori (un'app piu' nuova, un dispositivo compromesso): entra nello stato come una voce letta. */
  fun merge(entry: PersonalEntry?) {
    if (entry == null) return
    val mine = dated
    if (mine == null || entry.atNas > mine.atNas || entry.atNas == mine.atNas && entry.author < mine.author) dated = entry
  }

  val headsView: PersonalHeadsView = object : PersonalHeadsView {
    override suspend fun latestValueOf(key: PersonalKey): Map<String, HeadValue> = if (key == FacesSettingCodec.KEY) synchronized(view) { view.toMap() } else emptyMap()
  }
}

/**
 * La cancellazione stretta sul NAS vista da UN dispositivo (la porta `FacesEraseOps` legata da `:app` sopra `FacesNasEraser`): la regola di D31
 * con i fatti del dispositivo ([FacesAccountFacts]), il cancello del mondo, i giri a lotti. Registra ogni chiamata ([calls]) per la proprieta'
 * "si chiede solo cio' che la regola ammette".
 */
internal class WorldEraseOps(private val world: EraseWorld, private val device: EraseDevice) : FacesEraseOps {
  val calls: MutableList<Triple<FaceGeneration, String?, NasEraseReason>> = Collections.synchronizedList(mutableListOf())

  /** Le prossime N chiamate falliscono con un'eccezione (il NAS che cade). */
  var failNext = 0

  private fun fault() {
    if (failNext > 0) {
      failNext--
      throw java.io.IOException("guasto")
    }
  }

  override suspend fun generations(): List<FaceGeneration>? {
    fault()
    if (!device.online) return null
    return world.generationsOnNas().sortedBy { it.base32 }
  }

  override suspend fun eraseGeneration(generation: FaceGeneration, reason: NasEraseReason, budget: Duration): NasEraseRound {
    fault()
    calls += Triple(generation, null, reason)
    blocked()?.let { return it }
    if (reason == NasEraseReason.DEVICE_REVOKED) return NasEraseRound.NotAllowed
    when (val current = device.facts.current()) {
      FacesAccountFacts.Current.Unknown -> return NasEraseRound.Unsure
      is FacesAccountFacts.Current.Known -> if (current.generation == generation) return NasEraseRound.NotAllowed
    }
    return remove(world.facesFiles(generation))
  }

  override suspend fun eraseDevice(generation: FaceGeneration, deviceId: String, reason: NasEraseReason, budget: Duration): NasEraseRound {
    fault()
    calls += Triple(generation, deviceId, reason)
    blocked()?.let { return it }
    val current = device.facts.current() as? FacesAccountFacts.Current.Known ?: return NasEraseRound.Unsure
    val allowed = when (reason) {
      NasEraseReason.DEVICE_REVOKED -> device.facts.revokedOrLost(deviceId)
      NasEraseReason.NOT_CURRENT, NasEraseReason.USER_CONFIRMED -> current.generation != generation
    }
    if (!allowed) return NasEraseRound.NotAllowed
    return remove(world.share.paths().filter { it.isWithin(NasLayout.facesDeviceDir(generation, deviceId)) })
  }

  override suspend fun verify(generation: FaceGeneration): NasEraseCheck? {
    fault()
    if (!device.online) return null
    return NasEraseCheck(world.facesFiles(generation).isNotEmpty(), world.recycleBin && world.binCopies.isNotEmpty())
  }

  private fun blocked(): NasEraseRound? = when {
    !device.online -> NasEraseRound.Waiting(EraseWait.NAS)
    world.frozen -> NasEraseRound.Waiting(EraseWait.FROZEN)
    else -> null
  }

  private fun remove(files: List<SharePath>): NasEraseRound {
    if (world.filesHeldOpen && files.isNotEmpty()) return NasEraseRound.More(0, files.size)
    val batch = files.take(world.maxFilesPerRound)
    for (path in batch) {
      world.share.delete(path)
      world.deleted += device.id to path
      if (world.recycleBin) world.binCopies += path
    }
    return if (files.size > batch.size) NasEraseRound.More(batch.size, files.size - batch.size) else NasEraseRound.Done(batch.size)
  }
}
