package dev.pampa.mycloudgallery.core.faces.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import java.io.File
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P6, §5.2, "fake fedeli con test di parita'"): l'archivio vero (cifrato, sopra Room, con la memoria dei volti per il raggruppamento)
 * e quello in memoria di P0 rispondono **allo stesso modo** a ogni lettura dopo la stessa storia di scritture. Storie a caso (400 passi su 30
 * contenuti e 3 dispositivi: corpi nuovi, rifatti con altri volti, con ricette diverse, "non supportato"), con una lettura completa di tutto dopo ogni passo e una chiusura-riapertura del negozio ogni tanto: vincitori, "gia'
 * fatto", non riconoscibili, volti da raggruppare (riferimenti, posti, livelli e vettori) e lo stesso ordine.
 */
@RunWith(AndroidJUnit4::class)
class FacesStoreParityTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val world by lazy { StoreWorld(File(folder.root, "faces")) }
  private val generation = FaceGeneration.fromBytes(ByteArray(16) { 7 })
  private val authors = listOf("00000000000000000000000000", "5555555555555555555555555x", "zzzzzzzzzzzzzzzzzzzzzzzzzz")

  private fun randomBody(random: Random, keys: List<ContentKey>, step: Int): FaceBody {
    val count = random.nextInt(5)
    val tiers = listOf(FaceTier.CORE, FaceTier.WEAK, FaceTier.NONE)
    val faces = (0 until count).map { i -> FaceFixtures.entry(i, tiers[random.nextInt(3)], seed = step * 8 + i, frame = random.nextInt(3)) }
    val key = keys[random.nextInt(keys.size)]
    return FaceBody(
      contentKey = key,
      space = FaceSpaceCode.CURRENT,
      detector = FaceDetectorCode.entries[random.nextInt(FaceDetectorCode.entries.size)],
      recipe = 1 + random.nextInt(3),
      source = FaceSource.entries[random.nextInt(FaceSource.entries.size)],
      author = authors[random.nextInt(authors.size)],
      faces = faces,
      digest = 10_000L + step,
    )
  }

  private suspend fun assertSame(real: FacesArchive, reference: FacesArchive, keys: List<ContentKey>) {
    val a = real.winners(keys)
    val b = reference.winners(keys)
    assertThat(a.keys).containsExactlyElementsIn(b.keys)
    for ((key, expected) in b) {
      val got = a.getValue(key)
      assertThat(got.author).isEqualTo(expected.author)
      assertThat(got.recipe).isEqualTo(expected.recipe)
      assertThat(got.digest).isEqualTo(expected.digest)
      assertThat(got.detector).isEqualTo(expected.detector)
      assertThat(got.source).isEqualTo(expected.source)
      assertThat(got.faces.size).isEqualTo(expected.faces.size)
      for (i in expected.faces.indices) {
        val x = expected.faces[i]
        val y = got.faces[i]
        assertThat(listOf(y.index, y.frame, y.box, y.score, y.tier, y.reasons, y.sidePx, y.yaw, y.sharpness))
          .isEqualTo(listOf(x.index, x.frame, x.box, x.score, x.tier, x.reasons, x.sidePx, x.yaw, x.sharpness))
        assertThat(y.landmarks).isEqualTo(x.landmarks)
        assertThat(y.vector).isEqualTo(x.vector)
      }
    }
    for (recipe in 1..4) assertThat(real.analyzed(keys, recipe)).isEqualTo(reference.analyzed(keys, recipe))
    assertThat(real.unrecognizable(keys)).isEqualTo(reference.unrecognizable(keys))
    val ga = real.groupFaces()
    val gb = reference.groupFaces()
    assertThat(ga.map { it.ref }).isEqualTo(gb.map { it.ref })
    assertThat(ga.map { it.locator.box }).isEqualTo(gb.map { it.locator.box })
    assertThat(ga.map { it.locator.frame }).isEqualTo(gb.map { it.locator.frame })
    assertThat(ga.map { it.tier }).isEqualTo(gb.map { it.tier })
    for (i in ga.indices) assertThat(ga[i].vector()).isEqualTo(gb[i].vector())
  }

  @Test
  fun lArchivioVeroRispondeComeQuelloInMemoriaSuStorieACaso() = runBlocking<Unit> {
    val keys = (1..30).map { FaceFixtures.key(it) }
    for (seed in listOf(1L, 2L)) {
      val random = Random(seed)
      var store = world.ready(generation)
      val reference = InMemoryFacesArchive(generation)
      for (step in 1..200) {
        when (random.nextInt(10)) {
          0 -> {
            val key = keys[random.nextInt(keys.size)]
            val recipe = 1 + random.nextInt(4)
            store.archive.markUnsupported(key, recipe)
            reference.markUnsupported(key, recipe)
          }
          else -> {
            val body = randomBody(random, keys, step)
            store.archive.saveBody(body)
            reference.saveBody(body)
          }
        }
        assertSame(store.archive, reference, keys)
        if (step % 50 == 0) {
          // Chiudere e riaprire il negozio (un processo nuovo): stesse risposte, e la memoria dei volti si rifa' da capo.
          store.close()
          store = world.ready(generation)
          assertSame(store.archive, reference, keys)
        }
      }
      store.close()
      world.stores.eraseLocal(generation)
    }
  }

  @Test
  fun laMemoriaDeiVoltiSiAggiornaConLeScrittureEIVincitoriCheCambiano() = runBlocking<Unit> {
    val store = world.ready(generation)
    val archive = store.archive
    val k = FaceFixtures.key(1)
    val small = FaceFixtures.AUTHOR_SMALL
    val big = FaceFixtures.AUTHOR_BIG
    assertThat(archive.groupFaces()).isEmpty() // la memoria parte vuota
    archive.saveBody(FaceFixtures.body(k, big, recipe = 1, faces = listOf(FaceFixtures.entry(0, FaceTier.CORE, seed = 1))))
    assertThat(archive.groupFaces()).hasSize(1)
    // Un altro dispositivo con la stessa ricetta ma l'id minore vince: i suoi volti prendono il posto di quelli di prima.
    archive.saveBody(FaceFixtures.body(k, small, recipe = 1, faces = listOf(FaceFixtures.entry(0, FaceTier.CORE, seed = 2), FaceFixtures.entry(1, FaceTier.WEAK, seed = 3))))
    val after = archive.groupFaces()
    assertThat(after).hasSize(2)
    assertThat(after.map { it.ref.index }).containsExactly(0, 1).inOrder()
    assertThat(after[0].ref.bodyDigest).isEqualTo(FaceFixtures.body(k, small, recipe = 1).digest)
    // Una ricetta piu' alta dell'altro dispositivo lo rimette in testa; "analizzato, nessun volto" li toglie tutti dalla memoria.
    archive.saveBody(FaceFixtures.body(k, big, recipe = 2, faces = emptyList()))
    assertThat(archive.groupFaces()).isEmpty()
    store.close()
  }
}
