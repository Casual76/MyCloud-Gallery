package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.JsonValue
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.data.AlbumFx.Companion.with
import dev.pampa.mycloudgallery.core.trust.data.AlbumFx.Companion.withValue
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il codec ostile delle voci e dei segmenti d'album (Fase 7, P2, "Fatto quando": test ostili per ogni campo e ogni tetto; A4 del §0.3,
 * D7, D8): lunghezze, UTF-8 non valido, caratteri di controllo e di direzione, percorsi con `..`, assoluti, dentro `.mycloudgallery` (anche
 * con `．` a larghezza piena), campi sconosciuti, corpi oltre i tetti, versioni 1, 2 e 4, id che non torna con account e nonce. Ogni
 * violazione rifiuta il segmento intero con il suo motivo.
 */
class AlbumHostileTest {

  private val fx = AlbumFx()
  private val definition = fx.entry(AlbumField.Definition, fx.definition())
  private val item = fx.entry(AlbumField.Item(fx.media(1)), fx.item(1))

  private fun key(field: AlbumField): ByteArray = AlbumKey.of(fx.album, field).bytes()

  /** Il motivo del rifiuto di un segmento con la sola voce [json] sotto [key] (null = accettato). */
  private fun reason(json: ByteArray, key: ByteArray, context: AlbumSegmentContext = fx.context()): RejectReason? {
    val (bytes, ref) = AlbumFx.rawSegment(listOf(key to json))
    return AlbumDocs.decodeSegment(bytes, ref, context).rejectionOrNull()
  }

  private fun reason(obj: JsonObject, field: AlbumField, context: AlbumSegmentContext = fx.context()): RejectReason? =
    reason(CanonicalJson.encode(obj), key(field), context)

  private fun defJson(): JsonObject = AlbumFx.json(AlbumDocs.encodeEntry(definition))

  private fun itemJson(): JsonObject = AlbumFx.json(AlbumDocs.encodeEntry(item))

  private fun def(key: String, value: JsonValue?) = reason(defJson().withValue(key, value), AlbumField.Definition)

  private fun itm(key: String, value: JsonValue?) = reason(itemJson().withValue(key, value), item.field)

  @Test
  fun ogniVoceValidaSiRilegge_ancheDallAccountScrittoConLaMaiuscola() {
    for (entry in fx.everyKind()) assertWithMessage(entry.toString()).that(reason(AlbumDocs.encodeEntry(entry), entry.key.bytes())).isNull()
    // Il tablet ha `Test2` nel registro: le sue voci dicono `test2` (una sola scrittura dell'account).
    for (entry in fx.everyKind(fx.tablet)) {
      assertThat(entry.account).isEqualTo(AccountId("test2"))
      assertThat(reason(AlbumDocs.encodeEntry(entry), entry.key.bytes(), fx.context(fx.tablet))).isNull()
    }
    for (entry in fx.everyKind()) assertThat(AlbumDocs.admits(entry)).isTrue()
  }

  @Test
  fun lunghezzeETetti() {
    val cases = listOf<Pair<String, () -> RejectReason?>>(
      "titolo di 65 caratteri" to { def("title", "a".repeat(65).json()) },
      "titolo di 65 emoji (130 unita')" to { def("title", "😀".repeat(65).json()) },
      "titolo vuoto" to { def("title", "".json()) },
      "titolo con spazi ai bordi" to { def("title", " Oslo".json()) },
      "cartella oltre 1.024 caratteri" to { def("folder", "a".repeat(1_025).json()) },
      "cartella oltre 1.024 byte" to { def("folder", "é".repeat(600).json()) },
      "percorso oltre 1.024 byte" to { itm("path", ("Foto/" + "é".repeat(510)).json()) },
      "account di 65 caratteri" to { reason(itemJson().with("account", "a".repeat(65).json()), item.field) },
      "nome del campo di 33 caratteri" to { reason(itemJson().with("field", ("item:" + "0".repeat(28)).json()), item.field) },
      "posizione negativa" to { itm("position", (-1L).json()) },
      "posizione oltre 2^53" to { itm("position", ((1L shl 53) + 1).json()) },
      "afterClose di 17 caratteri" to {
        reason(defJson().withValue("state", "closed".json()).withValue("afterClose", "a".repeat(17).json()), AlbumField.Definition)
      },
    )
    for ((name, run) in cases) assertWithMessage(name).that(run()).isNotNull()
    assertThat(def("title", "a".repeat(65).json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(def("title", "😀".repeat(65).json())).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(def("folder", "é".repeat(600).json())).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(itm("path", ("Foto/" + "é".repeat(510)).json())).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reason(itemJson().with("account", "a".repeat(65).json()), item.field)).isEqualTo(RejectReason.TOO_LARGE)
    // Al tetto si passa: 64 emoji di titolo, un percorso di 1.024 byte esatti.
    assertThat(def("title", "😀".repeat(64).json())).isNull()
    val exact = List(5) { "a".repeat(200) }.joinToString("/") + "/" + "b".repeat(19)
    assertThat(exact.toByteArray().size).isEqualTo(1_024)
    assertThat(itm("path", exact.json())).isNull()
    assertThat(itm("path", (exact + "c").json())).isEqualTo(RejectReason.TOO_LARGE)
    // Un corpo oltre 4 KiB si rifiuta dalla tabella, prima di leggerlo.
    assertThat(reason(ByteArray(AlbumLimits.MAX_ENTRY_BYTES + 1) { 'x'.code.toByte() }, key(item.field))).isEqualTo(RejectReason.TOO_LARGE)
    // La storia dei livelli: 32 cambi si', 33 no.
    val at = fx.now
    fun history(n: Int) = (0 until n).map { jsonObject("at" to (at - (n - it) * 1_000L).json(), "level" to "add".json()) }.json()
    val level = AlbumFx.json(AlbumDocs.encodeEntry(fx.entry(AlbumField.Level, AlbumValue.Levels(listOf(LevelChange(at, ShareLevel.ADD))))))
    assertThat(reason(level.withValue("history", history(32)), AlbumField.Level)).isNull()
    assertThat(reason(level.withValue("history", history(33)), AlbumField.Level)).isEqualTo(RejectReason.TOO_LARGE)
  }

  @Test
  fun utf8NonValido() {
    val good = AlbumDocs.encodeEntry(definition)
    val at = String(good, Charsets.UTF_8).indexOf("Oslo")
    val bad = listOf(
      byteArrayOf(0xC3.toByte()), // sequenza troncata
      byteArrayOf(0xC0.toByte(), 0xAF.toByte()), // forma lunga di '/'
      byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // surrogato
      byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // oltre U+10FFFF
      byteArrayOf(0xFF.toByte()),
    )
    for (sequence in bad) {
      val bytes = good.copyOfRange(0, at) + sequence + good.copyOfRange(at + 4, good.size)
      assertWithMessage(sequence.joinToString { "%02x".format(it) }).that(reason(bytes, key(AlbumField.Definition))).isEqualTo(RejectReason.MALFORMED)
    }
  }

  @Test
  fun caratteriDiControlloDiDirezioneEInvisibili() {
    for (c in listOf("\u0007", "\u0000", "\u007F", "\u0085", "\u202E", "\u202A", "\u2066", "\u2069", "\u200E", "\u200F", "\u061C", "\u200B", "\uFEFF", "\u00AD", "\uFFFE", "\u2028")) {
      val code = "U+%04X".format(c[0].code)
      assertWithMessage("titolo con $code").that(def("title", "Oslo${c}2026".json())).isEqualTo(RejectReason.INVALID_FIELD)
      assertWithMessage("percorso con $code").that(itm("path", "Album condivisi/Oslo${c}2026/a.jpg".json())).isEqualTo(RejectReason.HOSTILE_PATH)
      assertWithMessage("cartella con $code").that(def("folder", "Album condivisi/Oslo$c".json())).isEqualTo(RejectReason.HOSTILE_PATH)
    }
    // Lo ZWJ e i selettori di presentazione servono alle emoji composte: ammessi.
    assertThat(def("title", "Famiglia 👨\u200D👩\u200D👧 ❤\uFE0F".json())).isNull()
    // Gli account: ASCII stretto e minuscolo.
    assertThat(itm("addedBy", "Test".json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(itm("addedBy", "te st".json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(itm("addedBy", "tést".json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(def("creator", "-test".json())).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun percorsiOstili() {
    val hostile = listOf(
      "../x.jpg", "Album condivisi/../x.jpg", "./x.jpg", "/x.jpg", "\\\\server\\x.jpg", "Foto\\x.jpg", "C:/x.jpg", "Foto//x.jpg", "Foto/",
      ".mycloudgallery/family/registry/00000000000000000001", ".MyCloudGallery/x.jpg", ".MYCLOUDGALLERY",
      "\uFF0Emycloudgallery/x.jpg", "\uFF0EMYCLOUDGALLERY/x", "Album condivisi/\uFF0E\uFF0E/x.jpg", "Foto\uFF0Fx.jpg", "Foto\uFF3Cx.jpg",
      "CON/x.jpg", "Foto/nul.jpg", "Foto/COM1", "Foto/LPT\u00B9.txt", "a./x.jpg", "Foto/x.jpg ", "Foto/a:b.jpg", "Foto/a*b.jpg", "",
    )
    for (path in hostile) {
      assertWithMessage("percorso «$path»").that(itm("path", path.json())).isEqualTo(RejectReason.HOSTILE_PATH)
      assertWithMessage("cartella «$path»").that(def("folder", path.json())).isEqualTo(RejectReason.HOSTILE_PATH)
    }
    // Accenti, emoji, spazi interni: ammessi.
    assertThat(itm("path", "Album condivisi/Estate in Puglia ☀️/IMG 0001.jpg".json())).isNull()
    // Chi scrive non puo' nemmeno costruire un percorso cosi'.
    assertThrows(Exception::class.java) { SharePath.parseMediaPath("\uFF0Emycloudgallery/x.jpg") }
  }

  @Test
  fun campiSconosciutiEValoriFuoriForma() {
    assertThat(reason(defJson().with("extra", 1L.json()), AlbumField.Definition)).isEqualTo(RejectReason.MALFORMED)
    assertThat(def("extra", 1L.json())).isEqualTo(RejectReason.MALFORMED)
    assertThat(itm("extra", "x".json())).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(defJson().with("field", "titolo".json()), AlbumField.Definition)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(itemJson().with("field", "item:${"z".repeat(26)}".json()), item.field)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(def("state", "archived".json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(def("nonce", "0".repeat(25).json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(itm("key", "0".repeat(51).json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(itm("version", "0".repeat(27).json())).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(itm("position", "1000".json())).isEqualTo(RejectReason.MALFORMED)
    val sort = AlbumFx.json(AlbumDocs.encodeEntry(fx.entry(AlbumField.Sort, AlbumValue.Sort(dev.pampa.mycloudgallery.core.model.AlbumSort.CUSTOM))))
    assertThat(reason(sort.withValue("sort", "random".json()), AlbumField.Sort)).isEqualTo(RejectReason.INVALID_FIELD)
    val level = AlbumFx.json(AlbumDocs.encodeEntry(fx.entry(AlbumField.Level, AlbumValue.Levels(listOf(LevelChange(fx.now, ShareLevel.ADD))))))
    assertThat(reason(level.withValue("history", listOf(jsonObject("at" to fx.now.json(), "level" to "admin".json())).json()), AlbumField.Level))
      .isEqualTo(RejectReason.INVALID_FIELD)
    // Q5: afterClose solo con `closed`/`deleted`, obbligatorio li'; un codice sconosciuto si legge (e vale `remove` in core-albums).
    val closed = defJson().withValue("state", "closed".json())
    assertThat(reason(closed, AlbumField.Definition)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(closed.withValue("afterClose", "keep_copy".json()), AlbumField.Definition)).isNull()
    assertThat(reason(closed.withValue("afterClose", "zzz".json()), AlbumField.Definition)).isNull()
    assertThat(reason(closed.withValue("afterClose", "Keep-Copy".json()), AlbumField.Definition)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(def("afterClose", "remove".json())).isEqualTo(RejectReason.INVALID_FIELD)
  }
}

class AlbumSegmentHostileTest {

  private val fx = AlbumFx()
  private val item = fx.entry(AlbumField.Item(fx.media(1)), fx.item(1))
  private val itemRow = item.key.bytes() to AlbumDocs.encodeEntry(item)

  private fun decode(bytes: ByteArray, ref: SegmentRef, context: AlbumSegmentContext = fx.context()) = AlbumDocs.decodeSegment(bytes, ref, context)

  private fun reason(rows: List<Pair<ByteArray, ByteArray>>, version: Int = 3, cls: Int = 17, count: Int = rows.size, reserved: Int = 0): RejectReason? {
    val (bytes, ref) = AlbumFx.rawSegment(rows, version, cls, count, reserved)
    return decode(bytes, ref).rejectionOrNull()
  }

  /** "Versioni 1, 2 e 4": la 3 sola vale per i segmenti d'album; la 4 chiede l'aggiornamento. */
  @Test
  fun versioniEClasseDelSegmento() {
    assertThat(reason(listOf(itemRow))).isNull()
    assertThat(reason(listOf(itemRow), version = 1)).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(listOf(itemRow), version = 2)).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(listOf(itemRow), version = 4)).isEqualTo(RejectReason.NEWER_FORMAT)
    assertThat(reason(listOf(itemRow), cls = SegmentClass.PERSONAL.code)).isEqualTo(RejectReason.WRONG_TYPE)
    assertThat(reason(listOf(itemRow), cls = SegmentClass.META.code)).isEqualTo(RejectReason.WRONG_TYPE)
    assertThat(reason(listOf(itemRow), reserved = 1)).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun tabellaOstile() {
    val other = fx.entry(AlbumField.Item(fx.media(2)), fx.item(2))
    val otherRow = other.key.bytes() to AlbumDocs.encodeEntry(other)
    val rows = listOf(itemRow, otherRow).sortedWith { a, b -> IndexKeys.compare(a.first, b.first) }
    assertThat(reason(rows)).isNull()
    assertThat(reason(rows.reversed())).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(listOf(itemRow, itemRow))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(emptyList())).isEqualTo(RejectReason.MALFORMED)
    assertThat(reason(listOf(itemRow), count = 2)).isNotNull()
    assertThat(reason(emptyList(), count = AlbumLimits.MAX_ENTRIES_PER_SEGMENT + 1)).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reason(listOf(itemRow.first to ByteArray(0)))).isEqualTo(RejectReason.MALFORMED)
    // La chiave deve tornare con l'album e il campo della voce.
    assertThat(reason(listOf(otherRow.first to itemRow.second))).isEqualTo(RejectReason.INVALID_FIELD)
    val strange = AlbumKey.of(AlbumIds.derive(AccountId("test"), ByteArray(16)), item.field).bytes()
    assertThat(reason(listOf(strange to itemRow.second))).isEqualTo(RejectReason.INVALID_FIELD)
    // Dimensione e impronta della testa; il tetto del segmento prima di tutto.
    val (bytes, ref) = AlbumFx.rawSegment(listOf(itemRow))
    assertThat(decode(bytes, SegmentRef(ref.hash, ref.size + 1)).rejectionOrNull()).isEqualTo(RejectReason.HASH_MISMATCH)
    assertThat(decode(bytes, SegmentRef(dev.pampa.mycloudgallery.core.trust.crypto.Hashes.sha256Name(byteArrayOf(1)), ref.size)).rejectionOrNull())
      .isEqualTo(RejectReason.HASH_MISMATCH)
    assertThat(decode(bytes.copyOf(bytes.size - 1), ref).rejectionOrNull()).isEqualTo(RejectReason.HASH_MISMATCH)
    assertThat(decode(bytes, SegmentRef(ref.hash, AlbumLimits.MAX_SEGMENT_BYTES + 1L)).rejectionOrNull()).isEqualTo(RejectReason.TOO_LARGE)
  }

  /** D8: ogni voce e' del firmatario (autore) e del suo account (`nasUser`, minuscolo). */
  @Test
  fun accountEAutoreDelFirmatario() {
    fun with(key: String, value: String) = CanonicalJson.encode(AlbumFx.json(itemRow.second).with(key, value.json()))
    assertThat(reason(listOf(itemRow.first to with("account", "test2")))).isEqualTo(RejectReason.NOT_OWNER)
    assertThat(reason(listOf(itemRow.first to with("account", "Test")))).isEqualTo(RejectReason.NOT_OWNER)
    assertThat(reason(listOf(itemRow.first to with("author", fx.tablet.deviceId)))).isEqualTo(RejectReason.WRONG_LOCATION)
    // Il segmento del telefono letto come se fosse del tablet: tutto rifiutato.
    val (bytes, ref) = AlbumFx.rawSegment(listOf(itemRow))
    assertThat(decode(bytes, ref, fx.context(fx.tablet)).rejectionOrNull()).isEqualTo(RejectReason.NOT_OWNER)
  }

  @Test
  fun jsonNonCanonicoOFuoriForma() {
    val text = String(itemRow.second, Charsets.UTF_8)
    val cases = listOf(
      "spazio" to text.replaceFirst(":", ": "),
      "a capo finale" to "$text\n",
      "chiavi fuori ordine" to text.replaceFirst("{\"account\":\"test\",\"at\":${item.at},", "{\"at\":${item.at},\"account\":\"test\","),
      "barra escapata" to text.replace("Album condivisi/", "Album condivisi\\/"),
      "null" to text.replaceFirst("}}", "},\"x\":null}"),
      "numero con la virgola" to text.replaceFirst("\"position\":1000", "\"position\":1000.0"),
      "zeri iniziali" to text.replaceFirst("\"position\":1000", "\"position\":01000"),
      "chiave ripetuta" to text.replaceFirst("{\"account\"", "{\"account\":\"test\",\"account\""),
      "intero fuori intervallo" to text.replaceFirst("\"position\":1000", "\"position\":99999999999999999999"),
    )
    for ((name, json) in cases) assertWithMessage(name).that(reason(listOf(itemRow.first to json.toByteArray()))).isNotNull()
    assertThat(reason(listOf(itemRow.first to text.replace("Album condivisi/", "Album condivisi\\/").toByteArray()))).isEqualTo(RejectReason.NOT_CANONICAL)
  }

  @Test
  fun lapidiSoloDoveHannoUnSenso() {
    for (field in listOf(AlbumField.Definition, AlbumField.Level, AlbumField.Heir)) {
      val json = CanonicalJson.encode(jsonObject("account" to "test".json(), "at" to fx.now.json(), "author" to fx.phone.deviceId.json(), "field" to field.wire.json()))
      assertWithMessage(field.wire).that(reason(listOf(AlbumKey.of(fx.album, field).bytes() to json))).isEqualTo(RejectReason.INVALID_FIELD)
    }
    for (field in listOf(AlbumField.Cover, AlbumField.Sort, AlbumField.Item(fx.media(9)))) {
      val entry = fx.entry(field, null)
      assertWithMessage(field.wire).that(reason(listOf(entry.key.bytes() to AlbumDocs.encodeEntry(entry)))).isNull()
    }
    // Un valore che non e' quello del campo (un elemento sotto `cover`).
    val cover = fx.entry(AlbumField.Cover, null)
    val json = CanonicalJson.encode(AlbumFx.json(AlbumDocs.encodeEntry(cover)).with("value", AlbumFx.json(itemRow.second).members.getValue("value")))
    assertThat(reason(listOf(cover.key.bytes() to json))).isNotNull()
  }

  @Test
  fun oreEStoriaDeiLivelli() {
    fun at(value: Long) = CanonicalJson.encode(AlbumFx.json(itemRow.second).with("at", value.json()))
    assertThat(reason(listOf(itemRow.first to at(915_148_800_000L)))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(listOf(itemRow.first to at(-1)))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(listOf(itemRow.first to at(4_200_000_000_000L)))).isEqualTo(RejectReason.INVALID_FIELD)
    val now = fx.now
    val level = fx.entry(AlbumField.Level, AlbumValue.Levels(listOf(LevelChange(now, ShareLevel.ADD))))
    fun history(vararg changes: Pair<Long, String>) = CanonicalJson.encode(
      AlbumFx.json(AlbumDocs.encodeEntry(level)).withValue("history", changes.map { jsonObject("at" to it.first.json(), "level" to it.second.json()) }.json()),
    )
    val key = level.key.bytes()
    assertThat(reason(listOf(key to history(now - 10 to "all", now to "view")))).isNull()
    assertThat(reason(listOf(key to history(now to "all", now - 10 to "view")))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(listOf(key to history(now to "all", now to "view")))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(listOf(key to history(now + 1 to "all")))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(listOf(key to history()))).isEqualTo(RejectReason.INVALID_FIELD)
  }

  /** E-SH14, D7: una definizione con un id che non torna con account e nonce rifiuta il contributo intero; tetti del contributo. */
  @Test
  fun idCheNonTornaETettiDelContributo() {
    val good = fx.entry(AlbumField.Definition, fx.definition())
    assertThat(AlbumDocs.checkContribution(fx.album, listOf(good, item)).acceptedOrNull()).isNotNull()
    val otherAccount = fx.entry(AlbumField.Definition, fx.definition(creator = "test2"))
    assertThat(AlbumDocs.checkContribution(fx.album, listOf(otherAccount)).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    val otherNonce = fx.entry(AlbumField.Definition, fx.definition(nonceBytes = ByteArray(16) { 9 }))
    assertThat(AlbumDocs.checkContribution(fx.album, listOf(otherNonce)).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    // La stessa chiave in due segmenti del contributo.
    assertThat(AlbumDocs.checkContribution(fx.album, listOf(item, item.copy(at = item.at + 1))).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    val many = (0..AlbumLimits.MAX_ENTRIES_PER_CONTRIBUTION).map { fx.entry(AlbumField.Item(fx.media(it)), null) }
    assertThat(AlbumDocs.checkContribution(fx.album, many).rejectionOrNull()).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(AlbumDocs.checkContribution(fx.album, many.dropLast(1)).acceptedOrNull()).isNotNull()
    assertThat(AlbumIds.matches(fx.album, AccountId("test"), fx.nonce)).isTrue()
    assertThat(AlbumIds.matches(fx.album, AccountId("test2"), fx.nonce)).isFalse()
  }

  /** Chi scrive usa le regole di chi legge: non produce mai una voce o un segmento che gli altri rifiuterebbero. */
  @Test
  fun chiScriveNonProduceCioCheChiLeggeRifiuta() {
    assertThrows(IllegalArgumentException::class.java) { fx.definition(title = "Oslo‮") }
    assertThrows(IllegalArgumentException::class.java) { fx.definition(state = AlbumDefinitionState.CLOSED) }
    assertThrows(IllegalArgumentException::class.java) { fx.entry(AlbumField.Definition, null) }
    assertThrows(IllegalArgumentException::class.java) { AlbumValue.Levels(emptyList()) }
    val stranger = fx.entry(AlbumField.Item(fx.media(1)), fx.item(1), albumId = AlbumIds.derive(AccountId("test"), ByteArray(16)))
    assertThrows(IllegalArgumentException::class.java) { AlbumDocs.encodeSegment(fx.album, listOf(item, stranger)) }
    assertThrows(IllegalArgumentException::class.java) { AlbumDocs.encodeSegment(fx.album, listOf(item, item.copy(at = item.at + 1))) }
    assertThrows(IllegalArgumentException::class.java) { AlbumDocs.encodeSegment(fx.album, emptyList()) }
    val big = (0..AlbumLimits.MAX_ENTRIES_PER_SEGMENT).map { fx.entry(AlbumField.Item(fx.media(it)), null) }
    assertThrows(IllegalArgumentException::class.java) { AlbumDocs.encodeSegment(fx.album, big) }
    // Al tetto si scrive e si rilegge.
    val full = AlbumDocs.encodeSegment(fx.album, big.dropLast(1))
    assertThat(AlbumDocs.decodeSegment(full.bytes, full.ref, fx.context()).acceptedOrNull()).hasSize(AlbumLimits.MAX_ENTRIES_PER_SEGMENT)
  }

  @Test
  fun idEChiaviSulNas() {
    val text = AlbumIds.text(fx.album)
    assertThat(text).hasLength(26)
    assertThat(AlbumIds.parseAlbum(text)).isEqualTo(fx.album)
    assertThat(AlbumIds.parseAlbum(text.uppercase())).isNull()
    assertThat(AlbumField.parse(AlbumField.Item(fx.media(4)).wire)).isEqualTo(AlbumField.Item(fx.media(4)))
    assertThat(AlbumField.Item(fx.media(4)).wire).hasLength(31)
    assertThat(AlbumKey.of(fx.album, AlbumField.Cover)).isNotEqualTo(AlbumKey.of(fx.album, AlbumField.Sort))
    assertThat(AlbumKey.of(fx.album, AlbumField.Cover)).isNotEqualTo(AlbumKey.of(AlbumIds.derive(AccountId("test2"), fx.nonce), AlbumField.Cover))
    // Gli esempi tornano con quelli di core-model (D7): una sola derivazione per tutti.
    assertThat(AlbumIds.derive(AccountId("test"), fx.nonce)).isEqualTo(dev.pampa.mycloudgallery.core.model.AlbumId.derived(AccountId("test"), fx.nonce))
  }

}
