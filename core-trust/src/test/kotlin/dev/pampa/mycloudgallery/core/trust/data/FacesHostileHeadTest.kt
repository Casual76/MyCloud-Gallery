package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.JsonValue
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.data.AlbumFx.Companion.with
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import java.nio.ByteBuffer
import org.junit.Test

/**
 * La testa e il contenitore dei dati dei volti contro dati ostili (Fase 8, P3, "Fatto quando": test ostili per ogni campo del §2.4, versioni
 * 1-3 e 5, campi sconosciuti, generazione in disaccordo con il posto, account diverso). Ogni testa e' firmata davvero dal telefono (la busta
 * e' valida): il rifiuto viene dal contenuto, con il suo motivo.
 */
class FacesHostileHeadTest {

  private val fx = FacesFx()
  private val people = FacesDocs.peopleSegment(fx.everyPeople())
  private val cluster = FacesDocs.clusterSegment(fx.snapshotEntries())

  /** Il JSON della testa onesta con un segmento `people` e uno `cluster`. */
  private fun payload(): JsonObject {
    val segments = listOf(people.classed, cluster.classed).sortedBy { it.ref.hash }
    val bytes = fx.head(segments, snapshot = SnapshotMark(3, 2)).bytes
    val n = ByteBuffer.wrap(bytes, 97, 4).int
    return AlbumFx.json(bytes.copyOfRange(101, 101 + n))
  }

  private fun reason(obj: JsonObject, version: Int = 4, type: DocType = DocType.FACES_HEAD, at: Long = 1): RejectReason? =
    fx.accept(AlbumFx.rawSeal(type, obj, fx.phone, version), version = at).rejectionOrNull()

  private fun field(key: String, value: JsonValue?) = reason(payload().with(key, value))

  private fun segment(cls: String, hash: String, size: Long): JsonObject = jsonObject("class" to cls.json(), "hash" to hash.json(), "size" to size.json())

  @Test
  fun laTestaOnestaPassa() {
    assertThat(reason(payload())).isNull()
  }

  /** E-I3, A8: la 4 solo per il tipo 9; la 1, la 2 e la 3 sono un errore del dato, la 5 "aggiorna l'app". */
  @Test
  fun versioni1_3E5() {
    for (version in 1..3) assertWithMessage("versione $version").that(reason(payload(), version = version)).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(payload(), version = 5)).isEqualTo(RejectReason.NEWER_FORMAT)
    // Una testa d'altro tipo nel posto di una testa dei volti, e il contrario.
    assertThat(reason(payload(), version = 1, type = DocType.DEVICE_HEAD)).isEqualTo(RejectReason.WRONG_TYPE)
  }

  @Test
  fun campiSconosciutiOMancanti() {
    assertThat(field("nickname", "x".json())).isEqualTo(RejectReason.MALFORMED)
    for (key in listOf("account", "createdAt", "device", "family", "generation", "registryVersion", "segments", "version")) {
      assertWithMessage("senza $key").that(field(key, null)).isEqualTo(RejectReason.MALFORMED)
    }
    assertThat(field("version", "1".json())).isEqualTo(RejectReason.MALFORMED)
  }

  /** D11: famiglia, dispositivo e account devono essere quelli del firmatario. */
  @Test
  fun famigliaDispositivoEAccount() {
    assertThat(field("family", dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.random().json())).isEqualTo(RejectReason.WRONG_FAMILY)
    assertThat(field("device", fx.tablet.deviceId.json())).isEqualTo(RejectReason.WRONG_LOCATION)
    assertThat(field("account", "test2".json())).isEqualTo(RejectReason.NOT_OWNER)
    // Una sola scrittura dell'account: la minuscola.
    assertThat(field("account", "TEST".json())).isEqualTo(RejectReason.NOT_OWNER)
  }

  /** "Generazione in disaccordo con il posto": la testa dice una generazione, la cartella un'altra. */
  @Test
  fun generazioneEPosto() {
    val elsewhere = FaceGeneration.fromBytes(ByteArray(16) { 9 })
    assertThat(field("generation", elsewhere.base32.json())).isEqualTo(RejectReason.WRONG_LOCATION)
    assertThat(field("generation", elsewhere.base32.uppercase().json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("generation", "0".repeat(25).json())).isEqualTo(RejectReason.INVALID_FIELD)
    // La stessa testa letta nella cartella di un'altra generazione, o con un altro numero.
    val bytes = AlbumFx.rawSeal(DocType.FACES_HEAD, payload(), fx.phone, 4)
    assertThat(fx.accept(bytes, location = elsewhere).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    assertThat(fx.accept(bytes, version = 2).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    assertThat(fx.accept(bytes, signer = fx.tablet).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
  }

  @Test
  fun numeriEOre() {
    assertThat(field("version", 0L.json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("registryVersion", 0L.json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("createdAt", 1_000L.json())).isEqualTo(RejectReason.INVALID_FIELD)
    // D12: l'ora della testa non oltre 2 minuti nel futuro rispetto all'ora del NAS di chi legge.
    assertThat(field("createdAt", (fx.now + TrustLimits.MAX_FUTURE_SKEW_MILLIS + 1).json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("createdAt", (fx.now + TrustLimits.MAX_FUTURE_SKEW_MILLIS).json())).isNull()
  }

  @Test
  fun segmentiElencati() {
    val list = (payload().members.getValue("segments") as dev.pampa.mycloudgallery.core.trust.codec.JsonArray).items
    assertThat(field("segments", listOf(segment("meta", people.ref.hash, people.ref.size)).json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("segments", listOf(segment("vec", people.ref.hash, people.ref.size)).json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("segments", listOf(segment("nuova", people.ref.hash, people.ref.size)).json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("segments", list.reversed().json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("segments", (list + list.first()).json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("segments", listOf(segment("people", "nonunimpronta", 10)).json())).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("segments", listOf(segment("people", people.ref.hash, 0)).json())).isEqualTo(RejectReason.INVALID_FIELD)
    // Un segmento oltre 12 MiB, o una testa che dichiara piu' di 512 MiB in tutto: rifiutata prima di scaricare.
    assertThat(field("segments", listOf(segment("people", people.ref.hash, TrustLimits.MAX_FACES_SEGMENT_BYTES + 1L)).json()))
      .isEqualTo(RejectReason.TOO_LARGE)
    val huge = (0 until 50).map { Hashes.sha256Name("s$it".toByteArray()) }.sorted().map { segment("faces", it, TrustLimits.MAX_FACES_SEGMENT_BYTES.toLong()) }
    assertThat(reason(payload().with("segments", huge.json()).with("snapshot", null))).isEqualTo(RejectReason.TOO_LARGE)
    val many = (0..TrustLimits.MAX_HEAD_SEGMENTS).map { Hashes.sha256Name("m$it".toByteArray()) }.sorted().map { segment("faces", it, 10) }
    assertThat(reason(payload().with("segments", many.json()).with("snapshot", null))).isEqualTo(RejectReason.TOO_LARGE)
  }

  /** L'istantanea se e solo se la testa elenca segmenti `cluster`; numeri coerenti. */
  @Test
  fun istantanea() {
    assertThat(field("snapshot", null)).isEqualTo(RejectReason.INVALID_FIELD)
    val onlyPeople = listOf(segment("people", people.ref.hash, people.ref.size)).json()
    assertThat(reason(payload().with("segments", onlyPeople))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(reason(payload().with("segments", onlyPeople).with("snapshot", null))).isNull()
    assertThat(field("snapshot", jsonObject("base" to 0L.json(), "seq" to 0L.json()))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("snapshot", jsonObject("base" to 3L.json(), "seq" to 3L.json()))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(field("snapshot", jsonObject("base" to 1L.json(), "seq" to 3L.json(), "x" to 1L.json()))).isEqualTo(RejectReason.MALFORMED)
    assertThat(field("snapshot", "3".json())).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun bustaRovinata() {
    val bytes = fx.head().bytes
    val flipped = bytes.copyOf().also { it[110] = (it[110] + 1).toByte() }
    assertThat(fx.accept(flipped).rejectionOrNull()).isEqualTo(RejectReason.BAD_SIGNATURE)
    assertThat(fx.accept(bytes.copyOf(bytes.size - 3)).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    assertThat(fx.accept(ByteArray(DocType.FACES_HEAD.maxFileBytes + 1)).rejectionOrNull()).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(fx.accept(ByteArray(0)).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
  }

  // --- Il contenitore dei segmenti ---------------------------------------------------------------------------------------

  private val body = FacesDocs.encodeBody(fx.body())

  private fun container(version: Int = 4, cls: Int = 18, count: Int? = null, reserved: Int = 0, rows: List<Pair<ByteArray, ByteArray>> = listOf(fx.key(1).bytes() to body)) =
    FacesFx.rawSegment(rows, version, cls, count ?: rows.size, reserved).let { (bytes, ref) -> FacesDocs.decodeSegment(bytes, ref, SegmentClass.FACES).rejectionOrNull() }

  @Test
  fun contenitoreOstile() {
    assertThat(container()).isNull()
    for (version in 1..3) assertWithMessage("versione $version").that(container(version = version)).isEqualTo(RejectReason.MALFORMED)
    assertThat(container(version = 5)).isEqualTo(RejectReason.NEWER_FORMAT)
    assertThat(container(cls = 19)).isEqualTo(RejectReason.WRONG_TYPE)
    assertThat(container(cls = 4)).isEqualTo(RejectReason.WRONG_TYPE)
    assertThat(container(reserved = 1)).isEqualTo(RejectReason.MALFORMED)
    assertThat(container(count = 0)).isEqualTo(RejectReason.MALFORMED)
    assertThat(container(count = TrustLimits.MAX_FACES_SEGMENT_ENTRIES + 1)).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(container(count = 1_000)).isEqualTo(RejectReason.MALFORMED)
    val two = listOf(fx.key(2).bytes() to body, fx.key(1).bytes() to body).sortedByDescending { Hex.of(it.first) }
    assertThat(container(rows = two)).isEqualTo(RejectReason.MALFORMED)
    assertThat(container(rows = listOf(fx.key(1).bytes() to body, fx.key(1).bytes() to body))).isEqualTo(RejectReason.MALFORMED)
    assertThat(container(rows = listOf(fx.key(1).bytes() to ByteArray(TrustLimits.MAX_FACES_BODY_BYTES + 1)))).isEqualTo(RejectReason.TOO_LARGE)
    // Impronta, dimensione e tetto della testa.
    val (bytes, ref) = FacesFx.rawSegment(listOf(fx.key(1).bytes() to body))
    assertThat(FacesDocs.decodeSegment(bytes.copyOf().also { it[20] = 7 }, ref, SegmentClass.FACES).rejectionOrNull()).isEqualTo(RejectReason.HASH_MISMATCH)
    assertThat(FacesDocs.decodeSegment(bytes + 0, ref, SegmentClass.FACES).rejectionOrNull()).isEqualTo(RejectReason.HASH_MISMATCH)
    val big = SegmentRef(ref.hash, TrustLimits.MAX_FACES_SEGMENT_BYTES + 1L)
    assertThat(FacesDocs.decodeSegment(bytes, big, SegmentClass.FACES).rejectionOrNull()).isEqualTo(RejectReason.TOO_LARGE)
    // Una classe d'altro tipo non si legge con il codec dei volti.
    assertThat(FacesDocs.decodeSegment(bytes, ref, SegmentClass.META).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
  }
}
