package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import java.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * IL MONDO DEL BANCO: l'orologio, la famiglia (un account, 2-3 dispositivi), i media, il NAS ([ChaosNas]: finto, simulatore SMB o vero), le teste dei
 * dati personali, i registri di cio' che i dispositivi scrivono e cancellano e quanto basta per giudicare gli invarianti del §5.5 (chi ha salvato cosa,
 * chi ha visto "non piu' G", chi ha tenuto quale prenotazione). Solo identita' sintetiche e nomi inventati.
 */
internal class ChaosWorld(val seed: Long, val inj: ChaosInjector, val mutant: Mutant?, nasFactory: (ChaosWorld) -> ChaosNas) {
  val clock = ChaosClock()
  val family = ChaosFamily(clock, seed)
  val heads = ChaosHeads()
  val logs = ChaosLogs()
  val media = ChaosMedia(Random(seed xor 0x4D45444941L))
  val nas: ChaosNas = nasFactory(this)
  val devices = ArrayList<ChaosDevice>()

  /** Gli id delle persone, dal seme: lo stesso giro fa nascere le stesse persone. */
  val personIds: PersonIdSource = run {
    var n = 0
    PersonIdSource { PersonId.fromBytes(MessageDigest.getInstance("SHA-256").digest("persona-$seed-${++n}".toByteArray()).copyOf(16)) }
  }

  /** I file che non sono dati dei volti (media e file dell'app) messi nell'area privata, con il loro SHA-256 di partenza. Mai toccati. */
  val untouchable = LinkedHashMap<SharePath, String>()

  /** La cronaca fine di un giro (solo con `MCG_FACES_CHAOS_TRACE=1`): cosa e' successo a ogni dispositivo, per spiegare una violazione. Mai contenuti. */
  val trace = ArrayList<String>()
  val tracing: Boolean = System.getenv("MCG_FACES_CHAOS_TRACE") == "1"

  private val escaped = java.util.concurrent.ConcurrentLinkedQueue<String>()

  /** Un'eccezione e' uscita da una coroutine dell'interruttore di [device] (O3): si ricorda con il suo posto, e il verificatore la porta all'invariante 10. */
  fun escapedFromScope(device: String, e: Throwable) {
    escaped += "${e.javaClass.simpleName} esce da una coroutine dell'interruttore di $device: ${e.message}\n" + e.stackTrace.take(10).joinToString("\n") { "      $it" }
  }

  /** Le eccezioni uscite dalle coroutine dall'ultima volta che si sono chieste. */
  fun takeEscaped(): List<String> = generateSequence { escaped.poll() }.toList()

  fun note(text: String) {
    if (tracing) trace += "t=${clock.monotonicMs - ChaosClock.START_MONOTONIC_MS} $text"
  }

  // --- I registri per gli invarianti ------------------------------------------------------------------------------------------

  /** I corpi propri salvati: (dispositivo, generazione, contenuto) → quante volte. Piu' di una = un volto salvato e rifatto (invariante 1). */
  val saved = HashMap<Triple<String, FaceGeneration, ContentKey>, Int>()
  val resaved = ArrayList<String>()

  /** I nomi dati nelle persone di ogni generazione (per la cronologia). */
  val names = HashMap<FaceGeneration, MutableSet<String>>()
  val namedBy = HashMap<Pair<String, FaceGeneration>, MutableSet<String>>()

  /** Per (dispositivo, generazione): le scritture del dispositivo sotto `faces/<G>/` quando ha visto "non piu' G". */
  val offSeen = HashMap<Pair<String, FaceGeneration>, Int>()

  /** L'ultima generazione accesa da un dispositivo (la prima volta che compare in uno stato `On`). */
  var latestGeneration: FaceGeneration? = null
    private set
  private val everSeen = HashSet<FaceGeneration>()

  /** Per dispositivo: le scritture sotto `faces/` quando il dispositivo ha smesso di poter pubblicare (non e' piu' `On` attivo), finche' non torna. */
  val blockedAt = HashMap<String, Int>()
  private val known = HashMap<String, MutableSet<FaceGeneration>>()

  /** Chi tiene ogni prenotazione: (dispositivo, scadenza nell'ora del NAS). Una nuova presa di un altro prima della scadenza e' una violazione (invariante 7). */
  private val holders = HashMap<Pair<FaceGeneration, FacesResource>, Pair<Int, Long>>()
  val leaseViolations = ArrayList<String>()

  /** Scritture partite senza lo stato fresco (o per una generazione che non e' quella accesa qui): invariante 2. */
  val staleWrites = ArrayList<String>()

  fun device(name: String): ChaosDevice = ChaosDevice(this, devices.size, name, family.approve(name, "test")).also { devices += it }

  init {
    inj.onStall = { clock.advance(STALL_MILLIS) }
  }

  /** Mette nell'area privata i file che il banco non deve mai toccare. */
  fun plantUntouchable() {
    val paths = listOf(
      SharePath.parse("Foto/2025/IMG_0001.jpg"),
      SharePath.parse("Foto/2025/IMG_0002.jpg"),
      SharePath.parse("Foto/head/0000000001"),
      SharePath.parse(".mycloudgallery/devices/aaaa/personal/head/0000000001"),
      SharePath.parse(".mycloudgallery/faces-note.txt"),
    )
    paths.forEachIndexed { i, path ->
      val bytes = "non toccare $i".toByteArray()
      nas.plant(path, bytes)
      untouchable[path] = sha256(bytes)
    }
  }

  // --- Eventi guardati dal mondo ------------------------------------------------------------------------------------------------

  /** Un corpo scritto davvero nell'archivio di [device]: se e' suo ed e' la seconda volta per quel contenuto, un volto salvato e' stato rifatto. */
  fun bodySaved(device: ChaosDevice, generation: FaceGeneration, body: FaceBody) {
    if (body.author != device.id) return
    val key = Triple(device.id, generation, body.contentKey)
    val times = (saved[key] ?: 0) + 1
    saved[key] = times
    if (times > 1) resaved += "${device.name} ha salvato di nuovo un contenuto della ricetta ${body.recipe} (volta $times)"
    inj.stats.computedMedia++
    note("${device.name} salva il corpo ${body.contentKey.hex.takeLast(4)} (generazione ${generation.base32.take(4)})")
  }

  /** I dati locali di [generation] di [device] sono stati tolti (cancellazione o esclusione): i corpi che ricalcolera' dopo non sono lavoro rifatto. */
  fun wiped(device: ChaosDevice, generation: FaceGeneration) {
    saved.keys.removeAll { it.first == device.id && it.second == generation }
  }

  fun named(generation: FaceGeneration, name: String, device: ChaosDevice) {
    names.getOrPut(generation) { HashSet() } += name
    namedBy.getOrPut(device.id to generation) { HashSet() } += name
  }

  /** Quante scritture (tentativi) di [deviceId] stanno sotto `faces/<generation>/`. */
  fun attemptsUnder(deviceId: String, generation: FaceGeneration): Int =
    synchronized(logs.attempts) { logs.attempts.count { it.first == deviceId && it.second.isWithin(NasLayout.facesGenerationDir(generation)) } }

  /** Le scritture di [deviceId] sotto `faces/<generation>/` oltre le prime [skip], come "tipo (testa, segmento, prenotazione)" e numero: per spiegare una violazione. */
  fun writesAfter(deviceId: String, generation: FaceGeneration, skip: Int): String {
    val all = synchronized(logs.attempts) { logs.attempts.filter { it.first == deviceId && it.second.isWithin(NasLayout.facesGenerationDir(generation)) }.drop(skip) }
    return all.groupingBy { path ->
      val c = path.second.components
      if (c.size > 3) (if (c[3] == NasLayout.FACES_LEASES) "prenotazione" else c.getOrNull(4) ?: "?") else "?"
    }.eachCount().toString()
  }

  fun attemptsUnderFaces(deviceId: String): Int =
    synchronized(logs.attempts) { logs.attempts.count { it.first == deviceId && it.second.isWithin(NasLayout.FACES_DIR) } }

  /** Segue lo stato di Persone di [device] (a ogni processo nuovo): da quando non puo' piu' pubblicare, e per quali generazioni ha visto "non piu' G". */
  fun watch(device: ChaosDevice) {
    device.scope.launch {
      device.switch.state.collect { state -> onState(device, state) }
    }
  }

  private fun onState(device: ChaosDevice, state: FacesState) {
    if (device.stopping) return
    val generation = state.generationOrNull
    val set = known.getOrPut(device.id) { HashSet() }
    if (generation != null && (state is FacesState.On || state is FacesState.AwaitingNotice)) {
      set += generation
      if (everSeen.add(generation)) latestGeneration = generation
    }
    // Le generazioni che il dispositivo conosceva e che ora non tratta piu' come accese: "non piu' G".
    for (g in set.toList()) {
      val stillOn = (state is FacesState.On || state is FacesState.AwaitingNotice) && generation == g
      if (!stillOn) offSeen.putIfAbsent(device.id to g, attemptsUnder(device.id, g))
    }
    if (state.activeHere) blockedAt.remove(device.id) else blockedAt.putIfAbsent(device.id, attemptsUnderFaces(device.id))
  }

  /** Una scrittura sul NAS sta per partire: lo stato di Persone qui dev'essere `On` attivo e fresco, per la generazione del percorso (D30). */
  fun writeAttempt(device: ChaosDevice, path: SharePath) {
    val state = device.switch.currentState()
    val fresh = device.switch.fresh.value
    val generation = if (path.isWithin(NasLayout.FACES_DIR) && path.components.size > 2) FaceGeneration.parse(path.components[2]) else null
    val ok = state is FacesState.On && state.here == dev.pampa.mycloudgallery.core.faces.contract.HereState.ACTIVE && fresh &&
      (generation == null || generation == state.generation)
    if (!ok) staleWrites += "${device.name} scrive sul NAS con lo stato ${state.javaClass.simpleName}, fresco=$fresh"
  }

  /** Una chiamata alle prenotazioni e' finita male (guasto, morte): la prenotazione puo' essere cambiata sul NAS senza che nessuno lo sappia. Il tracciato la dimentica. */
  fun leaseUnknown(generation: FaceGeneration, resource: FacesResource) {
    holders.remove(generation to resource)
  }

  /** Le prenotazioni: ogni presa riuscita si confronta con chi la teneva e non era ancora scaduta. */
  fun leaseEvent(device: ChaosDevice, generation: FaceGeneration, resource: FacesResource, lease: FacesLease, released: Boolean = false) {
    inj.stats.leaseEvents++
    note("${device.name} prenotazione ${if (released) "rilasciata" else lease.javaClass.simpleName} ${resource.toString().takeLast(24)}${(lease as? FacesLease.Held)?.let { " n=${it.number} fino a +${it.untilNasMillis - clock.nasMs}" } ?: ""}")
    val key = generation to resource
    val now = clock.nasMs
    if (released) {
      if (holders[key]?.first == device.index) holders.remove(key)
      return
    }
    when (lease) {
      is FacesLease.Held -> {
        val current = holders[key]
        if (current != null && current.first != device.index && current.second > now) {
          leaseViolations += "${device.name} ha preso una prenotazione che ${devices[current.first].name} teneva ancora (scade fra ${current.second - now} ms)"
        }
        holders[key] = device.index to lease.untilNasMillis
      }
      FacesLease.Contended -> inj.stats.contendedLeases++
      else -> Unit
    }
  }

  // --- Il cambio di generazione a meta' di un lavoro ----------------------------------------------------------------------------

  /** Chi esegue un colpo [FaultKind.FLIP]: sul dispositivo del colpo, cancellazione confermata, esclusione, o la cancellazione di un altro. */
  suspend fun flip(blow: Blow) {
    val device = devices.getOrNull(blow.device) ?: return
    try {
      when (blow.flip) {
        0 -> device.confirmErase()
        1 -> device.switch.excludeThisDevice()
        else -> {
          val other = devices.firstOrNull { it !== device && it.isActive() } ?: return
          other.confirmErase()
          other.personalRound()
          device.personalRound()
        }
      }
    } catch (e: ChaosFault) {
      // Un guasto del NAS durante il cambio: il cambio resta a meta' (lo riprende la ripresa).
    } catch (e: ChaosDbFault) {
      // idem per il database
    } catch (e: CancellationException) {
      throw e
    }
  }

  init {
    inj.flipper = { blow -> flip(blow) }
  }

  fun close() {
    devices.forEach { runCatching { it.shutdown() } }
    nas.close()
  }

  companion object {
    /** Quanto passa prima che uno stallo si riveli: 30 secondi di orologio del banco. */
    const val STALL_MILLIS: Long = 30_000

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
