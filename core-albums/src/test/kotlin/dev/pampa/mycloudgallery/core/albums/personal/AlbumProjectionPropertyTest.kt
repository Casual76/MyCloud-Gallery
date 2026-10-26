package dev.pampa.mycloudgallery.core.albums.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La prova di proprieta' di D5: le tabelle sono **derivate**. Dopo una storia a caso di modifiche (aggiunte, rimozioni, copertine, album
 * cancellati e ricreati, voci di un altro dispositivo con id che qui non ci sono, elementi in attesa che arrivano, smart con esclusioni e
 * congelamenti, unioni d'identita' del catalogo) le righe che la proiezione ha tenuto al passo, un album per volta, sono **identiche**
 * a quelle che si ottengono ricostruendo da zero dallo stesso stato. Lo stato e' una funzione sola delle voci e del catalogo: non conta la
 * strada.
 */
@RunWith(AndroidJUnit4::class)
class AlbumProjectionPropertyTest {

  private class History(val seed: Int) {
    val room = AlbumsRoom()
    val random = Random(seed)
    var clock = T0
    val store = room.store(nasNow = { clock += 10; clock })
    val albums = PersonalAlbums(store)
    val projection = CatalogAlbumProjection(room.catalog, room.db)
    val ids = ArrayList<String>()
    val media = ArrayList<MediaId>()
    val unmerged = ArrayList<Pair<MediaId, MediaId>>()
    val later = ArrayList<Int>()
    var contentSeed = 1_000
    var nonce = seed * 1_000
    val other = TrustIds.random()
    val third = TrustIds.random()

    suspend fun project(only: Set<String>?) = projection.project(store.effective(), only)

    private fun pickAlbum(): String? = if (ids.isEmpty()) null else ids[random.nextInt(ids.size)]

    private fun pickMedia(): MediaId? = if (media.isEmpty()) null else media[random.nextInt(media.size)]

    private fun position() = (random.nextInt(1, 6) * 1_000).toLong()

    /** Una modifica a caso e il suo effetto sulle tabelle, come lo fa chi tiene la proiezione al passo. */
    suspend fun step() {
      val album = pickAlbum()
      val m = pickMedia()
      when (random.nextInt(16)) {
        0 -> {
          // Un media nuovo nel catalogo: una scansione con novita' (puo' risolvere un elemento in attesa).
          val arrived = room.record(contentSeed++)
          media += arrived
          project(projection.affectedBy(listOf(arrived)))
        }
        1, 2 -> {
          val id = AlbumId.derived(AlbumsRoom.ME, ByteArray(16) { (nonce + it).toByte() }.also { nonce++ }).value
          ids += id
          if (random.nextInt(3) == 0) albums.setKind(id, AlbumKind.SMART)
          albums.define(id, "Album ${ids.size}")
          project(setOf(id))
        }
        3, 4, 5 -> if (album != null && m != null) {
          albums.addItem(album, m.value, position())
          project(setOf(album))
        }
        6 -> if (album != null && m != null) {
          albums.removeItem(album, m.value)
          project(setOf(album))
        }
        7 -> if (album != null) {
          albums.setCover(album, if (random.nextBoolean()) m?.value else null)
          project(setOf(album))
        }
        8 -> if (album != null) {
          if (random.nextInt(3) == 0) albums.delete(album) else albums.define(album, "Rinominato ${random.nextInt(9)}")
          project(setOf(album))
        }
        9 -> if (album != null) {
          // Un altro dispositivo: un id che qui non c'e', l'impronta di un media che c'e' o che deve ancora arrivare.
          val seedOfFile = if (media.isNotEmpty() && random.nextInt(3) != 0) 1_000 + random.nextInt(media.size) else {
            later += contentSeed
            contentSeed++
          }
          val author = if (random.nextBoolean()) other else third
          val at = clock + random.nextLong(-30, 30)
          val on = random.nextInt(5) != 0
          val field = PersonalFields.albumItem(MediaId("00000000-0000-4000-8000-%012d".format(random.nextInt(1_000_000))).value)
          store.applyRemote(
            listOf(
              PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, field), at, author, if (on) PersonalValue.Number(position()) else PersonalValue.Removed, room.fingerprintOf(seedOfFile)),
            ),
          )
          project(setOf(album))
        }
        10 -> if (album != null && m != null) {
          when (random.nextInt(4)) {
            0 -> albums.exclude(album, m.value)
            1 -> albums.accept(album, m.value)
            2 -> albums.setFrozen(album, random.nextBoolean())
            else -> albums.setSmart(album, PersonalFields.SMART_TEXT, if (random.nextBoolean()) "neve" else null)
          }
          project(setOf(album))
        }
        11 -> {
          val pair = room.twins(contentSeed++)
          unmerged += pair
          media += pair.first
          media += pair.second
          project(projection.affectedBy(listOf(pair.first, pair.second)))
        }
        12 -> if (unmerged.isNotEmpty()) {
          val (a, b) = unmerged.removeAt(random.nextInt(unmerged.size))
          room.catalog.merge(a, b)
          // La scansione che ha fatto l'unione dice quali media ha toccato: la proiezione sa quali album riguardano.
          project(projection.affectedBy(listOf(a, b)))
        }
        13 -> if (later.isNotEmpty()) {
          // Il media di un'impronta "in attesa" arriva.
          val n = later.removeAt(random.nextInt(later.size))
          val arrived = room.record(n)
          media += arrived
          project(projection.affectedBy(listOf(arrived)))
        }
        14 -> if (album != null && m != null) {
          albums.setExample(album, m.value, random.nextBoolean())
          project(setOf(album))
        }
        else -> if (album != null) {
          albums.setShared(album, if (random.nextBoolean()) "00000000-0000-4000-8000-000000000001" else null)
          project(setOf(album))
        }
      }
    }
  }

  @Test
  fun ilRisultatoDopoUnaStoriaACasoEUgualeAUnaRicostruzioneDaZero() = runBlocking<Unit> {
    var checks = 0
    for (seed in 1..14) {
      val history = History(seed)
      try {
        repeat(70) { n ->
          history.step()
          if (n % 7 == 6) {
            val incremental = dump(history.room)
            // Da zero: via tutte le righe derivate, poi la ricostruzione dallo stato.
            history.room.db.albumDao().all().filter { it.state == null }.forEach { history.room.db.albumDao().delete(it.albumId) }
            history.projection.rebuild(history.store.effective())
            assertWithMessage("seed $seed, passo $n").that(dump(history.room)).isEqualTo(incremental)
            checks++
          }
        }
      } finally {
        history.room.close()
      }
    }
    assertWithMessage("controlli fatti").that(checks).isEqualTo(14 * 10)
  }
}
