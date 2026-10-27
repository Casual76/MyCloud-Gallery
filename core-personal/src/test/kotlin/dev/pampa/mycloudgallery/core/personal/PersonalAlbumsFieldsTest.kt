package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.LocalFlag
import dev.pampa.mycloudgallery.core.personal.store.PersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * I campi nuovi degli album personali (D4, D21, Q5; riga P3 del §4.4): le scritture portano l'**impronta** di ogni voce che nomina un media, i
 * lettori rileggono tutto dallo stato (anche le lapidi e cio' che una versione piu' nuova ha aggiunto), e il resto dei campi sconosciuti non
 * rompe niente. Lo stato e' quello dei dati personali: la proiezione sul catalogo e' di core-albums.
 */
class PersonalAlbumsFieldsTest {

  private val album = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
  private val m1 = mediaId(1)
  private val m2 = mediaId(2)

  private fun fp(n: Int) = PersonalFingerprint(1_000L + n, QuickHash("%064x".format(n + 1)))

  /** Un catalogo finto: l'impronta del media `mediaId(n)` e' `fp(n)`. */
  private object CatalogOfFingerprints : PersonalProjection {
    override suspend fun apply(entry: PersonalEntry) = ProjectionResult.NOT_PROJECTED

    override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? = null

    override suspend fun localFlags(): List<LocalFlag> = emptyList()

    override suspend fun fingerprintOfMedia(mediaId: String): PersonalFingerprint? =
      (1..9).firstOrNull { mediaId(it) == mediaId }?.let { PersonalFingerprint(1_000L + it, QuickHash("%064x".format(it + 1))) }
  }

  private fun store(projection: PersonalProjection = CatalogOfFingerprints, now: () -> Long? = { NAS_NOW }) =
    LedgerPersonalStore(TrustIds.random(), now, InMemoryPersonalLedger(), projection)

  @Test
  fun ogniVoceCheNominaUnMediaPortaLImprontaDelCatalogoSeNonSiPassa() = runTest {
    val store = store()
    val albums = PersonalAlbums(store)
    albums.define(album, "Gita")
    albums.addItem(album, m1, 1_000)
    albums.addItem(album, m2, 2_000, fingerprint = fp(7))
    albums.setCover(album, m1)
    albums.exclude(album, m2)
    albums.accept(album, m1)
    albums.setExample(album, m2, on = true)
    val state = store.state()
    fun fingerprintOf(field: String) = state[PersonalKey(PersonalKind.ALBUM, album, field)]!!.fingerprint
    assertThat(fingerprintOf(PersonalFields.albumItem(m1))).isEqualTo(fp(1))
    assertThat(fingerprintOf(PersonalFields.albumItem(m2))).isEqualTo(fp(7))
    assertThat(fingerprintOf(PersonalFields.ALBUM_COVER)).isEqualTo(fp(1))
    assertThat(fingerprintOf(PersonalFields.albumExclude(m2))).isEqualTo(fp(2))
    assertThat(fingerprintOf(PersonalFields.albumAccept(m1))).isEqualTo(fp(1))
    assertThat(fingerprintOf(PersonalFields.smartExample(m2))).isEqualTo(fp(2))
    // La definizione non nomina nessun media: niente impronta.
    assertThat(fingerprintOf(PersonalFields.ALBUM_DEFINITION)).isNull()
  }

  @Test
  fun senzaCatalogoLaVoceResta_ComeNellaFase4_SenzaImpronta() = runTest {
    val store = store(projection = dev.pampa.mycloudgallery.core.personal.store.NoProjection)
    PersonalAlbums(store).addItem(album, m1, 1_000)
    assertThat(store.get(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumItem(m1)))!!.fingerprint).isNull()
  }

  @Test
  fun lapideEImprontaDiUnaRimozioneSonoQuelleDellElemento() = runTest {
    val store = store()
    val albums = PersonalAlbums(store)
    albums.addItem(album, m1, 1_000)
    albums.removeItem(album, m1)
    val entry = store.get(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumItem(m1)))!!
    assertThat(entry.value).isEqualTo(PersonalValue.Removed)
    assertThat(entry.fingerprint).isEqualTo(fp(1))
  }

  @Test
  fun unaScelta_SpentaNonScriveLapidiPerVociMaiScritte() = runTest {
    val store = store()
    val albums = PersonalAlbums(store)
    albums.exclude(album, m1, on = false)
    albums.accept(album, m1, on = false)
    albums.setExample(album, m1, on = false)
    assertThat(store.state()).isEmpty()
    albums.exclude(album, m1)
    albums.exclude(album, m1, on = false)
    val entry = store.get(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumExclude(m1)))!!
    assertThat(entry.value).isEqualTo(PersonalValue.Removed)
  }

  @Test
  fun loStatoDiUnAlbumSiRileggeInteroCompresiICampiNuoviELeLapidi() = runTest {
    val store = store()
    val albums = PersonalAlbums(store)
    albums.setKind(album, AlbumKind.SMART)
    albums.setSort(album, AlbumSort.CUSTOM)
    albums.setSmart(album, PersonalFields.SMART_TEXT, "neve")
    albums.setSmart(album, PersonalFields.SMART_PERIOD, "r:2026-01-01..2026-01-09")
    albums.setFrozen(album, true)
    albums.setShared(album, "00000000-0000-4000-8000-0000000000aa")
    albums.setCopyOf(album, "00000000-0000-4000-8000-0000000000bb")
    albums.define(album, "Neve")
    albums.setOrder(album, 5)
    albums.setCover(album, m1)
    albums.addItem(album, m1, 1_000)
    albums.addItem(album, m2, 2_000)
    albums.removeItem(album, m2)
    albums.exclude(album, m2)
    albums.accept(album, m1)
    albums.setExample(album, m1, on = true)

    val state = albums.state(album, store.state())!!

    assertThat(state.defined).isTrue()
    assertThat(state.title).isEqualTo("Neve")
    assertThat(state.kind).isEqualTo("smart")
    assertThat(state.sort).isEqualTo("custom")
    assertThat(state.frozen).isTrue()
    assertThat(state.order).isEqualTo(5L)
    assertThat(state.cover!!.mediaId).isEqualTo(m1)
    assertThat(state.cover!!.fingerprint).isEqualTo(fp(1))
    assertThat(state.sharedId).isEqualTo("00000000-0000-4000-8000-0000000000aa")
    assertThat(state.copyOf).isEqualTo("00000000-0000-4000-8000-0000000000bb")
    assertThat(state.smart).containsExactly(PersonalFields.SMART_TEXT, "neve", PersonalFields.SMART_PERIOD, "r:2026-01-01..2026-01-09")
    // Gli elementi, vivi e lapidi (la proiezione deve sapere che un elemento e' stato tolto, anche sotto un altro id).
    assertThat(state.items.map { it.mediaId to it.position }).containsExactly(m1 to 1_000L, m2 to null)
    assertThat(state.items.single { it.mediaId == m1 }.fingerprint).isEqualTo(fp(1))
    assertThat(state.excluded.map { it.mediaId to it.on }).containsExactly(m2 to true)
    assertThat(state.accepted.map { it.mediaId to it.on }).containsExactly(m1 to true)
    assertThat(state.examples.map { it.mediaId to it.on }).containsExactly(m1 to true)
    assertThat(state.hasUndated).isFalse()
    assertThat(state.firstAtNas).isAtLeast(1)
    assertThat(state.lastAtNas).isAtLeast(state.firstAtNas)
  }

  @Test
  fun unAlbumCancellatoOSenzaDefinizioneHaLoStatoMaNonTitoloEIlLegacyNonLoMostra() = runTest {
    val store = store()
    val albums = PersonalAlbums(store)
    albums.define(album, "Via")
    albums.addItem(album, m1, 1_000)
    albums.delete(album)
    val state = albums.state(album, store.state())!!
    assertThat(state.defined).isFalse()
    assertThat(state.title).isNull()
    assertThat(state.items).hasSize(1)
    assertThat(albums.albums(store.state())).isEmpty()
    assertThat(albums.definition(album, store.state())!!.value).isEqualTo(PersonalValue.Removed)
    assertThat(albums.state("00000000-0000-4000-8000-000000000000", store.state())).isNull()
  }

  @Test
  fun leModificheSenzaOraDelNasValgonoSubitoEIlLoroStatoLoDice() = runTest {
    var anchored = false
    val store = store(now = { if (anchored) NAS_NOW else null })
    val albums = PersonalAlbums(store)
    albums.define(album, "Offline")
    albums.addItem(album, m1, 1_000)
    val offline = albums.state(album, store.effective())!!
    assertThat(offline.title).isEqualTo("Offline")
    assertThat(offline.hasUndated).isTrue()
    assertThat(offline.items.single().atNas).isEqualTo(Long.MAX_VALUE)
    // Lo stato unito non le ha ancora (non si pubblicano senza ora del NAS).
    assertThat(albums.state(album, store.state())).isNull()
    anchored = true
    store.stamp(NAS_NOW)
    val dated = albums.state(album, store.state())!!
    assertThat(dated.hasUndated).isFalse()
    assertThat(dated.lastAtNas).isEqualTo(NAS_NOW)
  }

  @Test
  fun unaVoceVecchiaSenzaPostoEIlCampoSconosciutoNonRompono() = runTest {
    val store = store()
    val author = TrustIds.random()
    store.applyRemote(
      listOf(
        PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.ALBUM_DEFINITION), NAS_NOW, author, PersonalValue.Text("Vecchio")),
        // Fase 4: il flag acceso al posto del numero = in fondo.
        PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumItem(m1)), NAS_NOW, author, PersonalValue.Flag(true)),
        PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumItem(m2)), NAS_NOW, author, PersonalValue.Number(5)),
        PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, "campo:del-futuro"), NAS_NOW, author, PersonalValue.Text("boh")),
        PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.ALBUM_KIND), NAS_NOW, author, PersonalValue.Number(3)),
      ),
    )
    val listed = PersonalAlbums(store).albums().single()
    assertThat(listed.items).containsExactly(m2, m1).inOrder()
    assertThat(listed.kind).isNull()
  }

  @Test
  fun lOrdineDellElencoEUnaImpostazioneEIDiAlbumNonCondivisoSiScriveComeVoceSenzaDefinizione() = runTest {
    val store = store()
    val albums = PersonalAlbums(store)
    assertThat(albums.listSort()).isNull()
    albums.setListSort("custom")
    assertThat(albums.listSort(store.state())).isEqualTo("custom")
    // Il posto di un album condiviso nell'elenco: una voce `order` senza definizione, che non crea nessun album.
    albums.setOrder("00000000-0000-4000-8000-0000000000cc", 3)
    assertThat(albums.albums(store.state())).isEmpty()
    assertThat(albums.state("00000000-0000-4000-8000-0000000000cc", store.state())!!.order).isEqualTo(3L)
  }

  @Test
  fun iCampiDelleRegoleSonoSoloQuelliDelleRegole() = runTest {
    val albums = PersonalAlbums(store())
    val failed = runCatching { albums.setSmart(album, "smart:inventato", "x") }
    assertThat(failed.isFailure).isTrue()
    assertThat(PersonalFields.SMART_RULE_FIELDS).containsExactly(
      PersonalFields.SMART_PERIOD, PersonalFields.SMART_PLACE, PersonalFields.SMART_TEXT, PersonalFields.SMART_SOURCES, PersonalFields.SMART_TYPES,
      PersonalFields.SMART_PEOPLE,
    )
  }

  @Test
  fun iLettoriDeiCampiSonoLInversoDeiCostruttori() {
    val id = m1
    assertThat(PersonalFields.smartExampleMedia(PersonalFields.smartExample(id))).isEqualTo(id)
    assertThat(PersonalFields.albumExcludeMedia(PersonalFields.albumExclude(id))).isEqualTo(id)
    assertThat(PersonalFields.albumAcceptMedia(PersonalFields.albumAccept(id))).isEqualTo(id)
    assertThat(PersonalFields.collectionsHiddenSection(PersonalFields.collectionsHidden("trips"))).isEqualTo("trips")
    assertThat(PersonalFields.tripDismissedId(PersonalFields.tripDismissed("ab".repeat(32)))).isEqualTo("ab".repeat(32))
    assertThat(PersonalFields.sharedAlbumLeftId(PersonalFields.sharedAlbumLeft(album))).isEqualTo(album)
    // Un campo non si scambia con un altro, e un prefisso senza nome non nomina niente.
    for (field in listOf(PersonalFields.ALBUM_KIND, PersonalFields.SMART_TEXT, PersonalFields.albumItem(id), "exclude:", "accept:", "smart:example:")) {
      assertThat(PersonalFields.albumExcludeMedia(field) ?: PersonalFields.albumAcceptMedia(field) ?: PersonalFields.smartExampleMedia(field)).isNull()
    }
    assertThat(PersonalFields.isAlbumExclude(PersonalFields.albumAccept(id))).isFalse()
    assertThat(PersonalFields.isSmartExample(PersonalFields.smartExample(id))).isTrue()
    assertThat(PersonalFields.isAlbumItem(PersonalFields.smartExample(id))).isFalse()
  }
}
