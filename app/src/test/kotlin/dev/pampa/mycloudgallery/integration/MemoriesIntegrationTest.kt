package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.shelf.FileShelfStore
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesRecords
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import dev.pampa.mycloudgallery.memories.MemoriesGraph
import dev.pampa.mycloudgallery.memories.MemoriesLive
import dev.pampa.mycloudgallery.memories.MemoriesWiring
import dev.pampa.mycloudgallery.work.FacesStages
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
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

/**
 * Fase 9, P13 (docs/fase9-progetto.md §4.4 riga P13, "Fatto quando"; D9-D12; E-MEM8, E-MEM10, E-MEM22): i ricordi **sul grafo vero** di due dispositivi dello
 * **stesso account** (telefono e tablet, `test`), sul NAS simulato, con le persone vere (motori dei volti finti, nomi inventati):
 *
 * 1. **gli scaffali possono essere diversi**: lo scaffale e' un file locale (`memories/`) che non va mai sul NAS; il telefono disegna il suo dall'ultimo file
 *    (`restore()`), il tablet ha il suo;
 * 2. **le scelte viaggiano**, nelle due direzioni, con il giro dei dati personali: "visto" (col giorno), "Non mostrare", "Mostra meno" e "Mai" per una persona,
 *    "Mai" per un periodo;
 * 3. **nessun nome nei dati personali** dei ricordi (ne' nello scaffale): la persona e' solo il suo id; il nome si legge al momento dalla porta delle persone
 *    (`IndexMemoryPeople.label`), che vede solo persone con un nome e celle mostrabili.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class MemoriesIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private var rig: FacesRig? = null
  private val graphs = mutableListOf<MemoriesGraph>()

  @After
  fun tearDown() {
    runBlocking { graphs.forEach { runCatching { it.stop() } } }
    rig?.close()
  }

  private suspend fun live(graph: MemoriesGraph, what: String): MemoriesLive =
    checkNotNull(withTimeout(Attese.TETTO) { graph.ensureLive() }) { "$what: ricordi non montati" }

  /** I giri dei dati personali di [nodes] (l'ora del NAS avanza) finche' [condition] vale. */
  private suspend fun roundsUntil(what: String, vararg nodes: FacesNode, condition: suspend () -> Boolean) {
    val rig = checkNotNull(rig)
    try {
      withTimeout(Attese.TETTO) {
        while (!condition()) {
          for (node in nodes) {
            node.device.graph.trustGuard.preflight(Purpose.BACKGROUND)
            node.round()
          }
          rig.time.advance(6.minutes.inWholeMilliseconds)
        }
      }
    } catch (e: TimeoutCancellationException) {
      throw AssertionError("Dopo ${Attese.TETTO} non e' successo: $what", e)
    }
  }

  @Test
  fun dueDispositiviDelloStessoAccount_leSceltePassanoGliScaffaliNoENessunNome() = runBlocking<Unit> {
    val faces = FacesRig(app, temp.root).also { rig = it }
    val phoneDevice = faces.ai.foundedDevice()
    val tabletDevice = faces.joinDevice(phoneDevice, "tablet", TestNas.ADMIN_USER, TestNas.ADMIN_PASSWORD)
    faces.putPhotos(TestNas.ADMIN_USER, PHOTOS)
    val phone = faces.node("telefono", phoneDevice, TestNas.ADMIN_USER)
    val tablet = faces.node("tablet", tabletDevice, TestNas.ADMIN_USER)
    faces.catalogue(phone, PHOTOS)
    faces.catalogue(tablet, PHOTOS)

    // 1. Uno scaffale scritto sul telefono (un ricordo di tre foto della sua Libreria): il motore lo disegna da file appena montato, il tablet no.
    val snapshot = checkNotNull(phone.mounted.gallery.visibleMedia.snapshot.value)
    val members = (0 until 3).map { snapshot.idAt(it) }
    val key = MemoryKey.OnThisDay(TODAY.minusYears(1))
    val memory = Memory(
      MemoryIds.of(key), key, MemoryTitle(1, DayRange.of(TODAY.minusYears(1)), emptyList(), null, null, null), members, members.first(), MemoryDays.dayOf(TODAY),
    )
    FileShelfStore(File(phone.noBackup, MemoriesWiring.DIR)).write(ShelfFile(listOf(memory)))
    val phoneMemories = phone.memoriesGraph().also { graphs += it }
    val tabletMemories = tablet.memoriesGraph().also { graphs += it }
    val phoneLive = live(phoneMemories, "telefono")
    val tabletLive = live(tabletMemories, "tablet")
    assertThat(phoneMemories.memories.shelf.first { it.items.isNotEmpty() }.items.map { it.id }).containsExactly(memory.id)
    assertThat(tabletMemories.memories.shelf.value.items).isEmpty()

    // 2. Le persone vere: Persone accesa sul telefono, calcolo, un nome; il tablet legge l'avviso e riceve le persone.
    val outcome = phone.live().switch.activate(FacesActivationConsent(FacesSwitch.CURRENT_TEXT_VERSION, SystemClock.elapsedRealtimeNanos()))
    assertThat(outcome).isInstanceOf(ActivateOutcome.Activated::class.java)
    phone.round()
    compute(phone)
    phone.faces.nightly(30.seconds)
    val person: PersonId = withTimeout(Attese.TETTO) { phone.live().people.others().first { it.people.size == 2 } }.people.first().id
    assertThat(phone.live().people.rename(person, NAME)).isEqualTo(EditOutcome.Done)
    roundsUntil("il tablet vede l'avviso", phone, tablet) { tablet.live().switch.currentState() is FacesState.AwaitingNotice }
    tablet.live().switch.acceptNotice(FacesSwitch.CURRENT_TEXT_VERSION)
    roundsUntil("il nome arriva sul tablet", phone, tablet) { tablet.live().people.list.value.named.any { it.name == NAME } }

    // La porta delle persone dei ricordi (P8): la persona con il nome e le sue celle visibili; il nome solo al momento.
    withTimeout(Attese.TETTO) { phoneLive.people.available.first { it } }
    val named = phoneLive.people.named()
    assertThat(named.map { it.person }).contains(person)
    assertThat(named.first { it.person == person }.members).isNotEmpty()
    assertThat(phoneLive.people.label(person)).isEqualTo(NAME)
    assertThat(phoneLive.people.resolve(person)).isEqualTo(person)

    // 3. Le scelte sul telefono: visto, "Non mostrare", "Mostra meno" per la persona, "Mai" per un periodo.
    val dismissedKey = MemoryKey.Month(java.time.YearMonth.from(TODAY.minusMonths(1)))
    val days = DayRange(TODAY.minusYears(2), TODAY.minusYears(2).plusDays(3))
    phoneMemories.memories.markSeen(memory.id)
    phoneMemories.memories.dismiss(MemoryIds.of(dismissedKey))
    phoneMemories.memories.setLess(LessTarget.Person(person), LessLevel.LESS)
    phoneMemories.memories.setLess(LessTarget.Days(days), LessLevel.NEVER)
    roundsUntil("le scelte arrivano sul tablet", phone, tablet) {
      val c = tabletLive.personal.current()
      memory.id.value in c.seen && MemoryIds.of(dismissedKey).value in c.dismissed && c.people[person] == MemoryLessLevel.LESS &&
        c.neverDays.any { it.from == MemoryDays.dayOf(days.from) && it.to == MemoryDays.dayOf(days.to) }
    }
    assertThat(tabletLive.personal.current().seen[memory.id.value]).isEqualTo(MemoryDays.dayOf(TODAY))

    // ...e all'indietro: dal tablet "Mai" per la persona e "Annulla" del "Non mostrare".
    tabletMemories.memories.setLess(LessTarget.Person(person), LessLevel.NEVER)
    tabletMemories.memories.dismiss(MemoryIds.of(dismissedKey), dismissed = false)
    roundsUntil("le scelte del tablet arrivano sul telefono", phone, tablet) {
      val c = phoneLive.personal.current()
      c.people[person] == MemoryLessLevel.NEVER && MemoryIds.of(dismissedKey).value !in c.dismissed
    }

    // Gli scaffali restano diversi: le scelte viaggiano, lo scaffale no (il tablet non ha ricevuto il ricordo del telefono).
    assertThat(tabletMemories.memories.shelf.value.items.map { it.id }).doesNotContain(memory.id)

    // 4. Nessun nome nei dati personali dei ricordi, ne' nello scaffale, ne' sul NAS fra i file che parlano di ricordi.
    val nameBytes = NAME.toByteArray(Charsets.UTF_8)
    for (node in listOf(phone, tablet)) {
      val entries = checkNotNull(node.nas.organizer.parts).personalStore.entries.value
      val memoriesEntries = entries.filterKeys { MemoriesRecords.isMemoriesKey(it) }
      assertWithMessage("${node.name}: le scelte dei ricordi").that(memoriesEntries).isNotEmpty()
      for ((k, v) in memoriesEntries) assertWithMessage("${node.name}: una voce dei ricordi con il nome").that("$k $v").doesNotContain(NAME)
      for (file in File(node.noBackup, MemoriesWiring.DIR).walkTopDown().filter { it.isFile }) {
        assertWithMessage("${node.name}: ${file.name} con il nome").that(contains(file.readBytes(), nameBytes)).isFalse()
      }
    }
    for (path in filesUnder(faces.backend, TestNas.ADMIN_USER, ".mycloudgallery")) {
      assertWithMessage("niente dello scaffale sul NAS: $path").that(path.contains("shelf") || path.contains("/memories")).isFalse()
    }
  }

  private suspend fun compute(node: FacesNode) {
    val stages = FacesStages(node.device.graph, node.faces, node.noBackup)
    withTimeout(Attese.TETTO) { while (node.live().counters.facesSaved.get() < FACES_EXPECTED) stages.compute(user = true) }
  }

  private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return true
    }
    return false
  }

  private companion object {
    const val PHOTOS = 6
    const val FACES_EXPECTED = 11
    const val NAME = "Persona di prova dei ricordi"
    val TODAY: LocalDate = FACES_TODAY.date()
  }
}
