package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.shared.Paper.T0
import dev.pampa.mycloudgallery.core.albums.shared.Paper.alice
import dev.pampa.mycloudgallery.core.albums.shared.Paper.bruno
import dev.pampa.mycloudgallery.core.albums.shared.Paper.carla
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumKey
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.data.LevelChange
import kotlin.random.Random
import org.junit.Test

/**
 * La fusione e' commutativa, associativa e idempotente (P2, "Fatto quando": test di proprieta' su 10.000 storie casuali di 3 account e 5
 * dispositivi). Ogni storia: alice (creatore, 2 dispositivi), bruno (2 dispositivi), carla (1 dispositivo, admin) scrivono a caso definizioni,
 * livelli, copertine, ordini, elementi (aggiunte, spostamenti, lapidi, anche di altri), eredita', a ore che a volte coincidono; ogni
 * dispositivo pubblica piu' versioni del suo contributo. Chi legge riceve le versioni in ordini e gruppi diversi, con doppioni: lo stato fuso
 * e' sempre lo stesso, e ogni voce vincitrice passa il controllo dei permessi.
 */
class AlbumFusionPropertyTest {

  private val devices: List<Pair<AccountId, Int>> = listOf(alice to 1, alice to 2, bruno to 1, bruno to 2, carla to 1)

  /** Una storia: per ogni dispositivo le versioni successive del suo contributo. */
  private fun history(random: Random): List<VerifiedContribution> {
    val state = devices.associateWith { LinkedHashMap<AlbumKey, AlbumEntry>() }
    val versions = HashMap<Pair<AccountId, Int>, Long>()
    val out = ArrayList<VerifiedContribution>()
    var now = T0
    var levels = listOf(LevelChange(T0, ShareLevel.entries.random(random)))
    // La creazione (a volte manca: l'album non si risolve) e il primo livello.
    if (random.nextInt(10) > 0) {
      state.getValue(alice to 1)[keyOf(AlbumField.Definition)] = Paper.entry(AlbumField.Definition, Paper.definition(), alice, T0)
      state.getValue(alice to 1)[keyOf(AlbumField.Level)] = Paper.entry(AlbumField.Level, AlbumValue.Levels(levels), alice, T0)
    }
    val steps = 4 + random.nextInt(16)
    repeat(steps) {
      if (random.nextInt(4) > 0) now += 1 + random.nextInt(5)
      val (account, device) = devices.random(random)
      val media = 1 + random.nextInt(6)
      val at = now
      val entry: AlbumEntry? = when (random.nextInt(9)) {
        0 -> Paper.entry(AlbumField.Definition, Paper.definition(title = listOf("Gita", "Mare", "Neve").random(random)), account, at, device)
        1 -> if (random.nextInt(3) == 0) Paper.entry(AlbumField.Definition, Paper.definition(listOf(AlbumDefinitionState.CLOSED, AlbumDefinitionState.DELETED).random(random), afterClose = listOf("remove", "keep_copy", "zzz").random(random)), account, at, device) else null
        2 -> {
          if (levels.size < 32 && levels.last().at < at) levels = levels + LevelChange(at, ShareLevel.entries.random(random))
          Paper.entry(AlbumField.Level, AlbumValue.Levels(levels), account, at, device)
        }
        3 -> Paper.entry(AlbumField.Cover, if (random.nextBoolean()) AlbumValue.Cover(Paper.media(media), Paper.key(media)) else null, account, at, device)
        4 -> Paper.entry(AlbumField.Sort, if (random.nextBoolean()) AlbumValue.Sort(AlbumSort.entries.random(random)) else null, account, at, device)
        5, 6 -> Paper.entry(AlbumField.Item(Paper.media(media)), Paper.item(media, if (random.nextInt(5) == 0) devices.random(random).first else account, random.nextLong(0, 10_000)), account, at, device)
        7 -> Paper.entry(AlbumField.Item(Paper.media(media)), null, account, at, device)
        else -> Paper.entry(AlbumField.Heir, dev.pampa.mycloudgallery.core.trust.data.AlbumValue.Heir(account, 1L + random.nextInt(12)), account, at, device)
      }
      if (entry != null) {
        state.getValue(account to device)[entry.key] = entry
        val v = (versions[account to device] ?: 0L) + 1
        versions[account to device] = v
        out += Paper.contribution(state.getValue(account to device).values.toList(), v).copy(contentHash = "h$v-${account.value}-$device")
      }
    }
    // Le versioni finali di chi non ha mai scritto dopo la creazione (alice-1 con la sola creazione).
    if (state.getValue(alice to 1).isNotEmpty() && versions[alice to 1] == null) out += Paper.contribution(state.getValue(alice to 1).values.toList(), 1)
    return out
  }

  private fun keyOf(field: AlbumField): AlbumKey = AlbumKey.of(Paper.album, field)

  private fun joinAll(contributions: List<VerifiedContribution>) = AlbumFusion.join(emptyMap(), contributions)

  @Test
  fun commutativaAssociativaEIdempotenteSu10000StorieCasuali() {
    var resolved = 0
    var denied = 0
    repeat(10_000) { seed ->
      val random = Random(seed)
      val registry = Paper.Registry(admins = mutableSetOf(Paper.device(carla))).apply { if (random.nextBoolean()) gone += alice }
      val all = history(random)
      val full = joinAll(all)
      val expected = AlbumFusion.resolve(full, registry)

      // Commutativa: le versioni in qualunque ordine.
      val shuffled = AlbumFusion.resolve(joinAll(all.shuffled(random)), registry)
      // Associativa: tre gruppi uniti in due modi diversi.
      val groups = all.groupBy { random.nextInt(3) }
      val (a, b, c) = Triple(groups[0].orEmpty(), groups[1].orEmpty(), groups[2].orEmpty())
      val left = AlbumFusion.join(AlbumFusion.join(joinAll(a), b), c)
      val right = AlbumFusion.join(joinAll(a), AlbumFusion.join(joinAll(b), c).values)
      // Idempotente: unire due volte le stesse versioni, o lo stato con se stesso.
      val twice = AlbumFusion.join(full, all)
      val self = AlbumFusion.join(full, full.values)
      // Le voci dentro un contributo in un altro ordine.
      val reordered = full.mapValues { (_, v) -> v.copy(entries = v.entries.shuffled(random)) }

      assertWithMessage("storia $seed: unione associativa").that(left).isEqualTo(right)
      assertWithMessage("storia $seed: unione idempotente").that(twice).isEqualTo(full)
      assertWithMessage("storia $seed: unione con se stessa").that(self).isEqualTo(full)
      for ((name, other) in listOf("ordine" to shuffled, "gruppi" to AlbumFusion.resolve(left, registry), "doppioni" to AlbumFusion.resolve(twice, registry), "voci" to AlbumFusion.resolve(reordered, registry))) {
        assertWithMessage("storia $seed: $name").that(other).isEqualTo(expected)
      }
      if (expected != null) {
        resolved++
        denied += expected.denied.size
        assertWinnersPass(expected, full, registry, seed)
      }
    }
    // Le storie esercitano davvero la fusione: album risolti e voci scartate.
    assertThat(resolved).isGreaterThan(5_000)
    assertThat(denied).isGreaterThan(5_000)
  }

  /** Ogni voce vincitrice e' permessa con il livello alla sua ora e l'erede di allora (la stessa tabella, C25). */
  private fun assertWinnersPass(merged: MergedAlbum, state: Map<String, VerifiedContribution>, registry: Paper.Registry, seed: Int) {
    val all = state.values.flatMap { it.entries }
    for ((key, winner) in merged.winners) {
      if (winner.field == AlbumField.Definition || winner.field == AlbumField.Heir) continue
      val before = all.filter { it.key == key }.sortedWith(AlbumFusion.ORDER).takeWhile { it != winner }
      // La voce precedente ammessa e l'ora dell'aggiunta sono fra quelle prima della vincitrice: basta che una combinazione la renda permessa.
      val candidates = listOf<AlbumEntry?>(null) + before
      val addedAts = listOf<Long?>(null) + before.map { it.at }
      val ok = candidates.any { previous ->
        addedAts.any { addedAt ->
          val verdict = AlbumPermissions.check(
            winner, merged.levels, merged.creator, merged.heirAt(winner.at), previous,
            CheckContext(registry.isAdminDevice(winner.author), registry.accountLeft(merged.creator), registry.version, merged.closedAt, addedAt),
          )
          verdict is PermissionVerdict.Allowed
        }
      }
      assertWithMessage("storia $seed: vincitrice ${winner.field.wire} permessa").that(ok).isTrue()
    }
  }
}
