package dev.pampa.mycloudgallery.navigation

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.personal.contract.FullHashPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.settings.SyncedCollectionsSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedFullHashSettings
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.feature.library.collections.CollectionSections
import dev.pampa.mycloudgallery.core.personal.store.LedgerSnapshot
import dev.pampa.mycloudgallery.core.personal.store.PersonalLedger
import dev.pampa.mycloudgallery.integration.Attese
import dev.pampa.mycloudgallery.integration.until
import dev.pampa.mycloudgallery.utility.PersonalFullHashSwitch
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * K5 (regressione di K3b): le porte di `:app` sopra il registro dei dati personali **vero** (`LedgerPersonalStore`) quando la memoria del telefono e' piena. Una scrittura
 * che non riesce deve arrivare, come eccezione, a chi sta sopra e sa che cosa farne (`CollectionsLayoutController` rimette l'ordine di prima e lo dice,
 * `DuplicatesController` dice "non salvato"): prima di K5 il registro rispondeva "fatto" e la scelta dell'utente spariva senza una parola.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PersonalWritesFullStorageTest {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val ledger = RefusalCountingLedger(InMemoryPersonalLedger())
  private val store = LedgerPersonalStore(Base32.encode(ByteArray(16) { 1 }), { NAS_NOW }, ledger)

  @After
  fun tearDown() {
    scope.cancel()
  }

  private suspend fun notSaved(write: suspend () -> Unit): Boolean = try {
    write()
    false
  } catch (_: IOException) {
    true
  }

  @Test
  fun lOrdineDelleRaccolteCheNonSiSalvaArrivaAChiLoHaChiesto_eLOrdineRestaQuelloDiPrima() = runBlocking<Unit> {
    val layout = AppCollectionsLayoutStore(MutableStateFlow<PersonalStore?>(store), scope)
    val before = listOf(CollectionSections.UTILITY, CollectionSections.ALBUMS)
    layout.setOrder(before)
    withTimeout(Attese.TETTO) { layout.order.first { it == before } }

    ledger.full = true
    // `CollectionsLayoutController.reorder` e `setShown` sono `if (attempt { store.setOrder(...) })`: l'eccezione e' il loro "non salvato".
    assertThat(notSaved { layout.setOrder(listOf(CollectionSections.ALBUMS, CollectionSections.UTILITY)) }).isTrue()
    assertThat(notSaved { layout.setHidden(CollectionSections.TRIPS, true) }).isTrue()
    assertThat(layout.order.value).isEqualTo(before)
    assertThat(layout.hidden.value).isEmpty()
    assertThat(SyncedCollectionsSettings(store, scope).order.value).isEqualTo(before)

    // Liberato lo spazio, la stessa scelta riesce.
    ledger.full = false
    layout.setHidden(CollectionSections.TRIPS, true)
    withTimeout(Attese.TETTO) { layout.hidden.first { it == setOf(CollectionSections.TRIPS) } }
  }

  @Test
  fun unaSceltaFattaPrimaDelRegistroRestaDellUtenteFincheIlRegistroNonSiScrive() = runBlocking<Unit> {
    val stores = MutableStateFlow<PersonalStore?>(null)
    val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val layout = AppCollectionsLayoutStore(stores, work, retryMs = RETRY_MS)
    val mine = listOf(CollectionSections.UTILITY, CollectionSections.ALBUMS)
    layout.setOrder(mine)
    withTimeout(Attese.TETTO) { layout.order.first { it == mine } }

    // Il registro compare con la memoria piena: la consegna fallisce (e si ritenta), la scelta resta quella dell'utente.
    ledger.full = true
    stores.value = store
    val synced = SyncedCollectionsSettings(store, work)
    until("la consegna e' stata rifiutata") { ledger.refused.get() >= 1 }
    assertThat(layout.order.value).isEqualTo(mine)
    assertThat(synced.order.value).isEmpty()

    // Lo spazio torna: la consegna ritentata riesce da sola.
    ledger.full = false
    withTimeout(Attese.TETTO) { synced.order.first { it == mine } }
    assertThat(layout.order.value).isEqualTo(mine)
    work.cancel()
  }

  @Test
  fun lImprontaDiNotteCheNonSiSalvaNonParteENonRestaAccesa() = runBlocking<Unit> {
    val applied = AtomicInteger()
    val switch = PersonalFullHashSwitch(MutableStateFlow<FullHashPersonalSettings?>(SyncedFullHashSettings(store, scope)), scope) { applied.incrementAndGet() }

    ledger.full = true
    // `DuplicatesController.setNightly` prende l'eccezione e mostra "non salvato" (DP-5).
    assertThat(notSaved { switch.set(true) }).isTrue()
    assertThat(switch.enabled.value).isFalse()
    assertThat(applied.get()).isEqualTo(0)

    ledger.full = false
    switch.set(true)
    withTimeout(Attese.TETTO) { switch.enabled.first { it } }
    assertThat(applied.get()).isEqualTo(1)
  }

  private companion object {
    const val NAS_NOW = 1_760_000_000_000L

    /** Fra un tentativo di consegna e il successivo: largo, cosi' la prova libera lo spazio molto prima che i venti tentativi finiscano anche su un PC carico. */
    const val RETRY_MS = 2_000L
  }
}

/** Il registro in memoria di prova, che conta le scritture rifiutate (memoria piena). */
private class RefusalCountingLedger(private val inner: InMemoryPersonalLedger) : PersonalLedger {
  val refused = AtomicInteger()

  var full: Boolean
    get() = inner.full
    set(value) {
      inner.full = value
    }

  override suspend fun load(): LedgerSnapshot = inner.load()

  override suspend fun save(snapshot: LedgerSnapshot) {
    try {
      inner.save(snapshot)
    } catch (e: IOException) {
      refused.incrementAndGet()
      throw e
    }
  }
}
