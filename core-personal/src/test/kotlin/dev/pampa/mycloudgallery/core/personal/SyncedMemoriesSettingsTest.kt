package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesRecords
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.legacy.LegacyPersonalSegments
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMemoriesSettings
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalStore
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le scelte sui ricordi (Fase 9, P6; D10, D11, Q9, E-MEM8, E-MEM9, E-MEM22) sul **motore vero**: due dispositivi dell'account con lo store, il
 * registro e la sincronizzazione dei dati personali veri. La suite di contratto di P0 gira anche qui; in piu': un'app della Fase 8 sullo stesso
 * account conserva i campi nuovi, la scelta fatta senza l'ora del NAS vale subito ed e' "da datare", e togliere una scelta che non c'e' non scrive niente.
 */
class SyncedMemoriesSettingsTest : MemoriesPersonalSettingsContract() {

  override fun rig(): Rig = RealRig()

  private class RealRig : Rig {
    private val world = PersonalWorld()
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val deviceA = world.device()
    private val deviceB = world.device()
    private var remoteTime = world.clock.now + 60_000L
    private val thirdDevice = TrustIds.random()

    init {
      runBlocking {
        deviceA.store.open()
        deviceB.store.open()
      }
    }

    override val a: MemoriesPersonalSettings = SyncedMemoriesSettings(deviceA.store, scope)
    override val b: MemoriesPersonalSettings = SyncedMemoriesSettings(deviceB.store, scope)

    override fun aOnline(online: Boolean) {
      deviceA.anchored = online
    }

    override suspend fun syncAll() {
      for (device in listOf(deviceA, deviceB, deviceA)) {
        world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
        device.syncOnce()
      }
    }

    override suspend fun remoteWrites(key: PersonalKey, value: PersonalValue) {
      // Un terzo dispositivo dell'account, con un'ora piu' recente di tutto cio' che si sa.
      remoteTime += 60_000L
      deviceA.store.applyRemote(listOf(PersonalEntry(key, remoteTime, thirdDevice, value)))
    }

    override fun liveValues(): Map<PersonalKey, PersonalValue> =
      runBlocking { deviceA.store.state() }.filterValues { it.value != PersonalValue.Removed }.mapValues { it.value.value }
  }

  private val anna = PersonId.fromBytes(ByteArray(16) { (it + 17).toByte() })

  private fun id(n: Int): String = Base32.encode(MessageDigest.getInstance("SHA-256").digest("scelta-$n".toByteArray()).copyOf(16))

  /** C31 della Fase 8: un'app senza la Fase 9 legge i segmenti scritti dal motore vero con il suo codec, senza scartarne nessuno, e li conserva identici. */
  @Test
  fun unAppDellaFase8ConservaLeVociNuove() = runTest {
    val world = PersonalWorld()
    val device = world.device()
    device.store.open()
    val settings = SyncedMemoriesSettings(device.store, CoroutineScope(Dispatchers.Unconfined + Job()))
    settings.markSeen(id(1), 20261027)
    settings.setDismissed(id(2), true)
    settings.setLess(anna, MemoryLessLevel.LESS)
    settings.setNeverDays(MemoryDays(20240301, 20240315), true)
    settings.setPeopleMemories(false)
    val state = device.store.state().values.toList()
    assertThat(state).hasSize(5)
    val plan = PersonalSegments.plan(state)
    assertThat(plan.skipped).isEqualTo(0)
    val old = plan.segments.flatMap { LegacyPersonalSegments.decode(it.bytes, it.ref).acceptedOrNull() ?: error("segmento scartato dall'app vecchia") }
    assertThat(old).containsExactlyElementsIn(state)
    // E l'app vecchia che ripubblica scrive gli stessi byte: le voci nuove non si perdono passando da lei.
    assertThat(LegacyPersonalSegments.plan(state).segments.map { it.ref.hash }).containsExactlyElementsIn(plan.segments.map { it.ref.hash })
  }

  /** E-MEM22: senza l'ora del NAS la scelta vale subito qui, e' "da datare" (non e' nello stato che si pubblica), e la prima sincronizzazione la data e la porta. */
  @Test
  fun leSceltaSenzaOraDelNasValgonoSubitoESonoDaDatare() = runTest {
    val world = PersonalWorld()
    val device = world.device().also { it.anchored = false }
    device.store.open()
    val settings = SyncedMemoriesSettings(device.store, CoroutineScope(Dispatchers.Unconfined + Job()))
    settings.markSeen(id(1), 20261027)
    settings.setDismissed(id(2), true)
    settings.setLess(anna, MemoryLessLevel.NEVER)
    assertThat(settings.current().seen).containsExactly(id(1), 20261027)
    assertThat(settings.current().dismissed).containsExactly(id(2))
    assertThat(settings.choices.value.people).containsExactly(anna, MemoryLessLevel.NEVER)
    assertThat(device.store.undated()).containsExactly(MemoriesRecords.seenKey(id(1)), MemoriesRecords.dismissedKey(id(2)), MemoriesRecords.lessKey(anna))
    assertThat(device.store.entries.value).isEmpty()
    device.anchored = true
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    device.syncOnce()
    assertThat(device.store.undated()).isEmpty()
    assertThat(settings.current().dismissed).containsExactly(id(2))
  }

  /** Un nuovo processo (stesso registro, ancora senza ora del NAS): le scelte fatte fuori casa ci sono ancora. */
  @Test
  fun leSceltaFuoriCasaSopravvivonoAlRiavvio() = runTest {
    val world = PersonalWorld()
    val first = world.device().also { it.anchored = false }
    first.store.open()
    val settings = SyncedMemoriesSettings(first.store, CoroutineScope(Dispatchers.Unconfined + Job()))
    settings.setDismissed(id(3), true)
    settings.setNeverDays(MemoryDays(20250101, 20250131), true)
    val restarted = world.device(ledger = first.ledger, signer = first.signer).also { it.anchored = false }
    restarted.store.open()
    val after = SyncedMemoriesSettings(restarted.store, CoroutineScope(Dispatchers.Unconfined + Job()))
    assertThat(after.current().dismissed).containsExactly(id(3))
    assertThat(after.current().neverOn(20250115)).isTrue()
  }

  /** Togliere una scelta che non c'e' non scrive niente: nessuna lapide a vuoto, nessuna voce da datare. */
  @Test
  fun togliereUnaSceltaCheNonCeNonScriveNiente() = runTest {
    val world = PersonalWorld()
    val device = world.device()
    device.store.open()
    val settings = SyncedMemoriesSettings(device.store, CoroutineScope(Dispatchers.Unconfined + Job()))
    settings.setDismissed(id(1), false)
    settings.setLess(anna, null)
    settings.setNeverDays(MemoryDays(20240101, 20240110), false)
    assertThat(device.store.state()).isEmpty()
    assertThat(device.store.undated()).isEmpty()
    // Una scelta che c'e' si toglie con una lapide, e una seconda volta non scrive di nuovo.
    settings.setLess(anna, MemoryLessLevel.LESS)
    settings.setLess(anna, null)
    val stamp = device.store.state().getValue(MemoriesRecords.lessKey(anna))
    assertThat(stamp.value).isEqualTo(PersonalValue.Removed)
    settings.setLess(anna, null)
    assertThat(device.store.state().getValue(MemoriesRecords.lessKey(anna))).isEqualTo(stamp)
  }

  /**
   * Due dispositivi finti sullo store in memoria (D10, E-MEM8): "visto" e "Non mostrare" fatti su uno arrivano all'altro; l'ultima scrittura per ora del
   * NAS vince (il tablet annulla, il telefono riscarta piu' tardi: un solo vincitore, uguale ovunque). Il caso senza l'ora del NAS sta sullo store vero
   * ([leSceltaSenzaOraDelNasValgonoSubitoESonoDaDatare], la suite di contratto): il fake non ha lo stato "da datare" nelle letture.
   */
  @Test
  fun vistiEScartatiViaggianoFraDueStoreFinti() = runTest {
    var nas = 1_790_000_000_000L
    val storeA = InMemoryPersonalStore(TrustIds.random()) { nas }
    val storeB = InMemoryPersonalStore(TrustIds.random()) { nas }
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    val phone = SyncedMemoriesSettings(storeA, scope)
    val tablet = SyncedMemoriesSettings(storeB, scope)
    phone.markSeen(id(1), 20261027)
    phone.setDismissed(id(2), true)
    assertThat(tablet.current().dismissed).isEmpty()
    storeB.applyRemote(storeA.entries.value.values.toList())
    assertThat(tablet.current().seen).containsExactly(id(1), 20261027)
    assertThat(tablet.current().dismissed).containsExactly(id(2))
    nas += 100_000
    tablet.setDismissed(id(2), false)
    storeA.applyRemote(storeB.entries.value.values.toList())
    assertThat(phone.current().dismissed).isEmpty()
    nas += 100_000
    phone.setDismissed(id(2), true)
    storeB.applyRemote(storeA.entries.value.values.toList())
    assertThat(tablet.current().dismissed).containsExactly(id(2))
    assertThat(phone.current()).isEqualTo(tablet.current())
  }

  /** `dropAllPeopleChoices` toglie tutte le scelte sulle persone, anche quelle ancora "da datare" (fuori casa), e risponde quante; i periodi restano. */
  @Test
  fun iLapidiDelJanitorToglionoAncheLeSceltaDaDatare() = runTest {
    val world = PersonalWorld()
    val device = world.device().also { it.anchored = false }
    device.store.open()
    val settings = SyncedMemoriesSettings(device.store, CoroutineScope(Dispatchers.Unconfined + Job()))
    settings.setLess(anna, MemoryLessLevel.LESS)
    settings.setLess(PersonId.fromBytes(ByteArray(16) { 99 }), MemoryLessLevel.NEVER)
    settings.setNeverDays(MemoryDays(20240101, 20240110), true)
    assertThat(settings.current().people).hasSize(2)
    assertThat(settings.dropAllPeopleChoices()).isEqualTo(2)
    assertThat(settings.current().people).isEmpty()
    assertThat(settings.current().neverOn(20240105)).isTrue()
    assertThat(settings.dropAllPeopleChoices()).isEqualTo(0)
  }
}
