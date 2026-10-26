package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Test

/**
 * Le teste d'album (Fase 7, P2; formato §5.8, §10; D6, D8, A6, A7): firma, firmatario, versione 3 legata al tipo, posto, campi, tetti,
 * anti-rollback ed equivocazione. Ogni rifiuto ha il suo motivo; nessun dato ostile fa cadere il codec.
 */
class AlbumHeadTest {

  private val fx = AlbumFx()
  private val segment = AlbumDocs.encodeSegment(fx.album, fx.everyKind())

  private fun payload(
    signer: Signer = fx.phone,
    version: Long = 1,
    family: String = fx.family.familyId,
    album: String = AlbumIds.text(fx.album),
    segments: List<JsonObject> = listOf(jsonObject("class" to "album".json(), "hash" to segment.ref.hash.json(), "size" to segment.ref.size.json())),
    extra: Pair<String, Long>? = null,
  ): JsonObject {
    val base = jsonObject(
      "album" to album.json(),
      "createdAt" to fx.now.json(),
      "device" to signer.deviceId.json(),
      "family" to family.json(),
      "registryVersion" to fx.family.state.registry.version.json(),
      "segments" to segments.json(),
      "version" to version.json(),
    )
    return if (extra == null) base else base.with(extra.first, extra.second.json())
  }

  private fun raw(payload: JsonObject = payload(), signer: Signer = fx.phone, version: Int = TrustFormat.ALBUM_VERSION) =
    AlbumFx.rawSeal(DocType.SHARED_ALBUM_HEAD, payload, signer, version)

  @Test
  fun unaTestaValidaSiAccettaEIlSegmentoSiRilegge() {
    val bytes = fx.head(listOf(segment.ref))
    assertThat(bytes[4].toInt()).isEqualTo(TrustFormat.ALBUM_VERSION)
    assertThat(bytes[5].toInt()).isEqualTo(DocType.SHARED_ALBUM_HEAD.code)
    val accepted = fx.accept(bytes).acceptedOrNull()!!
    assertThat(accepted.head.segments).containsExactly(segment.ref)
    assertThat(accepted.account).isEqualTo(AccountId("test"))
    assertThat(accepted.registryBehind).isFalse()
    assertThat(segment.bytes[4].toInt()).isEqualTo(3)
    assertThat(segment.bytes[5].toInt()).isEqualTo(SegmentClass.ALBUM.code)
    val read = AlbumDocs.decodeSegment(segment.bytes, segment.ref, fx.context()).acceptedOrNull()!!
    assertThat(read).containsExactlyElementsIn(fx.everyKind())
    assertThat(AlbumDocs.checkContribution(fx.album, read).acceptedOrNull()).isNotNull()
    // Una testa vuota (nessun segmento) e' valida: un contributo senza voci.
    assertThat(fx.accept(fx.head(emptyList())).acceptedOrNull()).isNotNull()
    // Il posto: albums/<album in base32>/<dispositivo>/head/<20 cifre> e seg/<impronta>.
    val text = AlbumIds.text(fx.album)
    assertThat(NasLayout.albumHead(text, fx.phone.deviceId, 7).value).isEqualTo(".mycloudgallery/albums/$text/${fx.phone.deviceId}/head/00000000000000000007")
    assertThat(NasLayout.albumSegment(text, fx.phone.deviceId, segment.ref.hash).value).endsWith("/seg/${segment.ref.hash}")
  }

  @Test
  fun approvatoSiDismessoSoloConLaTestaCongelataRevocatoEPersoMai() {
    val bytes = fx.head(listOf(segment.ref))
    val frozenHash = fx.accept(bytes).acceptedOrNull()!!.mark.contentHash
    val unknown = newSigner()
    assertThat(fx.accept(fx.head(listOf(segment.ref), signer = unknown), signer = unknown).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)
    // Dismesso con questa testa congelata: vale. Una testa nuova dello stesso dispositivo no (A7).
    fx.family.changeStatus(fx.phone, DeviceStatus.RETIRED, listOf(frozenHash))
    assertThat(fx.accept(bytes).acceptedOrNull()).isNotNull()
    assertThat(fx.accept(fx.head(listOf(segment.ref), version = 2), version = 2).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_RETIRED)
    // Revocato e perso: niente, nemmeno la testa che era congelata.
    fx.family.changeStatus(fx.phone, DeviceStatus.REVOKED)
    assertThat(fx.accept(bytes).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_REVOKED)
    fx.family.changeStatus(fx.tablet, DeviceStatus.LOST)
    assertThat(fx.accept(fx.head(emptyList(), signer = fx.tablet), signer = fx.tablet).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_REVOKED)
  }

  /** A6: la testa d'album e' della versione 3; la 1 e la 2 sono rifiutate, la 4 chiede l'aggiornamento; nessun altro tipo accetta la 3. */
  @Test
  fun versioni1E2Rifiutate_la4PiuNuova_la3SoloPerGliAlbum() {
    assertThat(fx.accept(raw(version = 1)).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(fx.accept(raw(version = 2)).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(fx.accept(raw(version = 4)).rejectionOrNull()).isEqualTo(RejectReason.NEWER_FORMAT)
    assertThat(fx.accept(raw()).acceptedOrNull()).isNotNull()
    // Una testa `index` alla versione 3: piu' nuova di quello che una testa di dispositivo puo' essere.
    val index = AlbumFx.rawSeal(
      DocType.DEVICE_HEAD,
      jsonObject(
        "createdAt" to fx.now.json(), "device" to fx.phone.deviceId.json(), "family" to fx.family.familyId.json(), "kind" to "index".json(),
        "registryVersion" to fx.family.state.registry.version.json(), "segments" to emptyList<JsonObject>().json(), "share" to "Public".json(), "version" to 1L.json(),
      ),
      fx.phone,
      TrustFormat.ALBUM_VERSION,
    )
    val location = HeadLocation("Public", fx.phone.deviceId, HeadKind.INDEX, 1)
    assertThat(HeadVerifier.accept(index, fx.family.state, location, null, "test").rejectionOrNull()).isEqualTo(RejectReason.NEWER_FORMAT)
  }

  /** M1 della Fase 4 esteso: la classe `album` in una testa di dispositivo rifiuta la testa intera; una testa d'album non elenca altre classi. */
  @Test
  fun laClasseAlbumSoloNelleTesteDAlbum() {
    val index = AlbumFx.rawSeal(
      DocType.DEVICE_HEAD,
      jsonObject(
        "createdAt" to fx.now.json(), "device" to fx.phone.deviceId.json(), "family" to fx.family.familyId.json(), "kind" to "index".json(),
        "registryVersion" to fx.family.state.registry.version.json(), "share" to "Public".json(), "version" to 1L.json(),
        "segments" to listOf(jsonObject("class" to "album".json(), "hash" to segment.ref.hash.json(), "size" to segment.ref.size.json())).json(),
      ),
      fx.phone,
      TrustFormat.CURRENT_VERSION,
    )
    assertThat(HeadVerifier.accept(index, fx.family.state, HeadLocation("Public", fx.phone.deviceId, HeadKind.INDEX, 1), null, "test").rejectionOrNull())
      .isEqualTo(RejectReason.INVALID_FIELD)
    val metaClass = payload(segments = listOf(jsonObject("class" to "meta".json(), "hash" to segment.ref.hash.json(), "size" to segment.ref.size.json())))
    assertThat(fx.accept(raw(metaClass)).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    // Il codec dell'indice e quello dei dati AI non leggono un segmento d'album.
    assertThat(IndexSegments.decode(segment.bytes, segment.ref, SegmentClass.ALBUM).rejectionOrNull()).isNotNull()
    assertThat(IndexSegments.decode(segment.bytes, segment.ref, SegmentClass.META).rejectionOrNull()).isNotNull()
    assertThat(AiSegments.decode(segment.bytes, segment.ref, AiClass.VEC).rejectionOrNull()).isNotNull()
  }

  @Test
  fun famigliaDispositivoEPostoDevonoTornare() {
    assertThat(fx.accept(raw(payload(family = TrustIds.random()))).rejectionOrNull()).isEqualTo(RejectReason.WRONG_FAMILY)
    // Firmata dal tablet a nome del telefono.
    assertThat(fx.accept(raw(payload(signer = fx.phone), signer = fx.tablet), signer = fx.tablet).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    // Copiata nella cartella di un altro dispositivo, di un altro album, o sotto un altro numero.
    val good = fx.head(listOf(segment.ref))
    assertThat(fx.accept(good, signer = fx.tablet).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    val other = AlbumIds.derive(AccountId("test"), ByteArray(16) { 7 })
    assertThat(fx.accept(good, albumId = other).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    assertThat(fx.accept(good, version = 2).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    // Un id che non e' base32 stretto.
    assertThat(fx.accept(raw(payload(album = AlbumIds.text(fx.album).uppercase()))).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(fx.accept(raw(payload(album = "0".repeat(25)))).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun antiRollbackEdEquivocazione() {
    val v1 = fx.head(listOf(segment.ref))
    val v2 = fx.head(listOf(segment.ref), version = 2)
    val mark2 = fx.accept(v2, version = 2).acceptedOrNull()!!.mark
    assertThat(fx.accept(v1, previous = mark2).rejectionOrNull()).isEqualTo(RejectReason.ROLLBACK)
    // Stessa versione, contenuto firmato diverso (un'altra ora di creazione): equivocazione.
    val forked = fx.head(listOf(segment.ref), version = 2, createdAt = fx.now + 1)
    assertThat(fx.accept(forked, version = 2, previous = mark2).rejectionOrNull()).isEqualTo(RejectReason.EQUIVOCATION)
    // Stesso contenuto con un'altra firma valida (ECDSA non e' deterministica): non e' un'equivocazione.
    val again = fx.head(listOf(segment.ref), version = 2)
    assertThat(again).isNotEqualTo(v2)
    assertThat(fx.accept(again, version = 2, previous = mark2).acceptedOrNull()).isNotNull()
    assertThat(fx.accept(fx.head(listOf(segment.ref), version = 3), version = 3, previous = mark2).acceptedOrNull()).isNotNull()
  }

  @Test
  fun campiSconosciutiFormaETettiDellaTesta() {
    fun ref(i: Int, size: Long = 100) = jsonObject("class" to "album".json(), "hash" to Hashes.sha256Name(byteArrayOf(i.toByte())).json(), "size" to size.json())
    val sorted = (0 until 65).map { ref(it) }.sortedBy { (it.members.getValue("hash") as dev.pampa.mycloudgallery.core.trust.codec.JsonString).value }
    val cases = listOf(
      "campo sconosciuto" to raw(payload(extra = "extra" to 1L)) to RejectReason.MALFORMED,
      "65 segmenti" to raw(payload(segments = sorted)) to RejectReason.TOO_LARGE,
      "segmento oltre 4 MiB" to raw(payload(segments = listOf(ref(1, AlbumLimits.MAX_SEGMENT_BYTES + 1L)))) to RejectReason.TOO_LARGE,
      "contributo oltre 32 MiB" to raw(payload(segments = sorted.take(9).map { it.with("size", AlbumLimits.MAX_SEGMENT_BYTES.toLong().json()) })) to RejectReason.TOO_LARGE,
      "segmenti fuori ordine" to raw(payload(segments = sorted.take(2).reversed())) to RejectReason.INVALID_FIELD,
      "segmento ripetuto" to raw(payload(segments = listOf(sorted[0], sorted[0]))) to RejectReason.INVALID_FIELD,
      "impronta non valida" to raw(payload(segments = listOf(ref(1).with("hash", "x".repeat(52).json())))) to RejectReason.INVALID_FIELD,
      "classe sconosciuta" to raw(payload(segments = listOf(ref(1).with("class", "faces".json())))) to RejectReason.INVALID_FIELD,
      "versione 0" to raw(payload(version = 0)) to RejectReason.INVALID_FIELD,
      "non firmato" to "x".repeat(300).toByteArray() to RejectReason.MALFORMED,
    )
    for ((case, reason) in cases) assertWithMessage(case.first).that(fx.accept(case.second).rejectionOrNull()).isEqualTo(reason)
    val createdIn1999 = payload().with("createdAt", 915_148_800_000L.json())
    assertThat(fx.accept(raw(createdIn1999)).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    // Un file troncato o con un byte in piu' non passa la busta.
    val good = fx.head(listOf(segment.ref))
    assertThat(fx.accept(good.copyOf(good.size - 1)).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    assertThat(fx.accept(good + byteArrayOf(0)).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
  }

  private fun JsonObject.with(key: String, value: dev.pampa.mycloudgallery.core.trust.codec.JsonValue): JsonObject =
    JsonObject(members.toMutableMap().apply { put(key, value) })
}
