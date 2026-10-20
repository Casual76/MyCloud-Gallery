package dev.pampa.mycloudgallery.core.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.codec.PersonalRecords
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SimilarCopiesRecords
import dev.pampa.mycloudgallery.core.personal.store.CatalogPersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.JsonString
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
 * nascosto. 20/10: nemmeno con l'impronta dell'altro media nel valore di una separazione (un testo). La loro proiezione sulle copie
 * simili (P15) e' in `SimilarCopiesProjectionTest`.
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

  // --- 20/10: la separazione porta l'impronta dell'altro media ---------------------------------------------------------------------

  private val low = minOf(a, b)
  private val high = maxOf(a, b)
  private val otherFp = PersonalFingerprint(123_456L, QuickHash("ab".repeat(32)))
  private val subjectFp = PersonalFingerprint(7L, QuickHash("cd".repeat(32)))

  @Test
  fun laSeparazionePortaLImprontaDellAltroMediaInUnTestoESiRileggeIdentica() {
    val entry = PersonalEntry(SimilarCopiesRecords.separation(a, b), 1_792_000_000_003L, author, PersonalValue.Apart(otherFp), subjectFp)
    val bytes = PersonalRecords.encodeOrNull(entry)!!
    val qh = Base32.encode(ByteArray(32) { 0xab.toByte() })
    val subjectQh = Base32.encode(ByteArray(32) { 0xcd.toByte() })
    assertThat(String(bytes, Charsets.UTF_8)).isEqualTo(
      """{"at":1792000000003,"author":"$author","field":"similar_apart:$high","fp":{"qh":"$subjectQh","size":7},"media":"$low","value":"$qh:123456"}""",
    )
    val back = (PersonalRecords.decode(bytes, entry.key.digest()) as Verdict.Accepted).value
    assertThat(back).isEqualTo(entry)
    assertThat(SimilarCopiesRecords.separates(back.value)).isTrue()
    assertThat(SimilarCopiesRecords.otherFingerprint(back.value)).isEqualTo(otherFp)
    // Mai l'impronta nei log.
    assertThat(back.value.toString()).doesNotContain(qh)
  }

  @Test
  fun unLettoreDiPrimaLaLeggeComeUnTestoQualunque_nessunCampoNuovoENessunaVersioneNuova() {
    val entry = PersonalEntry(SimilarCopiesRecords.separation(a, b), 1_792_000_000_003L, author, PersonalValue.Apart(otherFp), subjectFp)
    val bytes = PersonalRecords.encodeOrNull(entry)!!
    // Gli stessi membri di sempre e un valore di un tipo che il formato ammette gia' (un testo entro 1024 caratteri, senza caratteri
    // vietati): il lettore stretto di una versione precedente non scarta il segmento.
    val json = CanonicalJson.parseCanonical(bytes) as JsonObject
    assertThat(json.members.keys).containsExactly("at", "author", "field", "fp", "media", "value")
    assertThat(json.members["value"]).isInstanceOf(JsonString::class.java)
    // Per quel lettore e' un testo, come per questo un testo in un campo che non e' una separazione: lo stesso corpo sotto un altro tipo.
    val asOther = PersonalKey(PersonalKind.ALBUM, entry.key.subject, entry.key.field)
    val read = (PersonalRecords.decode(bytes, asOther.digest()) as Verdict.Accepted).value
    assertThat(read.value).isEqualTo(PersonalValue.Text(PersonalValue.Apart(otherFp).text))
    // Un testo non separa: la proiezione e il rilevatore di quella versione separano solo con `true`, quindi non toccano niente.
    assertThat(SimilarCopiesRecords.separates(read.value)).isFalse()
    // E il segmento e' ancora della versione 1 del formato.
    val segment = PersonalSegments.encode(listOf(entry))
    assertThat(segment.bytes[4].toInt()).isEqualTo(1)
    assertThat((PersonalSegments.decode(segment.bytes, segment.ref) as Verdict.Accepted).value).containsExactly(entry)
  }

  @Test
  fun unaVoceDelFormatoDiPrimaSiLeggeComePrima() {
    // `true`: separa, e l'altro media si ritrova solo per id (nessuna impronta).
    val old = PersonalEntry(SimilarCopiesRecords.separation(a, b), 1_792_000_000_001L, author, PersonalValue.Flag(true), subjectFp)
    val back = (PersonalRecords.decode(PersonalRecords.encodeOrNull(old)!!, old.key.digest()) as Verdict.Accepted).value
    assertThat(back.value).isEqualTo(PersonalValue.Flag(true))
    assertThat(SimilarCopiesRecords.separates(back.value)).isTrue()
    assertThat(SimilarCopiesRecords.otherFingerprint(back.value)).isNull()
    // Chi non conosce l'impronta dell'altro media scrive come prima; la lapide e il flag spento non separano.
    assertThat(SimilarCopiesRecords.apart(null)).isEqualTo(PersonalValue.Flag(true))
    assertThat(SimilarCopiesRecords.apart(otherFp)).isEqualTo(PersonalValue.Apart(otherFp))
    assertThat(SimilarCopiesRecords.separates(PersonalValue.Removed)).isFalse()
    assertThat(SimilarCopiesRecords.separates(PersonalValue.Flag(false))).isFalse()
  }

  @Test
  fun unTestoFuoriFormaInUnaSeparazioneScartaLaVoce_eAltroveResta_unTesto() {
    val key = SimilarCopiesRecords.separation(a, b)
    val good = PersonalRecords.encode(PersonalEntry(key, 1_792_000_000_003L, author, PersonalValue.Apart(otherFp)))
    val text = String(good, Charsets.UTF_8)
    val qh = Base32.encode(ByteArray(32) { 0xab.toByte() })
    fun reason(value: String): RejectReason? =
      (PersonalRecords.decode(text.replace("\"$qh:123456\"", "\"$value\"").toByteArray(), key.digest()) as? Verdict.Rejected)?.reason
    assertThat(reason("$qh:123456")).isNull()
    for (hostile in listOf("", "abc", "$qh", "$qh:", ":123456", "$qh:0123", "$qh:-1", "$qh:+1", "$qh: 1", "$qh:1:2", "$qh:99999999999999999999",
      qh.uppercase() + ":1", qh.dropLast(1) + ":1", qh + "0:1", qh.dropLast(1) + "z:1", "$qh.123456")) {
      assertThat(reason(hostile)).isEqualTo(RejectReason.INVALID_FIELD)
    }
    // L'impronta dell'altro media vale solo in una separazione: non si costruisce altrove, e altrove lo stesso testo e' un testo.
    assertThrows(IllegalArgumentException::class.java) {
      PersonalEntry(SimilarCopiesRecords.cover(a), 1_792_000_000_003L, author, PersonalValue.Apart(otherFp))
    }
    val album = PersonalEntry(PersonalKey(PersonalKind.ALBUM, "album", PersonalFields.ALBUM_DEFINITION), 1_792_000_000_003L, author, PersonalValue.Text("$qh:123456"))
    assertThat((PersonalRecords.decode(PersonalRecords.encode(album), album.key.digest()) as Verdict.Accepted).value).isEqualTo(album)
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
