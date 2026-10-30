package dev.pampa.mycloudgallery.core.memories.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesStatus
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfRead
import dev.pampa.mycloudgallery.core.memories.contract.ShelfState
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.memories.shelf.FileShelfStore
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Il motore a runtime (docs/fase9-progetto.md D4, D9, D10, D19, D31, R17, "Avvio e pagine istantanei"; P6): il lavoro pesante **fuori dal thread di
 * chi chiama**, lo scaffale che si disegna **subito da file**, il budget, la cancellazione, il file rovinato, i nascosti nell'istante fra un
 * "Nascondi" e la pulizia, le scelte fuori casa, niente nomi e niente dati nei log.
 */
class MemoryEngineRuntimeTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val rigs = ArrayList<EngineRig>()

  @After
  fun chiudeIDispositivi() {
    rigs.forEach { it.close() }
    GalleryLog.uninstall()
  }

  private fun d(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

  /** Due anniversari (2025 e 2024) il 3 novembre 2026. */
  private val shots = dayShots(d(2025, 11, 3), 9, series = 1, place = 3_100_001) + dayShots(d(2024, 11, 3), 9, series = 2, place = 3_100_002)

  private fun rig(
    store: dev.pampa.mycloudgallery.core.memories.contract.MemoryShelfStore = InMemoryShelfStore(),
    trips: MemoryTrips = dev.pampa.mycloudgallery.core.memories.noop.NoMemoryTrips,
    pixels: MemoryPixels = dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPixels,
    clock: FakeMemoryClock? = null,
    register: dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister = dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister(),
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
  ) = EngineRig(visibility = visibilityOver(shots), store = store, trips = trips, pixels = pixels, clockOverride = clock, register = register, dispatcher = dispatcher)
    .also { rigs += it }

  /** Il lavoro pesante (la tabella, il generatore, la scelta, il file) gira sul thread del motore: chi chiama, il thread principale, non lo tiene. */
  @Test
  fun ilLavoroPesanteNonStaSulThreadDiChiChiama() {
    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ricordi-lavoro") }
    val worker = executor.asCoroutineDispatcher()
    try {
      val threads = ConcurrentLinkedQueue<String>()
      val trips = object : MemoryTrips {
        override suspend fun past(until: LocalDate, budget: Duration): List<TripDigestView> {
          threads += Thread.currentThread().name
          return emptyList()
        }
      }
      val rig = rig(trips = trips, dispatcher = worker)
      val caller = Thread.currentThread().name
      val report = runBlocking { rig.refresh() }
      assertThat(report.published).isEqualTo(2)
      assertThat(threads).isNotEmpty()
      // Il nome porta anche il segno della coroutine (agente di debug): conta il thread.
      for (name in threads) assertThat(name).startsWith("ricordi-lavoro")
      assertThat(caller).isNotEqualTo("ricordi-lavoro")
    } finally {
      worker.close()
    }
  }

  /**
   * "Avvio e pagine istantanei": il nuovo processo legge lo scaffale da file in un colpo e disegna **subito** Raccolte -> Ricordi, senza la
   * Libreria (l'istantanea non c'e' ancora), senza database (nessuna lettura dei visibili), senza viaggi, persone, vettori o pixel.
   */
  @Test
  fun restoreDisegnaSubitoLoScaffaleDaFile() = runTest {
    val dir = File(temp.newFolder(), "memories")
    val first = rig(store = FileShelfStore(dir, Dispatchers.Unconfined))
    first.refresh()
    val drawn = first.engine.shelf.value
    assertThat(drawn.items).hasSize(2)

    val calls = AtomicInteger()
    val arriving = MutableStateFlow<TimelineSnapshot?>(null)
    val counting = object : MemoryVisibility {
      override val snapshot: StateFlow<TimelineSnapshot?> = arriving

      override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = ids.toSet().also { calls.incrementAndGet() }

      override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = emptySet<MediaId>().also { calls.incrementAndGet() }
    }
    val trips = object : MemoryTrips {
      override suspend fun past(until: LocalDate, budget: Duration): List<TripDigestView> = emptyList<TripDigestView>().also { calls.incrementAndGet() }
    }
    val second = EngineRig(visibility = visibilityOver(emptyList()), store = FileShelfStore(dir, Dispatchers.Unconfined), library = counting, trips = trips)
      .also { rigs += it }
    assertThat(second.engine.shelf.value.state).isEqualTo(ShelfState.Empty(ShelfEmptyReason.LIBRARY_NOT_READY))
    second.engine.restore()
    assertThat(second.engine.shelf.value.items).isEqualTo(drawn.items)
    assertThat(second.engine.shelf.value.state).isEqualTo(ShelfState.Ready)
    assertThat(second.engine.shelf.value.unseen).isEqualTo(2)
    assertThat(second.engine.status.value).isEqualTo(MemoriesStatus.Idle(20261103, 2, 2))
    assertThat(calls.get()).isEqualTo(0)

    // Quando la Libreria arriva, lo scaffale si filtra con l'istantanea senza nessuna pulizia: un nascosto non si vede nemmeno per un istante.
    val hidden = first.engine.current()!!.memories.first().members[1]
    arriving.value = snapshotOf(shots.filter { it.id != hidden })
    val card = second.engine.shelf.value.items.first { it.id == first.engine.current()!!.memories.first().id }
    assertThat(card.count).isEqualTo(first.engine.current()!!.memories.first().members.size - 1)
  }

  /** Un riavvio non cambia niente: con lo scaffale di ieri sul file, il giro di oggi pubblica solo cio' che e' nuovo (qui niente), senza rifare i ricordi. */
  @Test
  fun loScaffaleSopravvivealRiavvio() = runTest {
    val dir = File(temp.newFolder(), "memories")
    val first = rig(store = FileShelfStore(dir, Dispatchers.Unconfined))
    first.refresh()
    val before = first.engine.current()
    val second = rig(store = FileShelfStore(dir, Dispatchers.Unconfined))
    assertThat(second.refresh().published).isEqualTo(0)
    assertThat(second.engine.current()).isEqualTo(before)
  }

  /** D10: un file rovinato vale "scaffale vuoto" e si rigenera, e lo dice (una volta); le scelte dell'utente non si toccano. */
  @Test
  fun unFileRovinatoSiRigeneraELoDice() = runTest {
    val dir = File(temp.newFolder(), "memories")
    val register = dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister()
    val first = rig(store = FileShelfStore(dir, Dispatchers.Unconfined), register = register)
    first.refresh()
    first.engine.markSeen(first.engine.current()!!.memories.first().id)
    File(dir, FileShelfStore.FILE_NAME).writeBytes(ByteArray(300) { it.toByte() })
    val second = rig(store = FileShelfStore(dir, Dispatchers.Unconfined), register = register)
    second.engine.restore()
    assertThat(second.engine.shelf.value.state).isEqualTo(ShelfState.Working)
    val report = second.refresh()
    assertThat(report.shelfReset).isTrue()
    // Il ricordo gia' visto non si rigenera per 12 mesi (D9), l'altro si': lo scaffale si ricostruisce senza ripescare cio' che l'utente ha gia' guardato.
    assertThat(report.published).isEqualTo(1)
    assertThat(second.refresh().shelfReset).isFalse()
    assertThat(FileShelfStore(dir, Dispatchers.Unconfined).read()).isInstanceOf(ShelfRead.Ready::class.java)
    assertThat(second.engine.shelf.value.items).hasSize(1)
    assertThat(second.engine.shelf.value.unseen).isEqualTo(1)
  }

  /** Una pulizia su un file rovinato lo sostituisce con uno scaffale vuoto e lo dice; il giro dopo lo riempie. */
  @Test
  fun puliziaDiUnFileRovinato() = runTest {
    val dir = File(temp.newFolder(), "memories")
    val first = rig(store = FileShelfStore(dir, Dispatchers.Unconfined))
    first.refresh()
    File(dir, FileShelfStore.FILE_NAME).writeBytes(byteArrayOf(1, 2, 3))
    val second = rig(store = FileShelfStore(dir, Dispatchers.Unconfined))
    assertThat(second.engine.prune().shelfReset).isTrue()
    assertThat(FileShelfStore(dir, Dispatchers.Unconfined).read()).isInstanceOf(ShelfRead.Ready::class.java)
    assertThat(second.engine.prune().shelfReset).isFalse()
    assertThat(second.refresh().published).isEqualTo(2)
  }

  /**
   * D4: il budget si controlla prima di ogni ricordo. Qui il primo ricordo consuma tutto il tempo (le misure dei pixel); il secondo non parte: il giro
   * dice "incompleto", lo scaffale scritto e' valido (con il primo), e col budget intero il giro dopo pubblica l'altro.
   */
  @Test
  fun ilBudgetFinitoLasciaLoScaffaleValido() = runTest {
    val clock = FakeMemoryClock(d(2026, 11, 3))
    val pixels = MemoryPixels { _, _ ->
      clock.advance(10_000)
      emptyMap<MediaId, PixelProbe>()
    }
    val store = InMemoryShelfStore()
    val rig = rig(store = store, pixels = pixels, clock = clock)
    val report = rig.refresh(budget = 5.seconds)
    assertThat(report.incomplete).isTrue()
    assertThat(report.published).isEqualTo(1)
    assertThat(store.peek()!!.lastRunDay).isEqualTo(20261103)
    assertThat(store.peek()!!.memories).hasSize(1)
    assertThat(rig.engine.status.value).isEqualTo(MemoriesStatus.Idle(20261103, 1, 1))
    val full = rig.refresh(budget = 60.seconds)
    assertThat(full.incomplete).isFalse()
    assertThat(full.published).isEqualTo(1)
    assertThat(store.peek()!!.memories).hasSize(2)
  }

  /** Il tempo e' gia' finito prima dei segnali: nessuna misura dei pixel, nessun vettore, il giro e' incompleto. */
  @Test
  fun senzaTempoNonSiChiedonoISegnali() = runTest {
    val clock = FakeMemoryClock(d(2026, 11, 3))
    val calls = AtomicInteger()
    val slowTrips = object : MemoryTrips {
      override suspend fun past(until: LocalDate, budget: Duration): List<TripDigestView> {
        clock.advance(10_000)
        return emptyList()
      }
    }
    val pixels = MemoryPixels { _, _ ->
      calls.incrementAndGet()
      emptyMap<MediaId, PixelProbe>()
    }
    val rig = rig(trips = slowTrips, pixels = pixels, clock = clock)
    assertThat(rig.refresh(budget = 5.seconds).incomplete).isTrue()
    assertThat(calls.get()).isEqualTo(0)
  }

  /** Un giro cancellato (l'app si chiude, il lavoro si ferma) rimette lo stato com'era, non scrive niente e libera il lucchetto. */
  @Test
  fun unGiroCancellatoRimetteLoStato() = runTest {
    val store = InMemoryShelfStore()
    val gate = CompletableDeferred<Unit>()
    var blocking = false
    val trips = object : MemoryTrips {
      override suspend fun past(until: LocalDate, budget: Duration): List<TripDigestView> {
        if (blocking) gate.await()
        return emptyList()
      }
    }
    val rig = rig(store = store, trips = trips)
    rig.refresh()
    val statusBefore = rig.engine.status.value
    val fileBefore = store.peek()
    val writes = store.writes
    blocking = true
    val job = launch { rig.refresh() }
    runCurrent()
    assertThat(rig.engine.status.value).isEqualTo(MemoriesStatus.Working)
    job.cancelAndJoin()
    assertThat(rig.engine.status.value).isEqualTo(statusBefore)
    assertThat(store.peek()).isEqualTo(fileBefore)
    assertThat(store.writes).isEqualTo(writes)
    blocking = false
    assertThat(rig.refresh().published).isEqualTo(0)
  }

  /** Una scrittura che muore lascia lo scaffale in memoria e quello su file come erano, lo stato com'era, e il giro dopo riesce. */
  @Test
  fun unaScritturaCheMuoreNonCambiaNiente() = runTest {
    val store = InMemoryShelfStore()
    val rig = rig(store = store)
    rig.refresh()
    val memory = rig.engine.current()
    val status = rig.engine.status.value
    rig.clock.today = d(2026, 11, 4)
    store.dieDuringNextWrite()
    val failure = runCatching { rig.refresh() }.exceptionOrNull()
    assertThat(failure).isNotNull()
    assertThat(failure!!.message.orEmpty()).doesNotContain("2025")
    assertThat(rig.engine.current()).isEqualTo(memory)
    assertThat(rig.engine.status.value).isEqualTo(status)
    assertThat(rig.refresh().removed).isEqualTo(0)
    assertThat(store.peek()!!.lastRunDay).isEqualTo(20261104)
  }

  /** D31, E-MEM5: nell'istante fra un "Nascondi" e la pulizia, la sezione, la copertina e la pagina non mostrano gia' il nascosto. */
  @Test
  fun unNascostoNonSiVedeNemmenoPrimaDellaPulizia() = runTest {
    val rig = rig()
    rig.refresh()
    val memory = rig.engine.current()!!.memories.first()
    val victim = memory.cover
    rig.visibility.hidden += victim
    rig.visibility.snapshot.value = snapshotOf(shots.filter { it.id != victim }, seq = 2)
    // Il file non e' stato ripulito (nessuna chiamata a prune): la vista lo e' comunque.
    assertThat(rig.engine.current()!!.memories.first().members).contains(victim)
    val card = rig.engine.shelf.value.items.first { it.id == memory.id }
    assertThat(card.cover).isNotEqualTo(victim)
    assertThat(card.count).isEqualTo(memory.members.size - 1)
    val view = rig.engine.memory(memory.id).first()!!
    assertThat(view.cover).isNotEqualTo(victim)
    assertThat(view.view.viewIndexOf(victim)).isEqualTo(-1)
    // Sotto 3 elementi la voce non c'e' piu'.
    val keep = memory.members.take(2).toSet()
    rig.visibility.snapshot.value = snapshotOf(shots.filter { it.id !in memory.members || it.id in keep }, seq = 3)
    assertThat(rig.engine.shelf.value.items.map { it.id }).doesNotContain(memory.id)
    assertThat(rig.engine.memory(memory.id).first()).isNull()
  }

  /** E-MEM22: fuori casa (senza l'ora del NAS) "visto" e "Non mostrare" valgono subito qui, e viaggiano alla prima sincronizzazione. */
  @Test
  fun leSceltaFuoriCasaValgonoSubitoEViaggiano() = runTest {
    val register = dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister()
    val phone = rig(register = register)
    val tablet = rig(register = register)
    phone.refresh()
    tablet.refresh()
    val ids = phone.engine.current()!!.memories.map { it.id }
    phone.personal.online = false
    phone.engine.dismiss(ids[0])
    phone.engine.markSeen(ids[1])
    assertThat(phone.engine.shelf.value.items.map { it.id }).containsExactly(ids[1])
    assertThat(phone.engine.shelf.value.unseen).isEqualTo(0)
    assertThat(tablet.engine.shelf.value.items).hasSize(2)
    phone.personal.online = true
    phone.personal.sync()
    // Il tablet vede le scelte dell'altro **senza** un giro: la sezione segue le scelte.
    tablet.personal.pull()
    assertThat(tablet.engine.shelf.value.items.map { it.id }).containsExactly(ids[1])
    assertThat(tablet.engine.shelf.value.unseen).isEqualTo(0)
  }

  /** Mai nomi: ne' nello scaffale su file ne' nei dati personali; il nome di una persona si legge al momento e una rinomina si vede. */
  @Test
  fun maiNomiNelloScaffaleNeiDatiPersonali() = runTest {
    val dir = File(temp.newFolder(), "memories")
    val register = dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister()
    val personShots = (0 until 4).flatMap { dayShots(d(2025, 3, 1 + it), 6, series = 30L + it) }
    val library = visibilityOver(personShots)
    val rig = EngineRig(visibility = library, store = FileShelfStore(dir, Dispatchers.Unconfined), register = register).also { rigs += it }
    val anna = PersonId.fromBytes(ByteArray(16) { (it + 9).toByte() })
    rig.people.person(anna, "Annabella-Zeta", personShots.map { it.id })
    assertThat(rig.refresh().published).isEqualTo(1)
    val memory = rig.engine.current()!!.memories.single()
    assertThat(memory.kind).isEqualTo(MemoryKind.PERSON)
    // L'id si salva, il nome mai: ne' nello scaffale su file ne' nelle voci dei dati personali.
    val bytes = String(File(dir, FileShelfStore.FILE_NAME).readBytes(), Charsets.ISO_8859_1)
    assertThat(bytes).doesNotContain("Annabella")
    rig.engine.setLess(LessTarget.Person(anna), LessLevel.LESS)
    assertThat(register.live()).isNotEmpty()
    for ((key, value) in register.live()) {
      assertThat(key.toString() + value.toString()).doesNotContain("Annabella")
    }
    // "Mostra meno" toglie il ricordo di quella persona (D11): niente ricordo, e il nome si legge adesso per le Impostazioni.
    assertThat(rig.engine.current()!!.memories).isEmpty()
    assertThat(rig.engine.lessChoices.value.single().personName).isEqualTo("Annabella-Zeta")
    rig.people.names[anna] = "Nuovo nome"
    rig.engine.setLess(LessTarget.Person(anna), null)
    assertThat(rig.engine.lessChoices.value).isEmpty()
  }

  /** D33: niente titoli, luoghi, date, id o nomi nei log, nei `toString()` e nei messaggi dei guasti; solo numeri e codici. */
  @Test
  fun nessunDatoPersonaleNeiLogNeiToString() = runTest {
    val lines = ConcurrentLinkedQueue<String>()
    GalleryLog.install { _, tag, message, throwable -> lines += "$tag $message ${throwable?.message.orEmpty()}" }
    val dir = File(temp.newFolder(), "memories")
    val personShots = (0 until 4).flatMap { dayShots(d(2025, 3, 1 + it), 6, series = 40L + it, place = 3_199_999) }
    val tripShots = dayShots(d(2025, 11, 1), 12, series = 50, place = 3_199_998)
    val all = shots + personShots + tripShots
    val trip = TripDigestView(TripKind.TRIP, DayRange(d(2025, 11, 1), d(2025, 11, 1)), "Etichetta-riconoscibile", listOf(3_199_997), tripShots.map { it.id }, null, null)
    val rig = EngineRig(visibility = visibilityOver(all), store = FileShelfStore(dir, Dispatchers.Unconfined), trips = fakeTrips(trip)).also { rigs += it }
    val anna = PersonId.fromBytes(ByteArray(16) { (it + 3).toByte() })
    rig.people.person(anna, "Annabella-Zeta", personShots.map { it.id })
    val report = rig.refresh()
    val memories = rig.engine.current()!!.memories
    rig.engine.markSeen(memories.first().id)
    rig.engine.setLess(LessTarget.Days(DayRange.of(d(2025, 11, 3))), LessLevel.NEVER)
    File(dir, FileShelfStore.FILE_NAME).writeBytes(byteArrayOf(9))
    val reborn = EngineRig(visibility = visibilityOver(all), store = FileShelfStore(dir, Dispatchers.Unconfined)).also { rigs += it }
    reborn.engine.restore()
    reborn.refresh()

    assertThat(memories).isNotEmpty()
    val forbidden = buildList {
      add("Annabella"); add("Etichetta-riconoscibile"); add("3199999"); add("3199998"); add("3199997"); add("2025-11"); add("20251103"); add("2024-11")
      memories.forEach { memory ->
        add(memory.id.value)
        memory.members.forEach { add(it.value) }
      }
    }
    val texts = ArrayList<String>(lines)
    texts += report.toString()
    texts += rig.engine.shelf.value.toString()
    texts += rig.engine.status.value.toString()
    texts += rig.engine.lessChoices.value.toString()
    texts += rig.personal.current().toString()
    memories.forEach { memory ->
      texts += memory.toString()
      texts += memory.title.toString()
      texts += memory.key.toString()
      texts += rig.engine.memory(memory.id).first()?.toString().orEmpty()
    }
    rig.engine.shelf.value.items.forEach { texts += it.toString() }
    for (text in texts) for (secret in forbidden) assertWithMessage("\"$secret\" in: $text").that(text).doesNotContain(secret)
    assertThat(lines.size).isGreaterThan(0)
  }

  /**
   * Il passo 5 e' un punto d'innesto per ricordo (P7): il motore chiama la scelta **una volta per ricordo che prova a pubblicare**, con il tempo che
   * resta; non la chiama per chi non ha posto (al piu' due nuovi al giorno) ne' quando non ci sono candidati; riporta "senza vettori" se lo dice lei.
   */
  @Test
  fun ilPassoCinqueEUnPuntoDInnesto() = runTest {
    val calls = ConcurrentLinkedQueue<Pair<Int, Duration>>()
    val choice = dev.pampa.mycloudgallery.core.memories.plan.MemoryChoice { cells, spec, choices, budget ->
      calls += cells.size to budget
      val signals = dev.pampa.mycloudgallery.core.memories.contract.PickSignals(choices = choices)
      Chosen(dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker().pick(cells, spec, signals), withoutVectors = true)
    }
    val rig = EngineRig(visibility = visibilityOver(shots), choice = choice).also { rigs += it }
    val report = rig.refresh(budget = 30.seconds)
    assertThat(report.published).isEqualTo(2)
    assertThat(report.withoutVectors).isTrue()
    assertThat(calls.map { it.first }).containsExactly(9, 9)
    for ((_, budget) in calls) assertThat(budget).isAtMost(30.seconds)
    // Un secondo giro lo stesso giorno: niente candidati, la scelta non si chiama.
    rig.refresh()
    assertThat(calls).hasSize(2)
  }
}
