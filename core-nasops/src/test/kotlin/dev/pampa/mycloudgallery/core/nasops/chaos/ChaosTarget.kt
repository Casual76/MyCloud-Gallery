package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.ServerCopyMode
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.testing.FakeOpClocks
import dev.pampa.mycloudgallery.core.nasops.testing.FakeOpsGate
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryNasOperations
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryNasTrash
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryOpsStore
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random

// Il banco gira contro due cose che non conosce: UN MONDO (il NAS, finto o vero, con le azioni "da fuori" che il banco fa per conto suo:
// il PC che crea un file, il tempo che passa) e UNO STACK (le operazioni e il cestino sotto prova, con il loro diario). Cosi' lo stesso
// banco gira sulle operazioni fake di P0-B, sulle operazioni vere di P12, sul NAS finto e sull'area di prova del NAS vero.

/** Come il banco sceglie la copia tra share: la copia lato server (di norma) o il ripiego dal telefono (E-D12). */
enum class CopyMode { SERVER, FALLBACK }

/**
 * Un mondo di prova per UN giro: share vuote, il NAS visto "da fuori" (azioni del PC, senza colpi) e dal codice sotto prova (tramite i
 * decoratori). Le cose che un NAS vero non permette (far passare 31 giorni, rifiutare la copia lato server) si chiedono con [capabilities].
 */
interface ChaosWorld : AutoCloseable {
  /** L'area privata e l'area comune di prova, in quest'ordine. */
  val shares: List<String>

  /** Il dispositivo che sta sotto prova (i suoi temporanei e il suo cestino). */
  val deviceId: String
  val accountId: String

  /** La lettura e le primitive di scrittura SENZA colpi: per caricare il mondo, osservarlo e verificarlo. Mai date al codice sotto prova. */
  val reader: NasMediaReader
  val fileOps: NasFileOps

  /** Gli orologi (monotono e ora del NAS) condivisi da tutte le incarnazioni del processo. */
  val clocks: OpClocks

  val capabilities: Capabilities

  /** Fa passare il tempo (solo con [Capabilities.advanceTime]). */
  fun advanceTime(ms: Long)

  fun setCopyMode(mode: CopyMode)

  /** Il PC (o un altro dispositivo) scrive un file nuovo, intero: nessun colpo. */
  suspend fun putFile(share: String, path: SharePath, bytes: ByteArray)

  /** La connessione e' caduta davvero (colpo `DROP`/`STALL`): sul NAS vero chiude la sessione, cosi' la chiamata dopo si riconnette. */
  suspend fun connectionLost()

  /** Una riga per il rapporto (mai nomi di file ne' credenziali). */
  fun describe(): String

  /** Cosa il mondo sa fare oltre a cio' che fa ogni NAS. */
  class Capabilities(val advanceTime: Boolean, val copyFallback: Boolean)
}

/** Dove si apre un mondo. */
interface ChaosTarget {
  val name: String

  /** Un mondo nuovo e vuoto per il giro [round]. */
  suspend fun openWorld(round: Int): ChaosWorld
}

/** Tutto cio' che serve a costruire uno stack sotto prova: i decoratori (con i colpi) al posto dei veri. */
class StackEnv(
  val world: ChaosWorld,
  val fileOps: NasFileOps,
  val reader: NasMediaReader,
  val store: OpsStore,
  val clocks: OpClocks,
  val gate: OpsGate,
  /** L'id dei nuovi elementi (operazioni, cestino): ripetibile dal seme. */
  val newId: () -> String,
  val readOnly: (String) -> Boolean,
)

/** Il codice sotto prova: le operazioni e il cestino (con la pulizia a 30 giorni). */
class ChaosStack(val operations: NasOperations, val trash: NasTrash)

/**
 * Chi costruisce lo stack sotto prova. Oggi: le operazioni fake di P0-B ([FakeChaosStackFactory]). Quando P12 consegna, la sua fabbrica
 * si scrive qui accanto (e si sceglie con `MCG_CHAOS_STACK`, vedi [ChaosStacks]).
 */
interface ChaosStackFactory {
  val name: String

  /**
   * Il diario locale che sopravvive alle morti del processo (il "database su file"). Nuovo a ogni giro. Il banco lo legge senza colpi
   * per le verifiche; al codice sotto prova arriva avvolto in un decoratore.
   */
  fun newStore(world: ChaosWorld): OpsStore

  fun create(env: StackEnv): ChaosStack

  /**
   * Vero se lo stack scrive anche il record firmato del cestino (`trash/<id>/rec/<id>`, formato §5.7): il verificatore lo vuole per ogni voce
   * del cestino e lo ammette fra i file dell'app. Le operazioni fake di P0-B non lo scrivono.
   */
  val writesTrashRecords: Boolean get() = false

  /**
   * Le primitive che il banco NON colpisce con questo stack, perche' ha un difetto noto che nasconderebbe il resto. Vuoto per uno stack
   * senza difetti noti: ogni voce e' una cosa da sistemare, non un permesso. Il rapporto le elenca sempre.
   */
  val unfaultable: Set<Primitive> get() = emptySet()

  /** I difetti noti dello stack, per il rapporto e per `docs/prova-500.md`: sempre scritti, mai nascosti. */
  val knownDefects: List<String> get() = emptyList()

  /** Vero se toccare "Ferma" con questi elementi aperti non cade in un difetto noto dello stack (di norma sempre). */
  fun cancelIsSafe(openItems: List<OpItemRecord>): Boolean = true

  /**
   * Se il diario mostra l'effetto di un difetto noto che il banco non puo' evitare (perche' nasce da un guasto in un punto qualunque, non da
   * un'azione dell'utente), il suo numero ("D4"); il giro si ferma li' e il rapporto lo conta. Null se non c'e'. Mai usato per nascondere un
   * difetto nuovo: il segno riconosciuto deve essere preciso.
   */
  fun knownDefectHit(items: List<OpItemRecord>): String? = null
}

/** Le operazioni fake di P0-B: l'esecutore di riferimento e il cestino di riferimento. */
object FakeChaosStackFactory : ChaosStackFactory {
  override val name: String = "operazioni fake di P0-B (InMemoryNasOperations)"

  override val unfaultable: Set<Primitive> = setOf(Primitive.READ)

  override val knownDefects: List<String> = listOf(
    "D1. InMemoryNasOperations.fallbackCopy non cattura NasIoException: una caduta della connessione a meta' di una lettura a flusso (ripiego dal " +
      "telefono, E-D12) esce da start/resumeAll come eccezione invece di mettere l'operazione in pausa. Il banco non colpisce le letture a flusso " +
      "con questo stack.",
    "D2. InMemoryNasOperations.cancel ignora l'esito di deleteOwnTemp: se la cancellazione del temporaneo fallisce (connessione, socket) l'elemento " +
      "risulta ANNULLATO e il temporaneo resta sul NAS senza che nessun elemento del diario lo citi (invariante 5). Il banco non tocca \"Ferma\" " +
      "con elementi di spostamento tra share in COPYING, COPIED o VERIFIED.",
    "D4. InMemoryNasOperations.publishedByThisOp tratta un errore di lettura passeggero (connessione, socket) come \"non pubblicato\": dopo una " +
      "risposta persa di publishTemp, se la verifica della destinazione trova il NAS irraggiungibile l'elemento FALLISCE (unexpected) con la copia " +
      "gia' pubblicata e la sorgente mai finita nel cestino: due copie visibili e operazione fallita. Il banco ferma il giro quando lo vede e lo conta " +
      "(difetto 'riprodotto N volte').",
    "D3. OpStateMachine.cancellable ammette VERIFIED, ma lo stato VERIFIED non prova che la pubblicazione non sia avvenuta (PUBLISHED si scrive " +
      "DOPO publishTemp): con la risposta di publishTemp persa, \"Ferma\" annulla un elemento la cui copia e' gia' visibile mentre la sorgente non e' " +
      "mai andata nel cestino: due copie visibili (invariante 2) e operazione ANNULLATA. Stesso perimetro di D2 per il banco.",
  )

  override fun cancelIsSafe(openItems: List<OpItemRecord>): Boolean =
    openItems.none { it.flow == ItemFlow.MOVE_ACROSS_SHARES && it.state in UNSAFE_TO_CANCEL }

  override fun knownDefectHit(items: List<OpItemRecord>): String? =
    if (items.any { it.flow == ItemFlow.MOVE_ACROSS_SHARES && it.state == OpItemState.FAILED && it.errorCode == OpErrorCode.UNEXPECTED && it.sha256 != null }) "D4" else null

  private val UNSAFE_TO_CANCEL = setOf(OpItemState.COPYING, OpItemState.COPIED, OpItemState.VERIFIED)

  override fun newStore(world: ChaosWorld): OpsStore = InMemoryOpsStore()

  override fun create(env: StackEnv): ChaosStack {
    val operations = InMemoryNasOperations(
      deviceId = env.world.deviceId,
      accountId = env.world.accountId,
      fileOps = env.fileOps,
      reader = env.reader,
      store = env.store,
      clocks = env.clocks,
      gate = env.gate,
      readOnly = env.readOnly,
      newId = env.newId,
    )
    val trash = InMemoryNasTrash(
      deviceId = env.world.deviceId,
      store = env.store,
      fileOps = env.fileOps,
      reader = env.reader,
      clocks = env.clocks,
      gate = env.gate,
      readOnly = env.readOnly,
    )
    return ChaosStack(operations, trash)
  }
}

object ChaosStacks {
  /**
   * Lo stack delle operazioni VERE di P12: una classe `ProductionChaosStackFactory` (un `object` o una classe con costruttore vuoto) che
   * implementa [ChaosStackFactory], scritta in questa cartella quando P12 consegna (il codice sta in docs/prova-500.md). Si cerca per nome, non
   * si nomina mai nel codice: finche' non c'e' il banco gira solo sulle operazioni fake, e non dipende da come P12 costruisce le sue.
   */
  const val PRODUCTION_CLASS = "dev.pampa.mycloudgallery.core.nasops.chaos.ProductionChaosStackFactory"

  /** La variabile (o la proprieta' `chaos.stack`) per provare con un'altra fabbrica: il nome di una classe che implementa [ChaosStackFactory]. */
  private fun override(): String? = System.getProperty("chaos.stack") ?: System.getenv("MCG_CHAOS_STACK")

  /** Lo stack delle operazioni vere, se P12 ha consegnato la sua fabbrica (o se `MCG_CHAOS_STACK` ne nomina un'altra). Null altrimenti. */
  fun production(): ChaosStackFactory? = load(override() ?: PRODUCTION_CLASS)

  /** Quello da usare quando basta uno: le operazioni vere se ci sono, altrimenti le fake. */
  fun selected(): ChaosStackFactory = production() ?: FakeChaosStackFactory

  private fun load(name: String): ChaosStackFactory? {
    val type = try {
      Class.forName(name)
    } catch (_: ClassNotFoundException) {
      return null
    }
    val instance = runCatching { type.getField("INSTANCE").get(null) }.getOrNull() ?: type.getDeclaredConstructor().newInstance()
    return instance as ChaosStackFactory
  }
}

/** L'id ripetibile dal seme: 16 byte in base32 (26 caratteri, un id valido). Un solo generatore per tutte le incarnazioni di un giro. */
class SeededIds(seed: Long) {
  private val random = Random(seed)

  fun next(): String = Base32.encode(random.nextBytes(16))
}

// =============================================================================================================================
// Il NAS finto in memoria
// =============================================================================================================================

/** Il banco JVM: un NAS finto in memoria nuovo a ogni giro. */
class InMemoryTarget(
  private val shares: List<String> = listOf("test", "test-comune"),
  private val deviceId: String = DEVICE,
  private val accountId: String = "test",
) : ChaosTarget {
  override val name: String = "NAS finto in memoria"

  override suspend fun openWorld(round: Int): ChaosWorld = InMemoryWorld(shares, deviceId, accountId)

  companion object {
    /** Un id di dispositivo valido e stabile. */
    val DEVICE: String = Base32.encode(ByteArray(16) { 0x33 })
  }
}

class InMemoryWorld(
  override val shares: List<String>,
  override val deviceId: String,
  override val accountId: String,
) : ChaosWorld {
  private var nasMs: Long = FakeOpClocks.DEFAULT_NAS_NOW

  /** Il NAS finto: lo vedono anche i test dentro questo modulo (contenuti, file bloccati). */
  val nas: InMemoryNasMedia = InMemoryNasMedia(nasNow = { nasMs })

  override val clocks: FakeOpClocks = FakeOpClocks()
  override val reader: NasMediaReader = nas.reader
  override val fileOps: NasFileOps = nas.fileOps(deviceId)
  override val capabilities: ChaosWorld.Capabilities = ChaosWorld.Capabilities(advanceTime = true, copyFallback = true)

  init {
    shares.forEach { nas.addShare(it) }
  }

  override fun advanceTime(ms: Long) {
    nasMs += ms
    clocks.advance(ms)
  }

  override fun setCopyMode(mode: CopyMode) {
    nas.serverCopyMode = if (mode == CopyMode.SERVER) ServerCopyMode.OK else ServerCopyMode.NOT_SUPPORTED
  }

  override suspend fun putFile(share: String, path: SharePath, bytes: ByteArray) {
    nas.putFile(share, path.value, NasContent.of(bytes), nasMs)
  }

  override suspend fun connectionLost() = Unit

  override fun describe(): String = "NAS finto in memoria, share ${shares.joinToString()}"

  override fun close() = Unit
}

/** Il gate di default: sempre aperto. */
internal fun openGate(): FakeOpsGate = FakeOpsGate()
