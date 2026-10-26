package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.JsonValue
import dev.pampa.mycloudgallery.core.trust.crypto.EcdsaDer
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import java.nio.ByteBuffer

/**
 * Gli album di prova dei test del codec (Fase 7, P2): una famiglia vera (registro firmato), il telefono di `test` (creatore) e il tablet di
 * `Test2` (account scritto con la maiuscola nel registro: nelle voci va in minuscolo), un album con l'id legato a `test`, voci di ogni campo.
 */
class AlbumFx {
  val family = TestFamily()
  val phone: Signer = family.approveNewDevice(name = "Telefono di Test", nasUser = "test")
  val tablet: Signer = family.approveNewDevice(name = "Tablet di Test2", nasUser = "Test2")
  val nonce: ByteArray = ByteArray(16) { (it * 3 + 1).toByte() }
  val album: AlbumId = AlbumIds.derive(AccountId("test"), nonce)
  val now: Long get() = family.clock.nowMillis()

  fun account(signer: Signer): AccountId = AccountId.of(family.state.registry.device(signer.deviceId)!!.nasUser)

  fun context(signer: Signer = phone): AlbumSegmentContext = AlbumSegmentContext(album, signer.deviceId, account(signer))

  fun definition(
    title: String = "Gita a Oslo",
    state: AlbumDefinitionState = AlbumDefinitionState.ACTIVE,
    afterClose: String? = null,
    creator: String = "test",
    nonceBytes: ByteArray = nonce,
  ) = AlbumValue.Definition(title, SharePath.of("Album condivisi", "Gita a Oslo"), state, AccountId(creator), Hex.of(nonceBytes), afterClose)

  fun media(n: Int): MediaId = MediaId(java.util.UUID(0x5_0000_0000L + n, n.toLong()).toString())

  fun key(n: Int): ContentKey = ContentKey.of(QuickHash(Hex.of(Hashes.sha256("q$n".toByteArray()))), 1_000L + n)

  fun item(n: Int, by: String = "test") = AlbumValue.Item(
    key(n), SharePath.parseMediaPath("Album condivisi/Gita a Oslo/IMG_$n.jpg"), n * 1_000L, AccountId(by),
    VersionId(java.util.UUID(9, n.toLong()).toString()), Sha256(Hex.of(Hashes.sha256("f$n".toByteArray()))),
  )

  fun entry(field: AlbumField, value: AlbumValue?, signer: Signer = phone, at: Long = now, albumId: AlbumId = album): AlbumEntry =
    AlbumEntry(albumId, field, at, signer.deviceId, account(signer), value)

  /** Una voce per ogni campo e forma (lapidi comprese). */
  fun everyKind(signer: Signer = phone): List<AlbumEntry> = listOf(
    entry(AlbumField.Definition, definition()),
    entry(AlbumField.Level, AlbumValue.Levels(listOf(LevelChange(now - 60_000, ShareLevel.ALL), LevelChange(now, ShareLevel.ADD)))),
    entry(AlbumField.Cover, AlbumValue.Cover(media(1), key(1))),
    entry(AlbumField.Sort, AlbumValue.Sort(AlbumSort.OLDEST_FIRST)),
    entry(AlbumField.Heir, AlbumValue.Heir(AccountId("test"), 3)),
    entry(AlbumField.Item(media(1)), item(1)),
    entry(AlbumField.Item(media(2)), AlbumValue.Item(key(2), SharePath.parseMediaPath("Foto/b.jpg"), 0, AccountId("test"))),
    entry(AlbumField.Item(media(3)), null),
  ).map { it.copy(author = signer.deviceId, account = account(signer)) }

  /** I byte della testa firmata da [signer] con [segments]. */
  fun head(segments: List<SegmentRef>, version: Long = 1, signer: Signer = phone, createdAt: Long = now, albumId: AlbumId = album): ByteArray =
    AlbumDocs.encodeHead(AlbumHead(family.familyId, albumId, signer.deviceId, version, family.state.registry.version, createdAt, segments), signer).bytes

  fun accept(bytes: ByteArray, version: Long = 1, signer: Signer = phone, previous: HeadMark? = null, albumId: AlbumId = album) =
    AlbumDocs.decodeHead(bytes, family.state, AlbumHeadLocation(albumId, signer.deviceId, version), previous)

  companion object {
    private val MAGIC_S = "MCGS".toByteArray(Charsets.US_ASCII)
    private val DOMAIN = "mycloudgallery/signed\u0000".toByteArray(Charsets.US_ASCII)

    /** Una busta firmata **senza** i controlli di `Envelope.seal` (versione e tipo qualunque): per i test ostili. */
    fun rawSeal(type: DocType, payload: JsonObject, signer: Signer, version: Int): ByteArray {
      val body = CanonicalJson.encode(payload)
      val signed = ByteBuffer.allocate(4 + 1 + 1 + 91 + 4 + body.size)
        .put(MAGIC_S).put(version.toByte()).put(type.code.toByte()).put(signer.publicKey.encoded).putInt(body.size).put(body).array()
      val signature = EcdsaDer.normalize(signer.sign(DOMAIN + signed))
      return signed + byteArrayOf(signature.size.toByte()) + signature
    }

    /** Un segmento con le righe [rows] (chiave di 32 byte, corpo) cosi' come sono: versione, classe, conteggio e riservati a scelta. */
    fun rawSegment(rows: List<Pair<ByteArray, ByteArray>>, version: Int = 3, cls: Int = 17, count: Int = rows.size, reserved: Int = 0): Pair<ByteArray, SegmentRef> {
      val size = 5 + 8 + 36 * rows.size + rows.sumOf { it.second.size }
      val out = ByteBuffer.allocate(size)
      out.put("MCGB".toByteArray(Charsets.US_ASCII)).put(version.toByte()).put(cls.toByte()).put(reserved.toByte()).put(0).put(0).putInt(count)
      for ((key, body) in rows) out.put(key).putInt(body.size)
      for ((_, body) in rows) out.put(body)
      val bytes = out.array()
      return bytes to SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong())
    }

    fun json(bytes: ByteArray): JsonObject = CanonicalJson.parseCanonical(bytes) as JsonObject

    /** [obj] con [key] = [value] (null = tolto). */
    fun JsonObject.with(key: String, value: JsonValue?): JsonObject =
      JsonObject(members.toMutableMap().apply { if (value == null) remove(key) else put(key, value) })

    /** [obj] con il campo [key] del valore cambiato. */
    fun JsonObject.withValue(key: String, value: JsonValue?): JsonObject = with("value", (members.getValue("value") as JsonObject).with(key, value))
  }
}
