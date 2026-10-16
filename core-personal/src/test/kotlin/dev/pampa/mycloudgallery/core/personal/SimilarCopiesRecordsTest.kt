package dev.pampa.mycloudgallery.core.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.codec.PersonalRecords
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SimilarCopiesRecords
import dev.pampa.mycloudgallery.core.personal.store.CatalogPersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 5, D36 (P0): le voci personali delle copie simili ("Mostra questa come principale", "Separa") sono voci `MEDIA_FLAG` con campi
 * nuovi. Qui si prova che **il formato non cambia**: il codec di oggi le scrive e le rilegge identiche (una versione precedente
 * dell'app riconosce il tipo dalla chiave e non scarta il segmento), e la proiezione sul catalogo non le confonde con un preferito o un
 * nascosto. La loro proiezione sulle copie simili (P15) e' in `SimilarCopiesProjectionTest`.
 */
@RunWith(AndroidJUnit4::class)
class SimilarCopiesRecordsTest {

  private val a = MediaId.random().value
  private val b = MediaId.random().value
  private val author = TrustIds.random()

  @Test
  fun laSeparazioneESimmetricaESulMediaMinore() {
    val ab = SimilarCopiesRecords.separation(a, b)
    assertThat(SimilarCopiesRecords.separation(b, a)).isEqualTo(ab)
    assertThat(ab.kind).isEqualTo(PersonalKind.MEDIA_FLAG)
    assertThat(ab.subject).isEqualTo(minOf(a, b))
    assertThat(SimilarCopiesRecords.separatedPair(ab)).isEqualTo(minOf(a, b) to maxOf(a, b))
    assertThat(PersonalFields.isSimilarApart(ab.field)).isTrue()
    assertThat(SimilarCopiesRecords.separatedPair(SimilarCopiesRecords.cover(a))).isNull()
    assertThrows(IllegalArgumentException::class.java) { SimilarCopiesRecords.separation(a, a) }
  }

  @Test
  fun ilCodecDiOggiLeScriveELeRileggeIdentiche() {
    val entries = listOf(
      PersonalEntry(SimilarCopiesRecords.cover(a), 1_792_000_000_000L, author, PersonalValue.Flag(true)),
      PersonalEntry(SimilarCopiesRecords.separation(a, b), 1_792_000_000_001L, author, PersonalValue.Flag(true)),
      PersonalEntry(SimilarCopiesRecords.separation(a, b), 1_792_000_000_002L, author, PersonalValue.Removed),
    )
    for (entry in entries) {
      val bytes = PersonalRecords.encodeOrNull(entry)
      assertThat(bytes).isNotNull()
      val back = PersonalRecords.decode(bytes!!, entry.key.digest())
      assertThat(back).isInstanceOf(Verdict.Accepted::class.java)
      assertThat((back as Verdict.Accepted).value).isEqualTo(entry)
    }
  }

  @Test
  fun laProiezioneNonLeConfondeConPreferitoONascosto() = runBlocking {
    val db = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
    try {
      val catalog = MediaCatalog(db, AccountId.of("alessio"), zone = { ZoneId.of("Europe/Rome") }) { 1_760_000_000_000L }
      val projection = CatalogPersonalProjection(catalog, db)
      for (key in listOf(SimilarCopiesRecords.cover(a), SimilarCopiesRecords.separation(a, b))) {
        // Sono scelte sulle copie simili (P15): con media che questo catalogo non conosce restano in attesa, e mai un preferito o un nascosto.
        val result = projection.apply(PersonalEntry(key, 1_792_000_000_000L, author, PersonalValue.Flag(true)))
        assertThat(result).isEqualTo(ProjectionResult.UNRESOLVED)
        assertThat(db.personalDao().get(a)).isNull()
        assertThat(db.personalDao().get(b)).isNull()
      }
    } finally {
      db.close()
    }
  }
}
