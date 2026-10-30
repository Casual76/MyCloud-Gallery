package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import org.junit.Test

/**
 * Fase 8, correzioni C (L362, terzo punto di R9): il nome di una persona nel formato vuole **almeno un carattere che si veda** (la stessa regola con cui si scrive,
 * `PersonNames.forNewName`). Un nome fatto solo di giunti ZWJ e selettori di variante, scritto da un altro dispositivo, non e' una voce del formato; e il segmento che
 * lo porta **non si perde**: le altre voci valgono, la sola voce con quel nome si salta.
 */
class FacesNameVisibleTest {

  private val fx = FacesFx()

  /** Solo giunti e selettori di variante: sei byte in UTF-8, appare vuoto. */
  private val invisible = "\u200D\uFE0F"

  @Test
  fun unNomeCheNonSiVedeNonEUnNomeDelFormato() {
    assertThat(PeopleForms.isName(invisible)).isFalse()
    assertThat(PeopleForms.isName("\uFE0F")).isFalse()
    assertThat(PeopleForms.isName("\u200D\u200D\u200D")).isFalse()
    // La forma di prima (lunghezza, bordi, caratteri vietati) la sa ancora distinguere: serve a saltare la voce e non il segmento.
    assertThat(PeopleForms.hasNameShape(invisible)).isTrue()
    assertThat(PeopleForms.hasNameShape(" x")).isFalse()
    assertThat(PeopleForms.hasNameShape("")).isFalse()
    assertThat(PeopleForms.hasNameShape("x".repeat(65))).isFalse()
  }

  @Test
  fun unEmojiUnSimboloEUnAltroAlfabetoSonoNomi() {
    for (name in listOf("❤", "❤\uFE0F", "?", "Анна", "李明", "Anna", "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67")) {
      assertThat(PeopleForms.isName(name)).isTrue()
    }
    val a = fx.person(1).base32()
    assertThat(PeopleForms.fits(a, PeopleForms.NAME, invisible)).isFalse()
    assertThat(PeopleForms.fits(a, PeopleForms.NAME, "❤")).isTrue()
    // La lapide (nome tolto) resta ammessa.
    assertThat(PeopleForms.fits(a, PeopleForms.NAME, null)).isTrue()
  }

  @Test
  fun unaVoceConUnNomeCheNonSiVedeNonSiCostruisce() {
    val a = fx.person(1).base32()
    val author = fx.phone.deviceId
    val error = runCatching { PeopleDoc(a, PeopleForms.NAME, invisible, fx.now, author) }.exceptionOrNull()
    assertThat(error).isNotNull()
  }

  /** Un segmento scritto da un dispositivo che non aveva ancora la regola: tre voci, una con un nome che non si vede (byte per byte, stessa lunghezza). */
  private fun legacySegment(): EncodedSegment {
    val a = fx.person(1).base32()
    val b = fx.person(2).base32()
    val author = fx.phone.deviceId
    val placeholder = "XXXXXX"
    val written = FacesDocs.peopleSegment(
      listOf(
        PeopleDoc(a, PeopleForms.NAME, placeholder, fx.now, author),
        PeopleDoc(a, PeopleForms.HIDDEN, "true", fx.now, author),
        PeopleDoc(b, PeopleForms.NAME, "Persona Due", fx.now, author),
      ),
    )
    val from = placeholder.toByteArray(Charsets.UTF_8)
    val to = invisible.toByteArray(Charsets.UTF_8)
    assertThat(to.size).isEqualTo(from.size)
    val bytes = written.bytes.copyOf()
    val at = indexOf(bytes, from)
    assertThat(at).isAtLeast(0)
    to.copyInto(bytes, at)
    return EncodedSegment(written.cls, bytes, SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong()), written.entries)
  }

  private fun indexOf(bytes: ByteArray, part: ByteArray): Int {
    for (i in 0..bytes.size - part.size) if (part.indices.all { bytes[i + it] == part[it] }) return i
    return -1
  }

  @Test
  fun ilSegmentoConUnNomeCheNonSiVedeNonSiPerde_SiSaltaLaSolaVoce() {
    val segment = legacySegment()

    val content = FacesDocs.decodeSegment(segment.bytes, segment.ref, SegmentClass.PEOPLE).acceptedOrNull() as? FacesSegmentContent.People
    assertThat(content).isNotNull()

    // Le altre due voci valgono (una lapide e un nome vero), la sola voce con il nome che non si vede e' saltata e contata.
    assertThat(content!!.entries.map { it.field }).containsExactly(PeopleForms.HIDDEN, PeopleForms.NAME)
    assertThat(content.entries.mapNotNull { it.value }).containsExactly("true", "Persona Due")
    assertThat(content.skipped).isEqualTo(1)
    assertThat(content.size).isEqualTo(3)
  }

  @Test
  fun unaVoceSaltataControllaComunqueIlResto_OraAutoreESoggetto() {
    val a = fx.person(1).base32()
    val author = fx.phone.deviceId
    val placeholder = "XXXXXX"
    val written = FacesDocs.peopleSegment(listOf(PeopleDoc(a, PeopleForms.NAME, placeholder, fx.now, author)))
    // Un autore che non e' un id di dispositivo rovina la voce: si rifiuta il segmento, anche se il nome non si vedeva.
    val from = author.toByteArray(Charsets.UTF_8)
    val bytes = written.bytes.copyOf()
    val authorAt = indexOf(bytes, from)
    assertThat(authorAt).isAtLeast(0)
    bytes[authorAt] = '!'.code.toByte()
    val nameAt = indexOf(bytes, placeholder.toByteArray(Charsets.UTF_8))
    invisible.toByteArray(Charsets.UTF_8).copyInto(bytes, nameAt)
    val ref = SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong())

    val verdict = FacesDocs.decodeSegment(bytes, ref, SegmentClass.PEOPLE)

    assertThat(verdict.acceptedOrNull()).isNull()
    assertThat(verdict.rejectionOrNull()).isNotNull()
  }

  @Test
  fun unNomeDiAltraFormaRifiutaAncoraIlSegmento() {
    val a = fx.person(1).base32()
    val author = fx.phone.deviceId
    // Uno spazio ai bordi e' un valore d'altra forma (non un nome che non si vede): il segmento intero si rifiuta, come sempre.
    val written = FacesDocs.peopleSegment(
      listOf(PeopleDoc(a, PeopleForms.NAME, "Xxxxxx", fx.now, author), PeopleDoc(a, PeopleForms.HIDDEN, "true", fx.now, author)),
    )
    val bytes = written.bytes.copyOf()
    val at = indexOf(bytes, "Xxxxxx".toByteArray(Charsets.UTF_8))
    " xxxxx".toByteArray(Charsets.UTF_8).copyInto(bytes, at)
    val verdict = FacesDocs.decodeSegment(bytes, SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong()), SegmentClass.PEOPLE)
    assertThat(verdict.acceptedOrNull()).isNull()
    assertThat(verdict.rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
  }
}
