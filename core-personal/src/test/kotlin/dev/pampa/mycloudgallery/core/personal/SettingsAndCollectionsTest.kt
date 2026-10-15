package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalLimits
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.personal.settings.PersonalMemories
import dev.pampa.mycloudgallery.core.personal.settings.SyncedPersonalSettings
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le impostazioni personali (Q9 (a): "Mostra le copertine" e le esclusioni dell'area privata, non preset, qualita' o ruoli), gli
 * album manuali e i ricordi visti: stesso stato unito dei preferiti, quindi stessa convergenza fra dispositivi dello stesso account.
 */
class SettingsAndCollectionsTest {

  private val world = PersonalWorld()
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  private fun settingsOf(device: PersonalDevice) = SyncedPersonalSettings(device.store, scope)

  private suspend fun syncAll(vararg devices: PersonalDevice) {
    repeat(2) {
      for (device in devices) device.syncOnce()
      world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    }
  }

  // --- Impostazioni ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun mostraLeCopertineViaggiaFraIDispositivi() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = settingsOf(phone)
    val onTablet = settingsOf(tablet)
    assertThat(onPhone.showAlbumCovers.value).isFalse()

    onPhone.setShowAlbumCovers(true)
    assertThat(onPhone.showAlbumCovers.value).isTrue()
    syncAll(phone, tablet)
    assertThat(onTablet.showAlbumCovers.value).isTrue()

    // L'ultima scelta vince, su qualunque dispositivo.
    onTablet.setShowAlbumCovers(false)
    syncAll(tablet, phone)
    assertThat(onPhone.showAlbumCovers.value).isFalse()
    scope.cancel()
  }

  @Test
  fun leEsclusioniDueDispositiviCheAggiungonoCartelleDiverseLeTengonoEntrambe() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = settingsOf(phone)
    val onTablet = settingsOf(tablet)

    onPhone.setPrivateAreaExclusions(setOf(path("Musica")))
    onTablet.setPrivateAreaExclusions(setOf(path("Film/Concerti")))
    syncAll(phone, tablet)
    assertThat(onPhone.privateAreaExclusions.value).containsExactly(path("Musica"), path("Film/Concerti"))
    assertThat(onTablet.privateAreaExclusions.value).containsExactly(path("Musica"), path("Film/Concerti"))

    // Una cartella tolta su un dispositivo si toglie ovunque; l'altra resta.
    onPhone.setPrivateAreaExclusions(setOf(path("Film/Concerti")))
    syncAll(phone, tablet)
    assertThat(onTablet.privateAreaExclusions.value).containsExactly(path("Film/Concerti"))
    onTablet.setPrivateAreaExclusions(emptySet())
    syncAll(tablet, phone)
    assertThat(onPhone.privateAreaExclusions.value).isEmpty()
    scope.cancel()
  }

  @Test
  fun dueNomiDiCartellaDiversiSoloPerLeMaiuscoleSonoLaStessaEsclusione() = runTest {
    val phone = world.device()
    val onPhone = settingsOf(phone)
    onPhone.setPrivateAreaExclusions(setOf(path("Musica")))
    onPhone.setPrivateAreaExclusions(setOf(path("MUSICA")))
    // Per Samba e' la stessa cartella: nessuna seconda voce, quella di prima resta.
    assertThat(phone.store.entries.value.keys.count { PersonalFields.isPrivateExclusion(it.field) }).isEqualTo(1)
    scope.cancel()
  }

  @Test
  fun soloQuesteDueImpostazioniViaggiano_nonPresetQualitaORuoli() = runTest {
    val phone = world.device()
    val onPhone = settingsOf(phone)
    onPhone.setShowAlbumCovers(true)
    onPhone.setPrivateAreaExclusions(setOf(path("Musica"), path("Film")))
    val keys = phone.store.entries.value.keys
    assertThat(keys.map { it.kind }.toSet()).containsExactly(PersonalKind.SETTING)
    assertThat(keys.map { it.subject }.toSet()).containsExactly(PersonalFields.SETTINGS_SUBJECT)
    assertThat(keys.map { it.field }.filter { !PersonalFields.isPrivateExclusion(it) }).containsExactly(PersonalFields.SETTING_SHOW_ALBUM_COVERS)
    scope.cancel()
  }

  @Test
  fun unaVoceOstileNonDiventaUnaEsclusione() = runTest {
    val phone = world.device()
    val onPhone = settingsOf(phone)
    val field = PersonalFields.privateExclusion(path("Musica"))
    fun setting(f: String, value: String) =
      PersonalEntry(PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, f), NAS_NOW, phone.id, PersonalValue.Text(value))
    phone.store.applyRemote(
      listOf(
        setting(field, "Musica"),
        // Un percorso che esce dalla share, uno assoluto, uno che non torna con il suo campo.
        setting(PersonalFields.privateExclusion(path("Foto")), "../segreti"),
        setting("${PersonalFields.SETTING_PRIVATE_EXCLUSIONS}:x", "/etc"),
        setting("${PersonalFields.SETTING_PRIVATE_EXCLUSIONS}:y", "Altro"),
      ),
    )
    assertThat(onPhone.privateAreaExclusions.value).containsExactly(path("Musica"))
    // Un percorso troppo lungo per una voce non si sincronizza (e non e' un'esclusione).
    val deep = path((1..60).joinToString("/") { "cartella-molto-lunga-$it" })
    assertThat(deep.value.length).isGreaterThan(PersonalLimits.MAX_TEXT)
    onPhone.setPrivateAreaExclusions(setOf(path("Musica"), deep))
    assertThat(onPhone.privateAreaExclusions.value).containsExactly(path("Musica"))
    scope.cancel()
  }

  // --- Album manuali ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun glAlbumManualiConvergonoFraTreDispositivi() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val third = world.device()
    val albumsOnPhone = PersonalAlbums(phone.store)
    val albumsOnTablet = PersonalAlbums(tablet.store)
    val albumsOnThird = PersonalAlbums(third.store)
    val (a, b, c) = Triple(mediaId(1), mediaId(2), mediaId(3))

    albumsOnPhone.define("oslo", "Oslo 2026")
    albumsOnPhone.addItem("oslo", a, 1)
    albumsOnPhone.addItem("oslo", b, 2)
    albumsOnPhone.setOrder("oslo", 2)
    albumsOnTablet.define("estate", "Estate")
    albumsOnTablet.setOrder("estate", 1)
    syncAll(phone, tablet, third)

    // Dal terzo: tutti e due gli album, nell'ordine scelto, con copertina ed elementi cambiati da altri dispositivi.
    albumsOnThird.setCover("oslo", b)
    albumsOnThird.addItem("oslo", c, 0)
    albumsOnThird.removeItem("oslo", a)
    syncAll(third, phone, tablet)

    for (albums in listOf(albumsOnPhone, albumsOnTablet, albumsOnThird)) {
      val list = albums.albums()
      assertThat(list.map { it.id }).containsExactly("estate", "oslo").inOrder()
      val oslo = list.single { it.id == "oslo" }
      assertThat(oslo.title).isEqualTo("Oslo 2026")
      assertThat(oslo.cover).isEqualTo(b)
      assertThat(oslo.items).containsExactly(c, b).inOrder()
      assertThat(list.single { it.id == "estate" }.items).isEmpty()
    }
  }

  @Test
  fun cancellareUnAlbumLoTogliePerTuttiEUnaRicreazioneRitrovaGliElementi() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = PersonalAlbums(phone.store)
    val onTablet = PersonalAlbums(tablet.store)
    onPhone.define("gita", "Gita")
    onPhone.addItem("gita", mediaId(1), 0)
    syncAll(phone, tablet)
    assertThat(onTablet.albums().map { it.id }).containsExactly("gita")

    onTablet.delete("gita")
    syncAll(tablet, phone)
    assertThat(onPhone.albums()).isEmpty()
    assertThat(onTablet.albums()).isEmpty()

    onPhone.define("gita", "Gita 2")
    syncAll(phone, tablet)
    assertThat(onTablet.albums().single().title).isEqualTo("Gita 2")
    assertThat(onTablet.albums().single().items).containsExactly(mediaId(1))
  }

  @Test
  fun unAlbumNonCompareSenzaDefinizione() = runTest {
    val phone = world.device()
    val albums = PersonalAlbums(phone.store)
    albums.addItem("orfano", mediaId(1), 0)
    assertThat(albums.albums()).isEmpty()
  }

  // --- Ricordi visti -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun unRicordoVistoSuUnDispositivoRisultaVistoSuTutti() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = PersonalMemories(phone.store)
    val onTablet = PersonalMemories(tablet.store)
    assertThat(onTablet.lastSeen("ricordo-2025-10")).isNull()
    onPhone.markSeen("ricordo-2025-10", NAS_NOW)
    syncAll(phone, tablet)
    assertThat(onTablet.lastSeen("ricordo-2025-10")).isEqualTo(NAS_NOW)
    world.clock.advance(86_400_000)
    val seenAgain = world.clock.now
    onTablet.markSeen("ricordo-2025-10", seenAgain)
    syncAll(tablet, phone)
    assertThat(onPhone.lastSeen("ricordo-2025-10")).isEqualTo(seenAgain)
  }
}
