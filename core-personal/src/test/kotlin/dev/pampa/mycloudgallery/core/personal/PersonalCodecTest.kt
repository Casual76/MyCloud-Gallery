package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.codec.PersonalRecords
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.data.Segments
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il corpo di una voce e il segmento di classe `personal` (formato §5.5-bis): andata e ritorno, forma canonica unica e lettura
 * ostile. Un dato ostile non fa mai crashare e non fa mai passare niente: scarta il segmento intero con il suo motivo (E-I14).
 */
class PersonalCodecTest {

  /** Caratteri vietati nei testi (override bidirezionale), scritti a codice: nei sorgenti non devono comparire. */
  private val BIDI = 0x202E.toChar()

  private val author = Base32.encode(ByteArray(16) { 1 })
  private val at = NAS_NOW
  private val favorite = flagKey(mediaId(1), PersonalFields.FAVORITE)

  private fun key32(key: PersonalKey) = key.digest()

  private fun rejected(verdict: Verdict<*>): RejectReason = (verdict as Verdict.Rejected).reason

  private fun body(entry: PersonalEntry) = PersonalRecords.encode(entry)

  // --- Il corpo di una voce ------------------------------------------------------------------------------------------------------

  @Test
  fun ilCorpoEIlJsonCanonicoDelFormato() {
    val e = PersonalEntry(favorite, at, author, PersonalValue.Flag(true))
    assertThat(String(body(e), Charsets.UTF_8)).isEqualTo(
      """{"at":$at,"author":"$author","field":"favorite","media":"${mediaId(1)}","value":true}""",
    )
    // La lapide non porta "value": un campo facoltativo si omette, mai null.
    val gone = PersonalEntry(PersonalKey(PersonalKind.ALBUM, "album", PersonalFields.albumItem(mediaId(2))), at, author, PersonalValue.Removed)
    assertThat(String(body(gone), Charsets.UTF_8)).doesNotContain("value")
    assertThat(String(body(gone), Charsets.UTF_8)).doesNotContain("null")
  }

  @Test
  fun ognunDeiValoriEDeiTipiTornaIdentico() {
    val fp = PersonalFingerprint(123_456_789L, QuickHash("ab".repeat(32)))
    val entries = listOf(
      PersonalEntry(favorite, at, author, PersonalValue.Flag(true), fp),
      PersonalEntry(flagKey(mediaId(2), PersonalFields.HIDDEN), at - 1, author, PersonalValue.Flag(false)),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, "album-1", PersonalFields.ALBUM_DEFINITION), at, author, PersonalValue.Text("Oslo 2026 è 😀")),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, "album-1", PersonalFields.ALBUM_ORDER), at, author, PersonalValue.Number(-7)),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, "album-1", PersonalFields.albumItem(mediaId(3))), at, author, PersonalValue.Removed),
      PersonalEntry(PersonalKey(PersonalKind.MEMORY, "memory-2026-10", PersonalFields.MEMORY_SEEN), at, author, PersonalValue.Number(at)),
      PersonalEntry(
        PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_SHOW_ALBUM_COVERS),
        at,
        author,
        PersonalValue.Flag(true),
      ),
      PersonalEntry(PersonalKey(PersonalKind.PERSON, "p1", "name"), at, author, PersonalValue.Text("Mario")),
    )
    for (e in entries) {
      val back = PersonalRecords.decode(body(e), key32(e.key))
      assertThat((back as Verdict.Accepted).value).isEqualTo(e)
      assertThat(PersonalRecords.encodeOrNull(e)).isEqualTo(body(e))
    }
  }

  @Test
  fun ilTipoSiRicavaSoloDallaChiaveDellaTabella() {
    val asFlag = PersonalEntry(PersonalKey(PersonalKind.MEDIA_FLAG, "x", "favorite"), at, author, PersonalValue.Flag(true))
    val asAlbum = PersonalEntry(PersonalKey(PersonalKind.ALBUM, "x", "favorite"), at, author, PersonalValue.Flag(true))
    // Stessi soggetto e campo, tipi diversi: stesso corpo, chiavi diverse, voci diverse.
    assertThat(body(asFlag)).isEqualTo(body(asAlbum))
    assertThat((PersonalRecords.decode(body(asFlag), key32(asFlag.key)) as Verdict.Accepted).value.key.kind).isEqualTo(PersonalKind.MEDIA_FLAG)
    assertThat((PersonalRecords.decode(body(asAlbum), key32(asAlbum.key)) as Verdict.Accepted).value.key.kind).isEqualTo(PersonalKind.ALBUM)
    // Una chiave che non e' l'impronta di nessun tipo per quel contenuto: rifiutata.
    assertThat(rejected(PersonalRecords.decode(body(asFlag), key32(favorite)))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejected(PersonalRecords.decode(body(asFlag), ByteArray(32)))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejected(PersonalRecords.decode(body(asFlag), ByteArray(31)))).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun corpiOstiliSonoRifiutatiConIlLoroMotivoSenzaCrash() {
    val good = String(body(PersonalEntry(favorite, at, author, PersonalValue.Flag(true))), Charsets.UTF_8)
    val k = key32(favorite)
    fun decode(json: String) = rejected(PersonalRecords.decode(json.toByteArray(Charsets.UTF_8), k))

    // Spazi e chiavi fuori ordine: non e' la forma canonica, quindi un'altra scrittura dello stesso contenuto (due programmi non devono leggerlo in due modi).
    assertThat(decode(good.replace("\"at\":", "\"at\": "))).isIn(listOf(RejectReason.MALFORMED, RejectReason.NOT_CANONICAL))
    val reordered = """{"author":"$author","at":$at,"field":"favorite","media":"${mediaId(1)}","value":true}"""
    assertThat(decode(reordered)).isEqualTo(RejectReason.NOT_CANONICAL)
    assertThat(decode(good.replace("}", ",\"zzz\":1}"))).isEqualTo(RejectReason.MALFORMED)
    assertThat(decode(good.replace("\"value\":true", "\"value\":null"))).isEqualTo(RejectReason.MALFORMED)
    assertThat(decode(good.replace("\"value\":true", "\"value\":[1]"))).isEqualTo(RejectReason.MALFORMED)
    assertThat(decode(good.replace("\"value\":true", "\"value\":{}"))).isEqualTo(RejectReason.MALFORMED)
    assertThat(decode(good.replace("$at", "10"))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(decode(good.replace("$at", "99999999999999"))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(decode(good.replace(author, "not-a-device-id"))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(decode(good.replace("\"author\":\"$author\",", ""))).isEqualTo(RejectReason.MALFORMED)
    assertThat(decode(good.replace("\"field\":\"favorite\"", "\"field\":\"\""))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(decode(good.replace("\"field\":\"favorite\"", "\"field\":\"fav${BIDI}orite\""))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(decode(good.replace("\"value\":true", "\"value\":\"a${BIDI}b\""))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(decode(good.replace("\"value\":true", "\"value\":\"${"x".repeat(PersonalValue.MAX_TEXT + 1)}\""))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(decode(good.replace("\"media\":\"${mediaId(1)}\"", "\"media\":\"${"m".repeat(129)}\""))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(decode("")).isEqualTo(RejectReason.MALFORMED)
    assertThat(decode("[]")).isEqualTo(RejectReason.MALFORMED)
    assertThat(decode("{\"x\":1}")).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejected(PersonalRecords.decode(ByteArray(TrustLimits.MAX_META_RECORD_BYTES + 1) { '{'.code.toByte() }, k))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(rejected(PersonalRecords.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), k))).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun leImprontaFalseSonoRifiutate() {
    val k = key32(favorite)
    val base = """{"at":$at,"author":"$author","field":"favorite","fp":FP,"media":"${mediaId(1)}","value":true}"""
    fun decode(fp: String) = PersonalRecords.decode(base.replace("FP", fp).toByteArray(), k)
    val qh = Base32.encode(ByteArray(32) { 5 })
    assertThat(decode("""{"qh":"$qh","size":10}""")).isInstanceOf(Verdict.Accepted::class.java)
    assertThat(rejected(decode("""{"qh":"abc","size":10}"""))).isNotNull()
    assertThat(rejected(decode("""{"qh":"$qh","size":-1}"""))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(rejected(decode("""{"qh":"$qh"}"""))).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejected(decode("""{"qh":"$qh","size":1,"x":1}"""))).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejected(decode("true"))).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun chiPubblicaScartaCioCheGliAltriRifiuterebbero() {
    // Tutto cio' che un lettore rifiuterebbe non si scrive: un'ora fuori dal periodo, un testo con caratteri vietati, un testo lungo.
    assertThat(PersonalRecords.encodeOrNull(PersonalEntry(favorite, 5, author, PersonalValue.Flag(true)))).isNull()
    assertThat(PersonalRecords.encodeOrNull(PersonalEntry(favorite, at, author, PersonalValue.Text("a${BIDI}b")))).isNull()
    assertThat(PersonalRecords.encodeOrNull(PersonalEntry(favorite, at, author, PersonalValue.Text("ok")))).isNotNull()
    assertThat(PersonalRecords.encodeOrNull(PersonalEntry(PersonalKey(PersonalKind.ALBUM, "a\u0007b", "f"), at, author, PersonalValue.Removed))).isNull()
    // Un testo di sola punteggiatura di controllo (6 byte l'uno in JSON) supera i 4 KiB: la scrittura rifiuta, non tronca.
    val bulky = PersonalEntry(favorite, at, author, PersonalValue.Text(0x01.toChar().toString().repeat(PersonalValue.MAX_TEXT)))
    assertThrows(IllegalArgumentException::class.java) { PersonalRecords.encode(bulky) }
    assertThat(PersonalRecords.encodeOrNull(bulky)).isNull()
  }

  // --- Il segmento ---------------------------------------------------------------------------------------------------------------

  private fun entries(n: Int, seed: Int = 0): List<PersonalEntry> {
    val random = Random(seed)
    return List(n) { i ->
      PersonalEntry(
        flagKey(mediaId(i + seed * 100_000), if (random.nextBoolean()) PersonalFields.FAVORITE else PersonalFields.HIDDEN),
        at + random.nextLong(0, 1_000),
        author,
        PersonalValue.Flag(random.nextBoolean()),
      )
    }
  }

  @Test
  fun unSegmentoSiRilegge_eQualunqueOrdineDiIngressoDaLoStessoSegmento() {
    val list = entries(200)
    val encoded = PersonalSegments.encode(list)
    val back = (PersonalSegments.decode(encoded.bytes, encoded.ref) as Verdict.Accepted).value
    assertThat(back).containsExactlyElementsIn(list)
    // Il nome e' l'impronta: stesso insieme, stessi byte, stesso nome. E' cio' che rende "esiste gia'" un successo.
    val again = PersonalSegments.encode(list.shuffled(Random(1)))
    assertThat(again.bytes).isEqualTo(encoded.bytes)
    assertThat(again.ref).isEqualTo(encoded.ref)
    // E' lo stesso contenitore degli altri segmenti: MCGB, versione 1, classe 16.
    assertThat(String(encoded.bytes.copyOf(4), Charsets.US_ASCII)).isEqualTo("MCGB")
    assertThat(encoded.bytes[4].toInt()).isEqualTo(1)
    assertThat(encoded.bytes[5].toInt()).isEqualTo(16)
  }

  /** Costruisce un segmento a mano (anche sbagliato): intestazione, tabella e corpi dati dal test. */
  private fun craft(
    cls: Int = 16,
    reserved: Int = 0,
    declaredCount: Int? = null,
    rows: List<Pair<ByteArray, Int>>,
    bodies: List<ByteArray>,
  ): Pair<ByteArray, SegmentRef> {
    val size = 8 + rows.size * 36 + bodies.sumOf { it.size }
    val out = ByteBuffer.allocate(size)
    out.put(cls.toByte()).put(reserved.toByte()).put(0).put(0).putInt(declaredCount ?: rows.size)
    rows.forEach { (k, len) -> out.put(k).putInt(len) }
    bodies.forEach { out.put(it) }
    return Segments.wrap(out.array())
  }

  private fun segmentOf(list: List<PersonalEntry>): Triple<List<ByteArray>, List<ByteArray>, List<PersonalEntry>> {
    val sorted = list.sortedWith { a, b -> IndexKeys.compare(a.key.digest(), b.key.digest()) }
    return Triple(sorted.map { it.key.digest() }, sorted.map { PersonalRecords.encode(it) }, sorted)
  }

  @Test
  fun segmentiOstiliSonoScartatiInteriSenzaCrash() {
    val (keys, bodies, _) = segmentOf(entries(3))
    val rows = keys.indices.map { keys[it] to bodies[it].size }

    // Il segmento fatto bene passa: la prova che gli scarti sotto sono per il difetto, non per il banco.
    val (goodBytes, goodRef) = craft(rows = rows, bodies = bodies)
    assertThat(PersonalSegments.decode(goodBytes, goodRef)).isInstanceOf(Verdict.Accepted::class.java)

    fun reasonOf(pair: Pair<ByteArray, SegmentRef>) = rejected(PersonalSegments.decode(pair.first, pair.second))

    // Classe diversa da quella della testa (un segmento `meta` elencato come `personal`).
    assertThat(reasonOf(craft(cls = 1, rows = rows, bodies = bodies))).isEqualTo(RejectReason.WRONG_TYPE)
    // Byte riservati.
    assertThat(reasonOf(craft(reserved = 1, rows = rows, bodies = bodies))).isEqualTo(RejectReason.MALFORMED)
    // N a zero, N sopra il tetto, N piu' grande della tabella che c'e' davvero (nessuna allocazione da N dichiarato).
    assertThat(reasonOf(craft(declaredCount = 0, rows = rows, bodies = bodies))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reasonOf(craft(declaredCount = TrustLimits.MAX_SEGMENT_ENTRIES + 1, rows = rows, bodies = bodies))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reasonOf(craft(declaredCount = Int.MAX_VALUE, rows = rows, bodies = bodies))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reasonOf(craft(declaredCount = 4, rows = rows, bodies = bodies))).isIn(listOf(RejectReason.MALFORMED, RejectReason.TOO_LARGE))
    // Chiavi non crescenti e chiavi ripetute.
    assertThat(reasonOf(craft(rows = rows.reversed(), bodies = bodies.reversed()))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reasonOf(craft(rows = listOf(rows[0], rows[0]), bodies = listOf(bodies[0], bodies[0])))).isEqualTo(RejectReason.MALFORMED)
    // Una lunghezza che non torna con i byte, in piu' e in meno; un corpo vuoto o oltre il tetto.
    assertThat(reasonOf(craft(rows = rows.mapIndexed { i, r -> if (i == 0) r.first to r.second + 1 else r }, bodies = bodies))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reasonOf(craft(rows = rows, bodies = bodies + ByteArray(1)))).isEqualTo(RejectReason.MALFORMED)
    assertThat(reasonOf(craft(rows = rows.mapIndexed { i, r -> if (i == 0) r.first to 0 else r }, bodies = bodies.mapIndexed { i, b -> if (i == 0) ByteArray(0) else b }))).isEqualTo(RejectReason.MALFORMED)
    val huge = ByteArray(TrustLimits.MAX_META_RECORD_BYTES + 1)
    assertThat(reasonOf(craft(rows = listOf(rows[0].first to huge.size), bodies = listOf(huge)))).isEqualTo(RejectReason.TOO_LARGE)
    // Un corpo che non e' JSON canonico, e una chiave che non e' quella del suo contenuto.
    assertThat(reasonOf(craft(rows = listOf(rows[0].first to 3), bodies = listOf("{ }".toByteArray())))).isIn(listOf(RejectReason.MALFORMED, RejectReason.NOT_CANONICAL))
    val swapped = rows.mapIndexed { i, r -> if (i == 0) keys[1] to r.second else r }
    assertThat(reasonOf(craft(rows = swapped.sortedWith { a, b -> IndexKeys.compare(a.first, b.first) }, bodies = bodies))).isNotNull()
    // Troncato in qualunque punto: mai un'eccezione.
    for (cut in listOf(0, 1, 4, 5, 6, 10, 12, 13, 20, goodBytes.size - 1)) {
      val truncated = goodBytes.copyOf(cut)
      val ref = SegmentRef(Hashes.sha256Name(truncated), maxOf(cut.toLong(), 1))
      assertThat(PersonalSegments.decode(truncated, ref)).isInstanceOf(Verdict.Rejected::class.java)
    }
    // Impronta o dimensione della testa diverse dal file: scartato prima di leggere una riga.
    assertThat(rejected(PersonalSegments.decode(goodBytes, SegmentRef(goodRef.hash, goodRef.size + 1)))).isEqualTo(RejectReason.HASH_MISMATCH)
    val flipped = goodBytes.copyOf().also { it[goodBytes.size - 1] = (it[goodBytes.size - 1] + 1).toByte() }
    assertThat(rejected(PersonalSegments.decode(flipped, goodRef))).isEqualTo(RejectReason.HASH_MISMATCH)
    // Formato di una versione piu' nuova: non si interpreta ("aggiorna l'app", E-I3).
    val newer = goodBytes.copyOf().also { it[4] = 2 }
    assertThat(rejected(PersonalSegments.decode(newer, SegmentRef(Hashes.sha256Name(newer), newer.size.toLong()))))
      .isEqualTo(RejectReason.NEWER_FORMAT)
  }

  @Test
  fun unSegmentoDiPiuVociChiudeEdecodificaFinoAlTetto() {
    val list = entries(TrustLimits.MAX_SEGMENT_ENTRIES)
    val encoded = PersonalSegments.encode(list)
    assertThat((PersonalSegments.decode(encoded.bytes, encoded.ref) as Verdict.Accepted).value).hasSize(TrustLimits.MAX_SEGMENT_ENTRIES)
    assertThrows(IllegalArgumentException::class.java) { PersonalSegments.encode(entries(TrustLimits.MAX_SEGMENT_ENTRIES + 1)) }
    assertThrows(IllegalArgumentException::class.java) { PersonalSegments.encode(emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { PersonalSegments.encode(listOf(entries(1)[0], entries(1)[0])) }
  }

  // --- Il piano di pubblicazione -------------------------------------------------------------------------------------------------

  @Test
  fun loStatoSiDivideInSegmentiStabili_unaModificaNeRiscriveUnoSolo() {
    val state = entries(5_000)
    val plan = PersonalSegments.plan(state)
    assertThat(plan.entries).isEqualTo(5_000)
    assertThat(plan.skipped).isEqualTo(0)
    assertThat(plan.segments.size).isGreaterThan(1)
    assertThat(plan.segments.all { it.entries <= TrustLimits.MAX_SEGMENT_ENTRIES / 2 }).isTrue()
    val decoded = plan.segments.flatMap { (PersonalSegments.decode(it.bytes, it.ref) as Verdict.Accepted).value }
    assertThat(decoded).containsExactlyElementsIn(state)

    // Stesso stato in un altro ordine: stessi segmenti, stessi nomi.
    assertThat(PersonalSegments.plan(state.shuffled(Random(9))).names).isEqualTo(plan.names)

    // Una voce cambia: un solo segmento nuovo, gli altri hanno lo stesso nome (il NAS risponde "esiste gia'").
    val changed = state.toMutableList().also { it[17] = PersonalEntry(it[17].key, it[17].atNas + 1, author, PersonalValue.Flag(!(it[17].value as PersonalValue.Flag).on)) }
    val changedPlan = PersonalSegments.plan(changed)
    assertThat(changedPlan.names - plan.names).hasSize(1)
    assertThat(plan.names - changedPlan.names).hasSize(1)
  }

  @Test
  fun lePocheVociStannoInUnSegmentoSoloEUnoStatoVuotoNonProduceNulla() {
    assertThat(PersonalSegments.plan(entries(10)).segments).hasSize(1)
    val empty = PersonalSegments.plan(emptyList())
    assertThat(empty.segments).isEmpty()
    assertThat(empty.entries).isEqualTo(0)
  }

  @Test
  fun leVociNonScrivibiliSiContanoENonEntranoNelSegmento() {
    val good = entries(3)
    val old = PersonalEntry(flagKey(mediaId(99), PersonalFields.HIDDEN), 5, author, PersonalValue.Flag(true))
    val bidi = PersonalEntry(PersonalKey(PersonalKind.ALBUM, "a", PersonalFields.ALBUM_DEFINITION), at, author, PersonalValue.Text("x${BIDI}y"))
    val plan = PersonalSegments.plan(good + old + bidi)
    assertThat(plan.entries).isEqualTo(3)
    assertThat(plan.skipped).isEqualTo(2)
  }

  @Test
  fun centomilaVociSiDividonoESiRileggonoInTempiRagionevoli() {
    val state = entries(100_000)
    val started = System.nanoTime()
    val plan = PersonalSegments.plan(state)
    val planMs = (System.nanoTime() - started) / 1_000_000
    val readStarted = System.nanoTime()
    var count = 0
    for (segment in plan.segments) count += (PersonalSegments.decode(segment.bytes, segment.ref) as Verdict.Accepted).value.size
    val readMs = (System.nanoTime() - readStarted) / 1_000_000
    assertThat(count).isEqualTo(100_000)
    assertThat(plan.segments.size).isLessThan(TrustLimits.MAX_HEAD_SEGMENTS)
    println("P13 100.000 voci: piano $planMs ms in ${plan.segments.size} segmenti, rilettura $readMs ms")
    assertThat(planMs).isLessThan(15_000)
    assertThat(readMs).isLessThan(15_000)
  }
}
