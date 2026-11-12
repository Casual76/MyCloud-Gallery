package dev.pampa.mycloudgallery.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiResults
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.hidden.BuiltWorld
import dev.pampa.mycloudgallery.hidden.HiddenWorldBuilder
import dev.pampa.mycloudgallery.hidden.WorldSpec
import dev.pampa.mycloudgallery.hidden.Where
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * XS-4 (Pacchetto XF): **un candidato chiesto fuori dai primi della lista locale entra comunque.** Le liste locali del calcolo sono limitate (2.000
 * media dal piu' recente): un media del solo telefono o di una share chiesto con "Analizza adesso", ma piu' vecchio del limite, non veniva nemmeno
 * considerato. La prova usa il catalogo vero (`MediaCatalog` su un mondo di prova) e le porte vere (`CatalogAiCandidates`, `AppAiPhone`,
 * `CatalogAiWorkCatalog`) con un limite piccolo. Mutazione: togliere l'aggiunta dei chiesti fa cadere entrambe.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class RequestedBeyondLimitTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private val noBytes = object : PhoneBytes {
    override suspend fun lookup(ref: PhoneRef): PhoneLookup = error("non serve")

    override suspend fun open(ref: PhoneRef): PhoneOpen = error("non serve")
  }

  private class Ports(val world: BuiltWorld, val candidates: CatalogAiCandidates, val keyOf: Map<MediaId, ContentKey>)

  private suspend fun ports(asked: () -> MediaId?): Ports {
    val world = HiddenWorldBuilder.build(context, WorldSpec.nothingHidden(seed = 9_002, size = 60))
    val cells = world.catalog.mediaContentKeysOf(world.ids)
    val keyOf = cells.associate { it.mediaId to it.own.first() }
    val unsupported = FileAiUnsupported(File(temp.root, "unsupported"))
    val facts = ContentFacts()
    val phone = AppAiPhone({ world.catalog }, noBytes, FakeAiResults(), unsupported, facts, AiRecipe.CURRENT)
    val work = CatalogAiWorkCatalog({ world.catalog }, { AccountId.of(HiddenWorldBuilder.ME) }, RoomAiReplicaStore(world.db), unsupported, AiRecipe.CURRENT, facts)
    val inverse = keyOf.entries.associate { (id, key) -> key to id }
    val candidates = CatalogAiCandidates(work, { false }, phone) { key -> asked()?.takeIf { inverse[key] == it } }
    return Ports(world, candidates, keyOf)
  }

  @Test
  fun unMediaDelSoloTelefonoChiestoOltreIlLimiteEntraComunque() = runBlocking<Unit> {
    var asked: MediaId? = null
    val p = ports { asked }
    try {
      val phoneOnly = p.world.spec.media.filter { it.where == Where.PHONE }.map { p.world.ids[it.index] }
      assertThat(phoneOnly.size).isAtLeast(8)
      val base = p.candidates.phoneOnly(recipe = 1, limit = LIMIT)
      assertThat(base).hasSize(LIMIT)
      val inBase = base.map { it.contentKey }.toSet()
      // Un media del solo telefono che la lista limitata non ha.
      val beyond = phoneOnly.first { p.keyOf.getValue(it) !in inBase }
      asked = beyond

      val withAsked = p.candidates.phoneOnly(recipe = 1, limit = LIMIT, include = setOf(p.keyOf.getValue(beyond)))

      assertThat(withAsked.map { it.contentKey }).containsAtLeastElementsIn(base.map { it.contentKey })
      assertThat(withAsked.map { it.contentKey }).contains(p.keyOf.getValue(beyond))
      assertThat(withAsked).hasSize(LIMIT + 1)
      // Senza richieste la lista e' quella di sempre.
      assertThat(p.candidates.phoneOnly(recipe = 1, limit = LIMIT, include = emptySet()).map { it.contentKey }).isEqualTo(base.map { it.contentKey })
    } finally {
      p.world.close()
    }
  }

  @Test
  fun unMediaChiestoCheHaUnaCopiaSulNasNonEPiuDelSoloTelefono() = runBlocking<Unit> {
    var asked: MediaId? = null
    val p = ports { asked }
    try {
      // Un media che sta anche sul NAS non e' un candidato "solo telefono": nessuna aggiunta, anche se chiesto.
      val onNas = p.world.spec.media.first { it.where == Where.PHONE_AND_PRIVATE }.let { p.world.ids[it.index] }
      asked = onNas
      val base = p.candidates.phoneOnly(recipe = 1, limit = LIMIT)
      val withAsked = p.candidates.phoneOnly(recipe = 1, limit = LIMIT, include = setOf(p.keyOf.getValue(onNas)))
      assertThat(withAsked.map { it.contentKey }).isEqualTo(base.map { it.contentKey })
    } finally {
      p.world.close()
    }
  }

  @Test
  fun unMediaDiUnaShareChiestoOltreIlLimiteEntraComunque() = runBlocking<Unit> {
    var asked: MediaId? = null
    val p = ports { asked }
    try {
      val share = HiddenWorldBuilder.PRIVATE_SHARE
      val inShare = p.world.spec.media.filter { it.where == Where.NAS_PRIVATE || it.where == Where.PHONE_AND_PRIVATE }.map { p.world.ids[it.index] }
      assertThat(inShare.size).isAtLeast(8)
      val base = p.candidates.forShare(share, recipe = 1, limit = LIMIT)
      assertThat(base).hasSize(LIMIT)
      val inBase = base.map { it.contentKey }.toSet()
      val beyond = inShare.first { p.keyOf.getValue(it) !in inBase }
      asked = beyond

      val withAsked = p.candidates.forShare(share, recipe = 1, limit = LIMIT, include = setOf(p.keyOf.getValue(beyond)))

      assertThat(withAsked.map { it.contentKey }).contains(p.keyOf.getValue(beyond))
      assertThat(withAsked).hasSize(LIMIT + 1)
      // Un media che la share non ha non si aggiunge.
      val foreign = p.world.spec.media.first { it.where == Where.PHONE }.let { p.world.ids[it.index] }
      asked = foreign
      val none = p.candidates.forShare(share, recipe = 1, limit = LIMIT, include = setOf(p.keyOf.getValue(foreign)))
      assertThat(none.map { it.contentKey }).isEqualTo(base.map { it.contentKey })
    } finally {
      p.world.close()
    }
  }

  private companion object {
    const val LIMIT = 3
  }
}
