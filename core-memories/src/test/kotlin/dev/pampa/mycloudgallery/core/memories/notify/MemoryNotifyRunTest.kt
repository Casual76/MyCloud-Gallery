package dev.pampa.mycloudgallery.core.memories.notify

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyMark
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifySkip
import dev.pampa.mycloudgallery.core.memories.contract.NotifyTexts
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.memories.notify.NotifyFixtures.dayInt
import dev.pampa.mycloudgallery.core.memories.plan.dayShots
import dev.pampa.mycloudgallery.core.memories.plan.fakeTrips
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.testing.ProcessDied
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il giorno di una notifica sul motore vero dei ricordi (docs/fase9-progetto.md §2.6; E-NT1-E-NT7, E-NT12, E-NT14; D4, D15, D19, D20): un ricordo
 * "un anno fa" di 12 foto sulla Libreria sintetica, e le porte del sistema finte. Date relative a "oggi" (3 novembre 2026), nessun dato personale.
 */
class MemoryNotifyRunTest {

  private val today = LocalDate.of(2026, 11, 3)
  private val rigs = ArrayList<NotifyRig>()
  private val dirs = ArrayList<File>()

  @After
  fun chiudiTutto() {
    rigs.forEach { it.close() }
    dirs.forEach { it.deleteRecursively() }
  }

  private fun rig(
    prefs: NotifyPrefs = NotifyPrefs(),
    photos: Int = 12,
    access: NotifyAccessState = NotifyAccessState.GRANTED,
    ledger: dev.pampa.mycloudgallery.core.memories.contract.NotifyLedgerStore? = null,
    visibility: ((dev.pampa.mycloudgallery.core.library.testing.FakeVisibility) -> MemoryVisibility)? = null,
  ): NotifyRig {
    val shots = dayShots(today.minusYears(1), photos, series = 1)
    val rig = if (ledger == null) {
      NotifyRig(shots, today, prefs, access = access, visibilityOverride = visibility)
    } else {
      NotifyRig(shots, today, prefs, access = access, ledger = ledger, visibilityOverride = visibility)
    }
    return rig.also { rigs += it }
  }

  // --- Un giorno normale ---------------------------------------------------------------------------------------------------------------

  @Test
  fun unGiornoNormaleUnaNotificaGenericaEIlLavoroRifatto() = runTest {
    val rig = rig()
    val outcome = rig.run().run()
    assertThat(outcome).isEqualTo(NotifyOutcome.Posted(special = false, late = false))
    val notice = rig.publisher.published.single()
    // Di serie (Q5 (a)): "Un ricordo per te", niente del ricordo.
    assertThat(notice.mode).isEqualTo(LockScreenMode.GENERIC)
    assertThat(notice.content.title.key).isEqualTo(NotifyTexts.GENERIC_TITLE)
    assertThat(notice.content.image).isNull()
    // Il registro del giorno, la Diagnostica e il lavoro di domani alla stessa ora.
    assertThat(rig.ledger.read().notifiedOn(today)).isTrue()
    assertThat(rig.events.delivered).isEqualTo(1)
    assertThat(rig.scheduler.lastNext).isEqualTo(today.plusDays(1).atTime(19, 0))
    // Il passo leggero ha preparato i ricordi: la notte non c'era stata (D4).
    assertThat(rig.counting.refreshes).containsExactly(RefreshReason.LIGHT)
  }

  /** D4: se la notte ha gia' generato i ricordi di oggi, il passo leggero non gira. */
  @Test
  fun ilPassoLeggeroSoloSeLaNotteNonCeStata() = runTest {
    val rig = rig()
    rig.night()
    rig.counting.refreshes.clear()
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Posted(special = false, late = false))
    assertThat(rig.counting.refreshes).isEmpty()
  }

  /** E-NT5: due esecuzioni nello stesso giorno (riavvio, ripianificazione, aggiornamento dell'app): al piu' una notifica. */
  @Test
  fun dueCorseNelloStessoGiornoUnaNotifica() = runTest {
    val rig = rig()
    assertThat(rig.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
    rig.at(today, LocalTime.of(19, 20))
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.ALREADY_TODAY))
    assertThat(rig.publisher.published).hasSize(1)
    assertThat(rig.events.skips).containsExactly(NotifySkip.ALREADY_TODAY)
  }

  // --- Finestra e salto (E-NT3) -------------------------------------------------------------------------------------------------

  @Test
  fun laFinestraELOltreHPiuTreOre() = runTest {
    val late = rig().also { it.at(today, LocalTime.of(21, 30)) }
    assertThat(late.run().run()).isEqualTo(NotifyOutcome.Posted(special = false, late = true))
    assertThat(late.events.late).isEqualTo(1)

    val tooLate = rig().also { it.at(today, LocalTime.of(22, 5)) }
    assertThat(tooLate.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.OUTSIDE_WINDOW))
    assertThat(tooLate.publisher.published).isEmpty()
    // Oltre H+3 h quel giorno si salta e si ripianifica per domani.
    assertThat(tooLate.scheduler.lastNext).isEqualTo(today.plusDays(1).atTime(19, 0))
    assertThat(tooLate.ledger.read().notifiedOn(today)).isFalse()
  }

  @Test
  fun unaCorsaInAnticipoSiRipianificaSenzaDireSaltato() = runTest {
    val rig = rig().also { it.at(today, LocalTime.of(18, 58)) }
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.NotYet)
    assertThat(rig.events.skips).isEmpty()
    assertThat(rig.scheduler.lastNext).isEqualTo(today.atTime(19, 0))
  }

  // --- Permesso, canale, impostazioni (E-NT1, E-NT2, D18) ---------------------------------------------------------------------------

  /** Permesso negato o canale spento: nessuna notifica, un codice, e **nessuna richiesta** (la porta si rilegge soltanto). */
  @Test
  fun permessoNegatoECanaleSpentoNonChiedonoNiente() = runTest {
    for ((state, reason) in listOf(
      NotifyAccessState.PERMISSION_MISSING to NotifySkip.PERMISSION,
      NotifyAccessState.APP_OFF to NotifySkip.PERMISSION,
      NotifyAccessState.CHANNEL_OFF to NotifySkip.CHANNEL,
    )) {
      val rig = rig(access = state).also { it.access.system = state }
      assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(reason))
      assertThat(rig.publisher.published).isEmpty()
      assertThat(rig.ledger.read().notifiedOn(today)).isFalse()
      assertThat(rig.events.skips).containsExactly(reason)
      // Rilegge lo stato dal sistema, tutto qui; il lavoro riparte domani.
      assertThat(rig.access.refreshes).isEqualTo(1)
      assertThat(rig.scheduler.lastNext).isEqualTo(today.plusDays(1).atTime(19, 0))
    }
  }

  /** Il permesso tolto dall'utente fra due corse: la corsa lo rilegge dal sistema e non notifica. */
  @Test
  fun ilPermessoTolto() = runTest {
    val rig = rig()
    rig.access.system = NotifyAccessState.PERMISSION_MISSING
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.PERMISSION))
    rig.access.system = NotifyAccessState.GRANTED
    assertThat(rig.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
  }

  @Test
  fun notificheSpenteSiToglieIlLavoroEQuellaInTendina() = runTest {
    val rig = rig()
    assertThat(rig.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    try {
      val run = rig.run()
      run.watch(scope)
      // All'avvio rifa' il lavoro; al cambio dell'ora lo rifa' per la nuova ora (oggi alle 20:15: il registro dira' "gia' fatto" e si ripianifichera'); spente: lavoro tolto e notifica tolta.
      assertThat(rig.scheduler.lastNext).isEqualTo(today.plusDays(1).atTime(19, 0))
      rig.settings.set(NotifyPrefs(hour = LocalTime.of(20, 15)))
      assertThat(rig.scheduler.lastNext).isEqualTo(today.atTime(20, 15))
      rig.settings.set(NotifyPrefs(enabled = false))
      assertThat(rig.scheduler.lastNext).isNull()
      assertThat(rig.scheduler.cancelled).isEqualTo(1)
      assertThat(rig.publisher.current).isNull()
      assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.DISABLED))
    } finally {
      scope.cancel()
    }
  }

  // --- Cosa si sceglie (E-NT6) -----------------------------------------------------------------------------------------------------

  @Test
  fun niente_diBelloOggiNienteNotifica() = runTest {
    val rig = rig(photos = 6)
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    assertThat(rig.publisher.published).isEmpty()
    assertThat(rig.ledger.read().notifiedOn(today)).isFalse()
  }

  /** Q4: il giorno dopo un'altra notifica un ricordo non speciale aspetta ("frequenza"), e il codice lo dice. */
  @Test
  fun laFrequenzaDiQ4() = runTest {
    val rig = rig()
    rig.ledger.record(NotifyMark(dayInt(today.minusDays(1)), special = false), today)
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.FREQUENCY))
    assertThat(rig.publisher.published).isEmpty()
    // Con "una al giorno" (DAILY) arriva anche il giorno dopo.
    val daily = rig(prefs = NotifyPrefs(frequency = NotifyFrequency.DAILY))
    daily.ledger.record(NotifyMark(dayInt(today.minusDays(1)), special = false), today)
    assertThat(daily.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
  }

  /** D15: se l'utente ha gia' aperto i Ricordi oggi (un ricordo visto oggi), niente notifica. */
  @Test
  fun iRicordiGiaApertiOggi() = runTest {
    val rig = rig()
    rig.night()
    rig.engine.engine.markSeen(rig.engine.engine.shelf.value.items.first().id)
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.ALREADY_OPENED))
    assertThat(rig.publisher.published).isEmpty()
  }

  /** D15: un ricordo scartato ("Non mostrare") non e' mai la notifica; una notifica a un ricordo visto un anno fa nemmeno (non e' sullo scaffale). */
  @Test
  fun unRicordoScartatoNonEMaiLaNotifica() = runTest {
    val rig = rig()
    rig.night()
    rig.engine.engine.dismiss(rig.engine.engine.shelf.value.items.first().id)
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    assertThat(rig.publisher.published).isEmpty()
  }

  /** D15, Q4: l'anniversario di un viaggio e' "speciale": arriva anche il giorno dopo un'altra notifica e il registro lo segna. */
  @Test
  fun unAnniversarioDiViaggioArrivaAncheOltreIlTetto() = runTest {
    val from = today.minusYears(1).minusDays(1)
    val shots = (0 until 3).flatMap { dayShots(from.plusDays(it.toLong()), 5, series = 20L + it) }
    val digest = TripDigestView(TripKind.TRIP, DayRange(from, from.plusDays(2)), "Viaggio sintetico", listOf(3_200_000), shots.map { it.id }, null, null)
    val rig = NotifyRig(shots, today, trips = fakeTrips(digest)).also { rigs += it }
    rig.ledger.record(NotifyMark(dayInt(today.minusDays(1)), special = false), today)
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Posted(special = true, late = false))
    assertThat(rig.ledger.read().marks.last().special).isTrue()
  }

  // --- Contenuto (D16) ------------------------------------------------------------------------------------------------------------

  /** "Titolo e foto": titolo come id di stringa con gli anni, e la copertina (una foto visibile del ricordo). */
  @Test
  fun titoloEFoto() = runTest {
    val rig = rig(prefs = NotifyPrefs(lockScreen = LockScreenMode.TITLE_AND_PHOTO))
    assertThat(rig.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
    val content = rig.publisher.published.single().content
    assertThat(content.title.key).isEqualTo(NotifyTexts.TITLE_YEARS)
    assertThat(content.title.numbers).containsExactly(1)
    val members = rig.engine.engine.shelf.value.items.single().cover
    assertThat(content.image).isEqualTo(members)
    assertThat(content.publicTitle.key).isEqualTo(NotifyTexts.GENERIC_TITLE)
    assertThat(content.publicText.key).isEqualTo(NotifyTexts.GENERIC_TEXT)
  }

  // --- Nascosti (D19, D31) -----------------------------------------------------------------------------------------------------------

  /** In "Titolo e foto" un ricordo con la copertina nascosta non e' un candidato; in modalita' generica la copertina non conta. */
  @Test
  fun laCopertinaNascostaEsclude_soloInTitoloEFoto() = runTest {
    val titled = rig(prefs = NotifyPrefs(lockScreen = LockScreenMode.TITLE_AND_PHOTO))
    titled.night()
    val card = titled.engine.engine.shelf.value.items.single()
    titled.hide(setOf(card.cover))
    assertThat(titled.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    assertThat(titled.publisher.published).isEmpty()

    val generic = rig()
    generic.night()
    generic.hide(setOf(generic.engine.engine.shelf.value.items.single().cover))
    assertThat(generic.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
  }

  /**
   * Sotto 8 elementi visibili (nascosti contati **adesso**, non quelli dell'istantanea) non e' una notifica: la scelta ricontrolla i nascosti con
   * il filtro finale, anche se l'istantanea della Libreria non e' ancora cambiata.
   */
  @Test
  fun iNascostiSiContanoPrimaDiPubblicare() = runTest {
    val rig = rig(photos = 9)
    rig.night()
    val memory = rig.engine.engine.memory(rig.engine.engine.shelf.value.items.single().id).first()!!.memory
    rig.hide(memory.members.take(2))
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    assertThat(rig.publisher.published).isEmpty()
    // Nascosto uno solo (restano 8) la notifica parte: il numero minimo e' 8.
    val enough = rig(photos = 9)
    enough.night()
    enough.hide(enough.engine.engine.memory(enough.engine.engine.shelf.value.items.single().id).first()!!.memory.members.take(1))
    assertThat(enough.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
  }

  /** R17: un guasto della lettura dei nascosti non fa uscire niente (fallire chiusi) e non e' un crollo. */
  @Test
  fun unGuastoDeiNascostiNonFaUscireNiente() = runTest {
    val failing: (dev.pampa.mycloudgallery.core.library.testing.FakeVisibility) -> MemoryVisibility = { fake ->
      object : MemoryVisibility {
        override val snapshot: kotlinx.coroutines.flow.StateFlow<TimelineSnapshot?> get() = fake.snapshot

        override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = throw java.io.IOException("lettura fallita")

        override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = throw java.io.IOException("lettura fallita")
      }
    }
    val rig = rig(visibility = failing)
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    assertThat(rig.publisher.published).isEmpty()
    assertThat(rig.ledger.read().notifiedOn(today)).isFalse()
  }

  // --- Il registro (E-NT5) ------------------------------------------------------------------------------------------------------------

  /** Il registro si scrive **prima** della notifica: se il processo muore fra le due cose quel giorno resta senza, e la corsa dopo non ne fa una. */
  @Test
  fun laMorteFraIlRegistroELaNotifica() = runTest {
    val rig = rig()
    rig.publisher.dieAtNext = true
    assertThrows(ProcessDied::class.java) { runBlocking { rig.run().run() } }
    assertThat(rig.publisher.published).isEmpty()
    assertThat(rig.ledger.read().notifiedOn(today)).isTrue()
    // Il processo riparte e il lavoro gira di nuovo nella finestra: niente seconda possibilita' oggi.
    rig.at(today, LocalTime.of(19, 40))
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.ALREADY_TODAY))
    assertThat(rig.publisher.published).isEmpty()
    // Domani si riparte (il lavoro e' stato rifatto anche dalla corsa morta: `finally`).
    assertThat(rig.scheduler.lastNext).isEqualTo(today.plusDays(1).atTime(19, 0))
  }

  /** La morte **prima** della scrittura del registro: non e' partito niente e non e' scritto niente; la corsa dopo notifica (una volta). */
  @Test
  fun laMorteDuranteLaScritturaDelRegistro() = runTest {
    val dying = DyingLedger(dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifyLedgerStore())
    val rig = rig(ledger = dying)
    dying.dieBefore = true
    assertThrows(ProcessDied::class.java) { runBlocking { rig.run().run() } }
    assertThat(rig.publisher.published).isEmpty()
    assertThat(dying.read().notifiedOn(today)).isFalse()
    assertThat(rig.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
    assertThat(rig.publisher.published).hasSize(1)
  }

  /** Un registro rovinato (file) non si legge: quel giorno non si notifica, il file si rimette a posto e il giorno dopo il lavoro riparte. */
  @Test
  fun unRegistroRovinatoSaltaIlGiornoEPoSiRipara() = runTest {
    val dir = Files.createTempDirectory("registro").toFile().also { dirs += it }
    File(dir, FileNotifyLedgerStore.FILE_NAME).writeText("rovinato")
    val store = FileNotifyLedgerStore(dir, Dispatchers.Unconfined)
    val tomorrow = today.plusDays(1)
    val shots = dayShots(today.minusYears(1), 12, series = 1) + dayShots(tomorrow.minusYears(1), 12, series = 2)
    val rig = NotifyRig(shots, today, ledger = store).also { rigs += it }
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.ALREADY_TODAY))
    assertThat(rig.publisher.published).isEmpty()
    assertThat(store.read()).isEqualTo(NotifyLedger())
    rig.at(tomorrow)
    assertThat(rig.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
    assertThat(rig.publisher.publishedOn(dayInt(tomorrow))).isEqualTo(1)
  }

  /** Un guasto del passo leggero non ferma la corsa e non butta lo scaffale: niente da notificare oggi, nessun crollo. */
  @Test
  fun unGuastoDelPassoLeggeroNonFermaLaCorsa() = runTest {
    val rig = rig()
    rig.counting.failRefresh = true
    assertThat(rig.run().run()).isEqualTo(NotifyOutcome.Skipped(NotifySkip.NOTHING))
    assertThat(rig.counting.refreshes).containsExactly(RefreshReason.LIGHT)
    assertThat(rig.publisher.published).isEmpty()
    assertThat(rig.scheduler.lastNext).isEqualTo(today.plusDays(1).atTime(19, 0))
  }
}
