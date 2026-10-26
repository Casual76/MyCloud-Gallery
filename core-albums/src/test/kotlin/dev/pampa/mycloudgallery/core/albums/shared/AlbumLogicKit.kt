package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.data.LevelChange
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest

/**
 * Un album "di carta" per i test puri della fusione e dei permessi (nessun NAS, nessuna firma): account, dispositivi, voci e contributi
 * costruiti a mano. Il creatore e' `alice`; `dora` ha un dispositivo admin.
 */
object Paper {
  val alice = AccountId("alice")
  val bruno = AccountId("bruno")
  val carla = AccountId("carla")
  val dora = AccountId("dora")
  val nonce: ByteArray = ByteArray(16) { (it * 5 + 2).toByte() }
  val nonceHex: String = nonce.joinToString("") { "%02x".format(it) }
  val album: AlbumId = AlbumId.derived(alice, nonce)

  /** 1/1/2027 alle 10: le ore delle voci sono da qui in poi. */
  const val T0: Long = 1_798_794_000_000L

  /** Un id di dispositivo stabile per (account, n): 26 caratteri base32 dei primi 16 byte di uno SHA-256. */
  fun device(account: AccountId, n: Int = 1): String =
    Base32.encode(MessageDigest.getInstance("SHA-256").digest("${account.value}#$n".toByteArray()).copyOf(16))

  fun media(n: Int): MediaId = MediaId(java.util.UUID(0x3_0000_0000L + n, n.toLong()).toString())

  fun key(n: Int): ContentKey = ContentKey(MessageDigest.getInstance("SHA-256").digest("k$n".toByteArray()).joinToString("") { "%02x".format(it) })

  fun definition(state: AlbumDefinitionState = AlbumDefinitionState.ACTIVE, title: String = "Gita", afterClose: String? = null) =
    AlbumValue.Definition(title, SharePath.of("Album condivisi", "Gita"), state, alice, nonceHex, if (state == AlbumDefinitionState.ACTIVE) null else afterClose ?: "remove")

  fun item(n: Int, by: AccountId, position: Long = n * 1_000L) = AlbumValue.Item(key(n), SharePath.parseMediaPath("Album condivisi/Gita/$n.jpg"), position, by)

  fun levels(vararg steps: Pair<Long, ShareLevel>) = AlbumValue.Levels(steps.map { LevelChange(it.first, it.second) })

  fun entry(field: AlbumField, value: AlbumValue?, account: AccountId, at: Long, device: Int = 1) =
    AlbumEntry(album, field, at, device(account, device), account, value)

  /** Un contributo per (account, dispositivo) con le voci date (una per chiave). */
  fun contribution(entries: List<AlbumEntry>, version: Long = 1): VerifiedContribution {
    val first = entries.first()
    return VerifiedContribution(album, first.author, first.account, version, "h$version-${first.author}", T0, entries)
  }

  /** I contributi delle voci date, raggruppate per dispositivo (l'ultima voce per chiave di ogni dispositivo). */
  fun contributions(entries: List<AlbumEntry>): List<VerifiedContribution> = entries.groupBy { it.author }.values.map { mine ->
    contribution(mine.groupBy { it.key }.values.map { same -> same.maxBy { it.at } })
  }

  /** Il registro di carta: chi ha un dispositivo admin, chi e' uscito dalla famiglia. */
  class Registry(override var version: Long = 10, val admins: MutableSet<String> = (1..3).map { device(dora, it) }.toMutableSet(), val gone: MutableSet<AccountId> = mutableSetOf()) : AlbumRegistryView {
    override fun isAdminDevice(device: String): Boolean = device in admins

    override fun accountLeft(account: AccountId): Boolean = account in gone
  }

  fun merge(entries: List<AlbumEntry>, registry: AlbumRegistryView = Registry()): MergedAlbum? = AlbumFusion.merge(contributions(entries), registry)
}
