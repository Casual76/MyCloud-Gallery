package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.legacy.LegacyPersonalAlbums
import dev.pampa.mycloudgallery.core.personal.legacy.LegacyPersonalSegments
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.NoProjection
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Un'app della Fase 6 legge i segmenti con i campi nuovi della Fase 7 **senza scartarli** (D4, riga P3 del §4.4): i campi stanno dentro i tipi
 * `ALBUM`, `SETTING` e `MEDIA_FLAG` che il codec di allora gia' conosce. La prova usa il codec e gli album **della Fase 6** (copie parola per
 * parola dei sorgenti di allora, in `legacy/`), non quelli di oggi: se oggi cambiasse qualcosa nel codec, la prova non lo seguirebbe.
 */
class Phase7LegacyReaderTest {

  private val device = TrustIds.random()
  private val album = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
  private val smart = "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e"
  private val m1 = "0f8e6c1a-2b3c-4d5e-8f90-a1b2c3d4e5f6"
  private val m2 = "1f8e6c1a-2b3c-4d5e-8f90-a1b2c3d4e5f7"
  private val m3 = "2f8e6c1a-2b3c-4d5e-8f90-a1b2c3d4e5f8"
  private val t = 1_760_000_000_000L

  private fun fp(n: Int) = PersonalFingerprint(1_000L + n, QuickHash("%064x".format(n + 1)))

  private fun e(kind: PersonalKind, subject: String, field: String, at: Long, value: PersonalValue, fingerprint: PersonalFingerprint? = null) =
    PersonalEntry(PersonalKey(kind, subject, field), at, device, value, fingerprint)

  /** Uno stato con TUTTI i campi nuovi (e quelli vecchi): due album, le impostazioni, il flag di un media. */
  private fun phase7State(): List<PersonalEntry> {
    val a = PersonalKind.ALBUM
    val s = PersonalKind.SETTING
    val subject = PersonalFields.SETTINGS_SUBJECT
    return listOf(
      // Un album manuale della Fase 7: ordine dentro l'album, copertina e elementi con l'impronta, legato a uno condiviso.
      e(a, album, PersonalFields.ALBUM_DEFINITION, t + 1, PersonalValue.Text("Oslo 2026")),
      e(a, album, PersonalFields.ALBUM_ORDER, t + 2, PersonalValue.Number(2_000)),
      e(a, album, PersonalFields.ALBUM_COVER, t + 3, PersonalValue.Text(m2), fp(2)),
      e(a, album, PersonalFields.albumItem(m1), t + 4, PersonalValue.Number(1_000), fp(1)),
      e(a, album, PersonalFields.albumItem(m2), t + 5, PersonalValue.Number(2_000), fp(2)),
      e(a, album, PersonalFields.albumItem(m3), t + 6, PersonalValue.Removed, fp(3)),
      e(a, album, PersonalFields.ALBUM_SORT, t + 7, PersonalValue.Text("custom")),
      e(a, album, PersonalFields.ALBUM_SHARED, t + 8, PersonalValue.Text(smart)),
      e(a, album, PersonalFields.ALBUM_COPY_OF, t + 9, PersonalValue.Text(smart)),
      // Uno smart: tipo, regole (un testo per regola), esempi, esclusioni, accettazioni, congelamento.
      e(a, smart, PersonalFields.ALBUM_DEFINITION, t + 10, PersonalValue.Text("Neve")),
      e(a, smart, PersonalFields.ALBUM_KIND, t + 11, PersonalValue.Text("smart")),
      e(a, smart, PersonalFields.SMART_PERIOD, t + 12, PersonalValue.Text("r:2026-01-01..2026-01-09")),
      e(a, smart, PersonalFields.SMART_PLACE, t + 13, PersonalValue.Text("around:25:59.9139:10.7522:3143244:Oslo")),
      e(a, smart, PersonalFields.SMART_TEXT, t + 14, PersonalValue.Text("neve")),
      e(a, smart, PersonalFields.SMART_SOURCES, t + 15, PersonalValue.Text("my_phone,my_private")),
      e(a, smart, PersonalFields.SMART_TYPES, t + 16, PersonalValue.Text("photo")),
      e(a, smart, PersonalFields.smartExample(m1), t + 17, PersonalValue.Flag(true), fp(1)),
      e(a, smart, PersonalFields.albumExclude(m2), t + 18, PersonalValue.Flag(true), fp(2)),
      e(a, smart, PersonalFields.albumAccept(m3), t + 19, PersonalValue.Flag(true), fp(3)),
      e(a, smart, PersonalFields.ALBUM_FROZEN, t + 20, PersonalValue.Flag(true)),
      // Le impostazioni nuove e il flag nuovo di un media.
      e(s, subject, PersonalFields.SETTING_ALBUMS_SORT, t + 21, PersonalValue.Text("custom")),
      e(s, subject, PersonalFields.SETTING_COLLECTIONS_ORDER, t + 22, PersonalValue.Text("albums,smart,trips")),
      e(s, subject, PersonalFields.collectionsHidden("trips"), t + 23, PersonalValue.Flag(true)),
      e(s, subject, PersonalFields.tripDismissed("0".repeat(64)), t + 24, PersonalValue.Flag(true)),
      e(s, subject, PersonalFields.sharedAlbumLeft(smart), t + 25, PersonalValue.Flag(true)),
      e(s, subject, PersonalFields.SETTING_FULL_HASH_NIGHTLY, t + 26, PersonalValue.Flag(true)),
      e(PersonalKind.MEDIA_FLAG, m1, PersonalFields.SHARED_ORIGIN, t + 27, PersonalValue.Text("Foto/2026/Oslo"), fp(1)),
      // E uno vecchio, perche' conviva.
      e(PersonalKind.MEDIA_FLAG, m1, PersonalFields.FAVORITE, t + 28, PersonalValue.Flag(true)),
    )
  }

  @Test
  fun ilCodecDellaFase6LeggeISegmentiDellaFase7ConTuttiICampiNuoviEnessunoScartato() {
    val state = phase7State()
    val plan = PersonalSegments.plan(state)
    assertThat(plan.skipped).isEqualTo(0)
    assertThat(plan.entries).isEqualTo(state.size)
    val back = plan.segments.flatMap { segment ->
      LegacyPersonalSegments.decode(segment.bytes, segment.ref).acceptedOrNull()
        ?: error("L'app della Fase 6 ha scartato un segmento con i campi nuovi")
    }
    assertThat(back).containsExactlyElementsIn(state)
    // E le impronte (D4) ci sono ancora, voce per voce.
    val withFingerprint = state.filter { it.fingerprint != null }
    assertThat(withFingerprint.size).isAtLeast(8)
    assertThat(back.filter { it.fingerprint != null }).containsExactlyElementsIn(withFingerprint)
  }

  @Test
  fun eViceversaIlCodecDiOggiLeggeCioCheLaFase6Ripubblica() {
    val state = phase7State()
    val legacyPlan = LegacyPersonalSegments.plan(state)
    val livePlan = PersonalSegments.plan(state)
    // Stessi segmenti, stessi nomi: l'app della Fase 6 che ripubblica uno stato con i campi nuovi scrive gli stessi byte.
    assertThat(legacyPlan.segments.map { it.ref.hash }).containsExactlyElementsIn(livePlan.segments.map { it.ref.hash })
    val back = legacyPlan.segments.flatMap { segment -> PersonalSegments.decode(segment.bytes, segment.ref).acceptedOrNull().orEmpty() }
    assertThat(back).containsExactlyElementsIn(state)
  }

  @Test
  fun unStoreCheRiceveIlStatoDallaFase6LoTieneIntero() = runTest {
    val state = phase7State()
    val store = LedgerPersonalStore(TrustIds.random(), { t + 1_000 }, InMemoryPersonalLedger(), NoProjection)
    val decoded = PersonalSegments.plan(state).segments.flatMap { LegacyPersonalSegments.decode(it.bytes, it.ref).acceptedOrNull().orEmpty() }
    val result = store.applyRemote(decoded)
    assertThat(result.applied).isEqualTo(state.size)
    assertThat(store.state().values).containsExactlyElementsIn(state)
    // Quello che pubblichera' e' lo stato di prima: nessuna voce e' cambiata, nessuna e' sparita.
    assertThat(PersonalSegments.plan(store.state().values).names).containsExactlyElementsIn(PersonalSegments.plan(state).names)
  }

  @Test
  fun gliAlbumDellaFase6VedonoCioCheVedevanoSenzaInciampareNeiCampiNuovi() = runTest {
    val state = phase7State()
    val entries = state.associateBy { it.key }
    val store = LedgerPersonalStore(TrustIds.random(), { t }, InMemoryPersonalLedger(), NoProjection)
    val old = LegacyPersonalAlbums(store).albums(entries)
    // Gli album sono quelli: le regole, gli esempi, le esclusioni, i congelamenti e il resto per l'app vecchia non esistono.
    assertThat(old.map { it.id }).containsExactly(album, smart)
    val oslo = old.single { it.id == album }
    assertThat(oslo.title).isEqualTo("Oslo 2026")
    assertThat(oslo.order).isEqualTo(2_000L)
    assertThat(oslo.cover).isEqualTo(m2)
    assertThat(oslo.items).containsExactly(m1, m2).inOrder()
    assertThat(old.single { it.id == smart }.items).isEmpty()
    // L'app di oggi vede la stessa cosa dei campi di sempre.
    val current = PersonalAlbums(store).albums(entries)
    assertThat(current.map { Triple(it.id, it.title, it.items) }).isEqualTo(old.map { Triple(it.id, it.title, it.items) })
    assertThat(current.map { it.order to it.cover }).isEqualTo(old.map { it.order to it.cover })
  }

  /** Il codec di oggi e' quello della Fase 6, parola per parola: un cambiamento deve essere voluto (e la copia di `legacy/` aggiornata con lui). */
  @Test
  fun ilCodecDelleVociNonECambiatoDallaFase6() {
    fun normalized(text: String): String = text.replace("\r\n", "\n").lines()
      .dropWhile { !it.startsWith("package ") }.drop(1).joinToString("\n").trim()
    val live = File("src/main/kotlin/dev/pampa/mycloudgallery/core/personal/codec/PersonalCodec.kt").readText()
    val legacy = File("src/test/kotlin/dev/pampa/mycloudgallery/core/personal/legacy/LegacyPersonalCodec.kt").readText()
      .replace("LegacyPersonalRecords", "PersonalRecords").replace("LegacyPersonalSegments", "PersonalSegments")
      .replace("LegacyEncodedItem", "EncodedItem").replace("LegacyPublishPlan", "PersonalPublishPlan")
    assertWithMessage("il codec delle voci e' cambiato rispetto alla Fase 6: e' un cambio di formato (versione, D4)")
      .that(normalized(legacy)).isEqualTo(normalized(live))
  }
}
