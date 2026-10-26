package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.codec.PersonalRecords
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import org.junit.Test

/**
 * Fase 7 (P0, D4, risposta Q5): i campi nuovi degli album, delle Raccolte e dei viaggi stanno **dentro** i tipi esistenti. Il codec di
 * oggi (lo stesso dell'app della Fase 6) li scrive e li rilegge identici, impronta compresa: nessuna versione nuova, nessun segmento
 * scartato. I lettori sono di P3.
 */
class Phase7FieldsContractTest {

  private val media = "0f8e6c1a-2b3c-4d5e-8f90-a1b2c3d4e5f6"
  private val album = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
  private val fp = PersonalFingerprint(4_321, QuickHash("ab".repeat(32)))

  @Test
  fun iNomiDeiCampiSonoQuelliDelProgetto() {
    val album = listOf(
      PersonalFields.ALBUM_KIND to "kind", PersonalFields.ALBUM_SORT to "sort", PersonalFields.SMART_PERIOD to "smart:period",
      PersonalFields.SMART_PLACE to "smart:place", PersonalFields.SMART_TEXT to "smart:text", PersonalFields.SMART_SOURCES to "smart:sources",
      PersonalFields.SMART_TYPES to "smart:types", PersonalFields.ALBUM_FROZEN to "frozen", PersonalFields.ALBUM_SHARED to "shared",
      PersonalFields.ALBUM_COPY_OF to "copy_of", PersonalFields.smartExample(media) to "smart:example:$media",
      PersonalFields.albumExclude(media) to "exclude:$media", PersonalFields.albumAccept(media) to "accept:$media",
    )
    val settings = listOf(
      PersonalFields.SETTING_COLLECTIONS_ORDER to "collections_order", PersonalFields.collectionsHidden("trips") to "collections_hidden:trips",
      PersonalFields.SETTING_ALBUMS_SORT to "albums_sort", PersonalFields.tripDismissed("f".repeat(64)) to "trip_dismissed:${"f".repeat(64)}",
      PersonalFields.sharedAlbumLeft(this.album) to "shared_album_left:${this.album}", PersonalFields.SETTING_FULL_HASH_NIGHTLY to "full_hash_nightly",
    )
    for ((actual, expected) in album + settings + listOf(PersonalFields.SHARED_ORIGIN to "shared_origin")) {
      assertThat(actual).isEqualTo(expected)
      assertWithMessage(expected).that(actual.length).isAtMost(PersonalKey.MAX_PART)
    }
    // Un campo nuovo non si confonde con un elemento d'album ne' con una separazione fra copie simili.
    for ((field, _) in album + settings) {
      assertWithMessage(field).that(PersonalFields.isAlbumItem(field)).isFalse()
      assertWithMessage(field).that(PersonalFields.isSimilarApart(field)).isFalse()
    }
  }

  /** D4: il codec di oggi rilegge identiche le voci con i campi nuovi (impronta compresa), nei tre tipi esistenti. */
  @Test
  fun ilCodecDiOggiLeggeICampiNuoviSenzaScartarli() {
    val author = TrustIds.random()
    val t = 1_760_000_000_000L
    val entries = listOf(
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.ALBUM_KIND), t + 10, author, PersonalValue.Text("smart")),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.SMART_TEXT), t + 11, author, PersonalValue.Text("tramonto sul mare")),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.ALBUM_FROZEN), t + 12, author, PersonalValue.Flag(true)),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.ALBUM_COPY_OF), t + 12, author, PersonalValue.Text(album)),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumExclude(media)), t + 13, author, PersonalValue.Flag(true), fp),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumAccept(media)), t + 14, author, PersonalValue.Flag(true), fp),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.smartExample(media)), t + 15, author, PersonalValue.Flag(true), fp),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumItem(media)), t + 16, author, PersonalValue.Number(2_000), fp),
      PersonalEntry(
        PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.tripDismissed("0".repeat(64))), t + 17, author, PersonalValue.Flag(true),
      ),
      PersonalEntry(PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_COLLECTIONS_ORDER), t + 18, author, PersonalValue.Text("albums,smart")),
      PersonalEntry(PersonalKey(PersonalKind.MEDIA_FLAG, media, PersonalFields.SHARED_ORIGIN), t + 19, author, PersonalValue.Text("Foto/2026/Oslo"), fp),
    )
    for (entry in entries) {
      val bytes = PersonalRecords.encodeOrNull(entry)
      assertWithMessage(entry.key.field).that(bytes).isNotNull()
      assertThat(PersonalRecords.decode(bytes!!, entry.key.digest()).acceptedOrNull()).isEqualTo(entry)
    }
  }
}
