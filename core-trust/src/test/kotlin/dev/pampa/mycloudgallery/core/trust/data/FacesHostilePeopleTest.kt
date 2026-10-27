package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.JsonValue
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.data.AlbumFx.Companion.with
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import org.junit.Test

/**
 * Le voci `people` contro dati ostili (Fase 8, P3, "Fatto quando": testi con caratteri vietati, campi sconosciuti, valori d'altra forma;
 * §2.4 "Voci people", D13, D23, D24). Un campo sconosciuto o un valore d'altra forma rifiuta il segmento intero.
 */
class FacesHostilePeopleTest {

  private val fx = FacesFx()
  private val a = fx.person(1).base32()
  private val b = fx.person(2).base32()
  private val loc = fx.locator(1).encode()

  private fun reason(bytes: ByteArray, key: ByteArray): RejectReason? =
    FacesFx.rawSegment(listOf(key to bytes), cls = 20).let { (s, ref) -> FacesDocs.decodeSegment(s, ref, SegmentClass.PEOPLE).rejectionOrNull() }

  /** La voce onesta [subject]/[field] con [value], poi un campo del JSON cambiato ([key] = [replace]). */
  private fun reason(subject: String, field: String, value: String?, key: String? = null, replace: JsonValue? = null): RejectReason? {
    val doc = PeopleDoc(a, "name", "Uno", fx.now, fx.phone.deviceId)
    var obj = AlbumFx.json(FacesDocs.encodePeople(doc)).with("subject", subject.json()).with("field", field.json()).with("value", value?.json())
    if (key != null) obj = obj.with(key, replace)
    return reason(CanonicalJson.encode(obj), PeopleForms.key(subject, field))
  }

  @Test
  fun ogniFormaOnestaPassa() {
    for (doc in fx.everyPeople()) assertWithMessage(doc.field).that(reason(doc.subject, doc.field, doc.value)).isNull()
  }

  @Test
  fun campiSconosciutiESoggettiSbagliati() {
    assertThat(reason(a, "nickname", "x")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "Name", "x")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(loc, "name", "Uno")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "is", b)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "ignore", "true")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(loc, "hidden", "true")).isEqualTo(RejectReason.INVALID_FIELD)
    // Una persona nella sola scrittura canonica, mai l'id riservato tutto zero; un localizzatore nella sola forma di `encode`.
    assertThat(reason(a.uppercase(), "hidden", "true")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason("0".repeat(26), "hidden", "true")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(loc.replace(":0:", ":00:"), "ignore", "true")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason("$loc ", "ignore", "true")).isEqualTo(RejectReason.INVALID_FIELD)
  }

  /** D24: il nome da 1 a 64 caratteri, senza spazi ai bordi, senza controlli, caratteri di direzione o invisibili. */
  @Test
  fun nomiConCaratteriVietati() {
    assertThat(reason(a, "name", "Anna Maria")).isNull()
    assertThat(reason(a, "name", "x".repeat(64))).isNull()
    for (bad in listOf("", " ", "x".repeat(65), " Anna", "Anna ", "An‮na", "An​na", "An⁦na", "﻿Anna", "An­na", "Anna￿")) {
      assertWithMessage("nome ${bad.map { it.code }}").that(reason(a, "name", bad)).isEqualTo(RejectReason.INVALID_FIELD)
    }
    // Un carattere di controllo non e' nemmeno un JSON canonico accettabile come nome.
    assertThat(reason(a, "name", "An\u0007na")).isNotNull()
    assertThat(reason(a, "name", "An\nna")).isNotNull()
  }

  @Test
  fun valoriDAltraForma() {
    assertThat(reason(a, "hidden", "yes")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "hidden", null)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "forget", "false")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "forget", null)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "alias", a)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "alias", "x")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "alias", null)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "apart:$a", "true")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "apart:xyz", "true")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "apart:$b", "false")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "born", "split:$a")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "born", b)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "born", null)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "cover", a)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "cover", loc.replace(":0:", ":00:"))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(loc, "is", "true")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(loc, "is", loc)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(loc, "not:xyz", "true")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(loc, "not:$b", "false")).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(loc, "ignore", "false")).isEqualTo(RejectReason.INVALID_FIELD)
    // Un valore oltre il tetto del testo (128 caratteri).
    assertThat(reason(a, "name", "x".repeat(PeopleForms.MAX_TEXT_CHARS + 1))).isEqualTo(RejectReason.TOO_LARGE)
  }

  @Test
  fun oraAutoreECampiDelJson() {
    assertThat(reason(a, "hidden", "true", "at", 1_000L.json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "hidden", "true", "at", (TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS + 1).json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "hidden", "true", "author", "non-un-dispositivo".json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(a, "hidden", "true", "extra", 1L.json())).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(a, "hidden", "true", "at", null)).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(a, "hidden", "true", "subject", 3L.json())).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun chiaviTettiEJsonNonCanonico() {
    val doc = PeopleDoc(a, "hidden", "true", fx.now, fx.phone.deviceId)
    val bytes = FacesDocs.encodePeople(doc)
    assertThat(reason(bytes, doc.key)).isNull()
    assertThat(reason(bytes, PeopleForms.key(b, "hidden"))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(bytes, PeopleForms.key(a, "forget"))).isEqualTo(RejectReason.INVALID_FIELD)
    // Spazi: il lettore stretto non li ammette; un escape superfluo si legge ma non e' la forma canonica.
    val spaced = String(bytes, Charsets.UTF_8).replace(",", ", ").toByteArray(Charsets.UTF_8)
    assertThat(reason(spaced, doc.key)).isEqualTo(RejectReason.MALFORMED)
    val escaped = String(bytes, Charsets.UTF_8).replace("\"value\":\"true\"", "\"value\":\"\\u0074rue\"").toByteArray(Charsets.UTF_8)
    assertThat(escaped).isNotEqualTo(bytes)
    assertThat(reason(escaped, doc.key)).isEqualTo(RejectReason.NOT_CANONICAL)
    assertThat(reason(ByteArray(TrustLimits.MAX_PEOPLE_ENTRY_BYTES + 1) { 'x'.code.toByte() }, doc.key)).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reason("[]".toByteArray(), doc.key)).isEqualTo(RejectReason.MALFORMED)
  }
}
