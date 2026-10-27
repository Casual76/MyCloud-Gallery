package dev.pampa.mycloudgallery.navigation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.noop.NoAlbums
import dev.pampa.mycloudgallery.core.albums.noop.NoTrips
import dev.pampa.mycloudgallery.core.designsystem.state.PhoneTrash
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.testing.viewerItems
import dev.pampa.mycloudgallery.core.personal.settings.SyncedCollectionsSettings
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.feature.albums.contract.AccountNamesAsIs
import dev.pampa.mycloudgallery.feature.albums.contract.FixedAlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumEntryTarget
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.library.collections.CollectionSections
import dev.pampa.mycloudgallery.feature.library.utility.UtilityPage
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.integration.Attese
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 7 (P13; P11, D33, D26): le Raccolte legate in `:app`. Un fornitore per sezione con il codice stabile di `CollectionSections` (il ponte
 * `extraGroups` non c'e' piu'): Cartelle NAS solo con il NAS collegato (e il numero delle operazioni in corso), Utility con "Libera spazio" solo con il
 * backup, "Da tenere al sicuro" senza voci (gli Eliminati di recente stanno nell'Utility, una volta sola). Le voci si aprono per codice; l'ordine e la
 * visibilita' scelti prima che ci sia il registro dei dati personali vi entrano appena c'e'.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CollectionsWiringTest {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val library = SyntheticLibrary(count = 40)
  private val items = CollectionItemsSource { filter -> library.viewerItems(filter) }

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun sections(configured: StateFlow<Boolean>, operations: StateFlow<Int>, freeSpace: Boolean) = AppCollectionsSections(
    resources = ApplicationProvider.getApplicationContext<Application>().resources,
    albums = NoAlbums,
    trips = NoTrips,
    people = AccountNamesAsIs,
    clock = FixedAlbumScreensClock(LocalDate.of(2026, 10, 27)),
    items = items,
    nasConfigured = configured,
    operations = operations,
    freeSpace = freeSpace,
    canTrash = true,
    phoneTrash = PhoneTrash.ANDROID,
    scope = scope,
  )

  private suspend fun StateFlow<CollectionEntryGroup?>.next(test: (CollectionEntryGroup?) -> Boolean) = withTimeout(Attese.TETTO) { first(test) }

  @Test
  fun unFornitorePerSezioneConICodiciStabili() {
    val all = sections(MutableStateFlow(false), MutableStateFlow(0), freeSpace = false).all
    assertThat(all.map { it.code }).containsExactly(
      CollectionSections.ALBUMS, CollectionSections.SMART, CollectionSections.TRIPS, CollectionSections.NAS, CollectionSections.UTILITY, CollectionSections.SAFE,
    )
  }

  @Test
  fun cartelleNasSoloConIlNasEConLeOperazioniInCorso() = runBlocking<Unit> {
    val configured = MutableStateFlow(false)
    val operations = MutableStateFlow(0)
    val nas = sections(configured, operations, freeSpace = false).nas

    assertThat(nas.group.value).isNull()
    configured.value = true
    operations.value = 2
    val group = checkNotNull(nas.group.next { it?.entries?.lastOrNull()?.badge == 2 })
    assertThat(group.title).isEqualTo("Cartelle NAS")
    assertThat(group.code).isEqualTo(CollectionSections.NAS)
    assertThat(group.entries.map { it.code }).containsExactly(
      NasScreen.FOLDERS.route, NasScreen.SPACE.route, NasScreen.TRASH.route, NasScreen.OPERATIONS.route,
    ).inOrder()
    configured.value = false
    assertThat(nas.group.next { it == null }).isNull()
  }

  @Test
  fun utilityConLiberaSpazioSoloColBackupEDaTenereAlSicuroSenzaVoci() = runBlocking<Unit> {
    val with = sections(MutableStateFlow(true), MutableStateFlow(0), freeSpace = true)
    val without = sections(MutableStateFlow(true), MutableStateFlow(0), freeSpace = false)

    val utility = checkNotNull(with.utility.group.next { it != null })
    assertThat(utility.code).isEqualTo(CollectionSections.UTILITY)
    assertThat(utility.entries.map { it.code }).contains(BackupScreen.FREE_SPACE.route)
    assertThat(utility.entries.map { it.code }).contains(UtilityPage.RECENTLY_DELETED.entryCode)
    assertThat(checkNotNull(without.utility.group.next { it != null }).entries.map { it.code }).doesNotContain(BackupScreen.FREE_SPACE.route)
    // D26: gli Eliminati di recente una volta sola (nell'Utility): la sezione "Da tenere al sicuro" c'e' ma senza voci, e toglie quella della scheda.
    val safe = checkNotNull(with.safe.group.value)
    assertThat(safe.code).isEqualTo(CollectionSections.SAFE)
    assertThat(safe.entries).isEmpty()
  }

  @Test
  fun leVociSiApronoPerCodice() {
    assertThat(EntryTarget.of(BackupScreen.FREE_SPACE.route)).isEqualTo(EntryTarget.FreeSpace)
    assertThat(EntryTarget.of(UtilityPage.DUPLICATES.entryCode)).isEqualTo(EntryTarget.Utility(UtilityPage.DUPLICATES))
    assertThat(EntryTarget.of(NasScreen.TRASH.route)).isEqualTo(EntryTarget.Nas(NasScreen.TRASH))
    assertThat(EntryTarget.of(AlbumEntryTarget.ALL_ALBUMS)).isEqualTo(EntryTarget.Album(AlbumEntryTarget.AllAlbums))
    assertThat(EntryTarget.of(AlbumEntryTarget.NEW_ALBUM)).isEqualTo(EntryTarget.Album(AlbumEntryTarget.NewAlbum))
    assertThat(EntryTarget.of(AlbumEntryTarget.ALL_TRIPS)).isEqualTo(EntryTarget.Album(AlbumEntryTarget.Trip(null)))
    assertThat(EntryTarget.of("qualcosa/di-una-versione-nuova")).isEqualTo(EntryTarget.Unknown)
    // Sul tablet le pagine dell'Utility, della 4B e "Libera spazio" stanno nel pannello; quelle degli album sono rotte anche li'.
    assertThat(hasEntryDetail(UtilityPage.SCREENSHOTS.entryCode)).isTrue()
    assertThat(hasEntryDetail(NasScreen.FOLDERS.route)).isTrue()
    assertThat(hasEntryDetail(BackupScreen.FREE_SPACE.route)).isTrue()
    assertThat(hasEntryDetail(AlbumEntryTarget.ALL_ALBUMS)).isFalse()
    assertThat(hasEntryDetail(AlbumEntryTarget.NEW_ALBUM)).isFalse()
    assertThat(hasEntryDetail("sconosciuta")).isFalse()
  }

  @Test
  fun leScelteFattePrimaDelRegistroViEntranoAppenaCe() = runBlocking<Unit> {
    val stores = MutableStateFlow<PersonalStore?>(null)
    // Il deposito gira come nell'app, su un dispatcher vero: le attese sotto sono sugli eventi (i flussi), col tetto comune.
    val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val layout = AppCollectionsLayoutStore(stores, work)

    layout.setOrder(listOf(CollectionSections.UTILITY, CollectionSections.ALBUMS))
    layout.setHidden(CollectionSections.TRIPS, true)
    assertThat(withTimeout(Attese.TETTO) { layout.order.first { it.isNotEmpty() } }).containsExactly(CollectionSections.UTILITY, CollectionSections.ALBUMS).inOrder()
    assertThat(withTimeout(Attese.TETTO) { layout.hidden.first { it.isNotEmpty() } }).containsExactly(CollectionSections.TRIPS)

    // L'ora del NAS va avanti a ogni scrittura (due voci con la stessa ora si fondono per valore, non per ordine).
    var nasNow = 1_760_000_000_000L
    val store = InMemoryPersonalStore(TrustIds.random(), nasNow = { nasNow++ })
    stores.value = store
    val synced = SyncedCollectionsSettings(store, work)
    withTimeout(Attese.TETTO) { synced.order.first { it.isNotEmpty() } }
    assertThat(synced.order.value).containsExactly(CollectionSections.UTILITY, CollectionSections.ALBUMS).inOrder()
    assertThat(withTimeout(Attese.TETTO) { synced.hidden.first { it.isNotEmpty() } }).containsExactly(CollectionSections.TRIPS)
    // Il deposito legge ormai il registro.
    withTimeout(Attese.TETTO) { layout.hidden.first { it == setOf(CollectionSections.TRIPS) } }

    // Dopo, si scrive direttamente nel registro; e una scelta arrivata da un altro dispositivo si vede.
    layout.setHidden(CollectionSections.TRIPS, false)
    assertThat(withTimeout(Attese.TETTO) { layout.hidden.first { it.isEmpty() } }).isEmpty()
    // Album non si nasconde mai (D33).
    layout.setHidden(CollectionSections.ALBUMS, true)
    assertThat(layout.hidden.value).doesNotContain(CollectionSections.ALBUMS)
    work.cancel()
  }
}
