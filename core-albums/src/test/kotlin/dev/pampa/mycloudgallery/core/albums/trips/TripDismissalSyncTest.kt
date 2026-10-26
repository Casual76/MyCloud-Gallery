package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * `trip_dismissed` SINCRONIZZATO FRA I DISPOSITIVI DELL'UTENTE, TRAMITE I DATI PERSONALI (docs/fase7-progetto.md D4, D20; riga P7 del §4.4).
 * Due dispositivi dello stesso account hanno ognuno il suo store dei dati personali (`LedgerPersonalStore`, il vero) e le sue proposte; lo
 * scambio e' quello della sincronizzazione: lo stato di uno diventa un **segmento `personal` vero** (`PersonalSegments`: lo stesso codec che
 * firma e pubblica la testa) e l'altro lo applica. Teste e NAS li prova `core-personal`; qui conta che "Non mi interessa" sia una voce che
 * attraversa quel giro e che dopo il giro l'altro dispositivo non la riproponga piu'.
 */
class TripDismissalSyncTest {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val nas = AtomicLong(1_760_000_000_000L)

  private class Device(val store: LedgerPersonalStore, val harness: TripsHarness) : AutoCloseable {
    override fun close() = harness.close()
  }

  private fun device(story: Story, scope: CoroutineScope, nas: AtomicLong): Device {
    val store = LedgerPersonalStore(TrustIds.random(), { nas.get() }, InMemoryPersonalLedger())
    val dismissals = PersonalDismissedTrips(store, scope)
    return Device(store, TripsHarness(StoryLibrary(story.shots), story.today, dismissals = dismissals))
  }

  /** Il giro dei dati personali: lo stato di [from] passa per un segmento vero e lo applica [to]. */
  private suspend fun sync(from: Device, to: Device) {
    val entries = from.store.state().values.toList()
    if (entries.isEmpty()) return
    val segment = PersonalSegments.encode(entries)
    val back = (PersonalSegments.decode(segment.bytes, segment.ref) as Verdict.Accepted).value
    to.store.applyRemote(back)
  }

  @After
  fun stop() {
    scope.cancel()
  }

  @Test
  fun unaPropostaScartataSuUnDispositivoNonTornaSuQuelloDopo() = runBlocking<Unit> {
    val story = TripStories.all().first { it.name.startsWith("Firenze") }
    device(story, scope, nas).use { phone ->
      device(story, scope, nas).use { tablet ->
        phone.harness.trips.detect(1.minutes)
        tablet.harness.trips.detect(1.minutes)
        val ids = phone.harness.trips.proposals.value.proposals.map { it.id }
        assertThat(tablet.harness.trips.proposals.value.proposals.map { it.id }).containsExactlyElementsIn(ids)
        val victim = ids.first()

        phone.harness.trips.dismiss(victim)
        assertThat(phone.harness.trips.proposals.value.proposals.map { it.id }).doesNotContain(victim)
        // Prima del giro il tablet non lo sa.
        assertThat(tablet.harness.trips.proposals.value.proposals.map { it.id }).contains(victim)

        sync(phone, tablet)
        assertThat(tablet.harness.trips.proposals.value.proposals.map { it.id }).doesNotContain(victim)
        // E non torna con una rilevazione nuova, ne' sul telefono ne' sul tablet.
        tablet.harness.trips.detect(1.minutes)
        phone.harness.trips.detect(1.minutes)
        assertThat(tablet.harness.trips.proposals.value.proposals.map { it.id }).doesNotContain(victim)
        assertThat(phone.harness.trips.proposals.value.proposals.map { it.id }).doesNotContain(victim)
        assertThat(tablet.harness.trips.proposals.value.proposals.map { it.id }).containsExactlyElementsIn(ids - victim)
      }
    }
  }

  @Test
  fun dueDispositiviCheScartanoProposteDiverseLeTengonoEntrambe() = runBlocking<Unit> {
    val story = TripStories.all().first { it.name.startsWith("Firenze") }
    device(story, scope, nas).use { phone ->
      device(story, scope, nas).use { tablet ->
        phone.harness.trips.detect(1.minutes)
        tablet.harness.trips.detect(1.minutes)
        val ids = phone.harness.trips.proposals.value.proposals.map { it.id }
        phone.harness.trips.dismiss(ids[0])
        tablet.harness.trips.dismiss(ids[1])
        nas.addAndGet(1_000)
        sync(phone, tablet)
        sync(tablet, phone)
        for (device in listOf(phone, tablet)) {
          assertThat(device.harness.trips.proposals.value.proposals.map { it.id }).containsExactlyElementsIn(ids.drop(2))
        }
      }
    }
  }

  /** La voce e' un'impostazione personale e basta: nessun dato di viaggio, di luogo o di casa nei dati personali (D18, C19). */
  @Test
  fun neiDatiPersonaliVaSoloLImprontaDellaPropostaScartata() = runBlocking<Unit> {
    val story = TripStories.all().first { it.name.startsWith("Firenze") }
    device(story, scope, nas).use { phone ->
      phone.harness.trips.detect(1.minutes)
      val proposals = phone.harness.trips.proposals.value.proposals
      proposals.forEach { phone.harness.trips.dismiss(it.id) }
      val state = phone.store.state()
      assertThat(state).hasSize(proposals.size)
      for ((key, entry) in state) {
        assertThat(key.kind).isEqualTo(PersonalKind.SETTING)
        assertThat(key.subject).isEqualTo(PersonalFields.SETTINGS_SUBJECT)
        assertThat(PersonalFields.tripDismissedId(key.field)).isNotNull()
        assertThat(entry.value).isEqualTo(PersonalValue.Flag(true))
        assertThat(entry.fingerprint).isNull()
      }
      assertThat(state.keys.mapNotNull { PersonalFields.tripDismissedId(it.field) }).containsExactlyElementsIn(proposals.map { it.id.value })
      // La stessa proposta rilevata da due dispositivi ha la stessa impronta: e' per questo che la scelta vale per tutti.
      val again = TripId.of(proposals.first().kind, proposals.first().days, proposals.first().placeIds)
      assertThat(again).isEqualTo(proposals.first().id)
      assertThat(proposals.map { it.kind }.toSet()).contains(TripKind.TRIP)
    }
  }

  /** Una voce che non e' un flag acceso (la lapide di un'altra versione, un testo, un'impronta sbagliata) non scarta niente: nel dubbio una proposta resta. */
  @Test
  fun leVociStraneNonScartanoNiente() = runBlocking<Unit> {
    val store = LedgerPersonalStore(TrustIds.random(), { nas.get() }, InMemoryPersonalLedger())
    val dismissed = PersonalDismissedTrips(store, scope)
    val id = TripId("a".repeat(64))
    val key = dev.pampa.mycloudgallery.core.personal.contract.PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.tripDismissed(id.value))
    store.set(key, PersonalValue.Text("si"))
    assertThat(dismissed.dismissed.value).isEmpty()
    store.set(key, PersonalValue.Flag(false))
    assertThat(dismissed.dismissed.value).isEmpty()
    store.set(key, PersonalValue.Flag(true))
    assertThat(dismissed.dismissed.value).containsExactly(id)
    store.set(key, PersonalValue.Removed)
    assertThat(dismissed.dismissed.value).isEmpty()
    // Un'impronta che non e' tale (non 64 cifre esadecimali minuscole) non e' una proposta.
    val bad = dev.pampa.mycloudgallery.core.personal.contract.PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.tripDismissed("xyz"))
    store.set(bad, PersonalValue.Flag(true))
    assertThat(dismissed.dismissed.value).isEmpty()
    // Un'altra impostazione o un altro tipo con lo stesso nome di campo non c'entra.
    val album = dev.pampa.mycloudgallery.core.personal.contract.PersonalKey(PersonalKind.ALBUM, "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", PersonalFields.tripDismissed(id.value))
    store.set(album, PersonalValue.Flag(true))
    assertThat(dismissed.dismissed.value).isEmpty()
  }
}
