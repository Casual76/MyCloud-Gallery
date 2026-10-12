package dev.pampa.mycloudgallery.core.model.domain

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.EmbeddingVector
import dev.pampa.mycloudgallery.core.model.Fixtures.ME
import dev.pampa.mycloudgallery.core.model.Fixtures.T0
import dev.pampa.mycloudgallery.core.model.Fixtures.full
import dev.pampa.mycloudgallery.core.model.Fixtures.nas
import dev.pampa.mycloudgallery.core.model.Fixtures.phone
import dev.pampa.mycloudgallery.core.model.Fixtures.quick
import dev.pampa.mycloudgallery.core.model.Fixtures.single
import dev.pampa.mycloudgallery.core.model.Fixtures.stamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.VectorPrecision
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import org.junit.Test

class MergeRulesTest {

  private val low = MediaId("00000000-0000-4000-8000-000000000001")
  private val high = MediaId("ffffffff-ffff-4fff-bfff-ffffffffffff")

  @Test
  fun ilSopravvissutoEDeterministicoQualunqueSiaLOrdine() {
    assertThat(MergeRules.survivorOf(low, high)).isEqualTo(low)
    assertThat(MergeRules.survivorOf(high, low)).isEqualTo(low)

    val a = single(full(1), nas("a.jpg"), id = high)
    val b = single(full(1), nas("b.jpg"), locationId = 2, id = low)
    val ab = MergeRules.plan(a, b, ME) as MergePlan.Merge
    val ba = MergeRules.plan(b, a, ME) as MergePlan.Merge
    assertThat(ab.survivor).isEqualTo(low)
    assertThat(ba.survivor).isEqualTo(low)
    assertThat(ab.loser).isEqualTo(high)
  }

  @Test
  fun senzaHashCompletiUgualiDegliOriginaliNonSiUnisce() {
    val a = single(quick(1), nas("a.jpg"))
    val b = single(quick(1), nas("b.jpg"), locationId = 2)
    assertThat(MergeRules.plan(a, b, ME)).isEqualTo(MergePlan.Refused(MergeRefusal.ORIGINALS_NOT_PROVEN_IDENTICAL))

    val c = single(full(1), nas("a.jpg"))
    val d = single(full(2), nas("b.jpg"), locationId = 2)
    assertThat(MergeRules.plan(c, d, ME)).isEqualTo(MergePlan.Refused(MergeRefusal.ORIGINALS_NOT_PROVEN_IDENTICAL))

    assertThat(MergeRules.plan(c, c, ME)).isEqualTo(MergePlan.Refused(MergeRefusal.SAME_MEDIA))
  }

  /**
   * Il caso tipico: la foto del telefono, gia' modificata, e lo stesso originale trovato sul NAS come
   * media a parte (scoperto prima di avere gli hash completi). Dopo l'unione: un media, la modifica
   * resta la corrente, tutte le posizioni ci sono, l'obbligo di backup resta.
   */
  @Test
  fun lUnioneConservaLaModificaPiuRecenteETutteLePosizioni() {
    val fromPhone = single(full(1), phone("a.jpg", 10), id = high, owner = ME)
      .let { VersionRules.contentChangedAt(it, LocationId(1), full(2), stamp(full(2), T0 + 5_000), T0 + 5_000).media }
    val fromNas = single(full(1), nas("Foto/a.jpg", share = "NAS"), locationId = 2, owner = null, id = low)
      .copy(source = MediaSource.OTHER)

    val plan = MergeRules.plan(fromPhone, fromNas, ME) as MergePlan.Merge
    val merged = plan.merged

    assertThat(merged.id).isEqualTo(low)
    assertThat(merged.versions).hasSize(2)
    assertThat(merged.originalVersion.id).isEqualTo(fromNas.originalVersion.id)
    assertThat(merged.currentVersion.fingerprint).isEqualTo(full(2))
    assertThat(merged.currentVersion.kind).isEqualTo(VersionKind.EDITED)
    // La modifica ora discende dall'originale del sopravvissuto.
    assertThat(merged.currentVersion.parentId).isEqualTo(fromNas.originalVersion.id)
    assertThat(merged.locations.map { it.id }).containsExactly(LocationId(1), LocationId(2))
    assertThat(merged.owner).isEqualTo(ME)
    assertThat(merged.source).isEqualTo(MediaSource.CAMERA)
    assertThat(plan.translate(fromPhone.originalVersion.id)).isEqualTo(fromNas.originalVersion.id)
    assertThat(plan.translate(fromPhone.currentVersionId)).isEqualTo(fromPhone.currentVersionId)
  }

  @Test
  fun aParitaDiDataVinceLaCorrenteDelSopravvissuto() {
    val a = single(full(1), nas("a.jpg"), id = low)
    val b = single(full(1), nas("b.jpg"), locationId = 2, id = high)
    val plan = MergeRules.plan(a, b, ME) as MergePlan.Merge
    assertThat(plan.merged.currentVersionId).isEqualTo(a.currentVersionId)
    assertThat(plan.merged.versions).hasSize(1)
  }

  @Test
  fun traDueVettoriVinceQuelloCalcolatoDallaVersioneCorrente() {
    val space = EmbeddingSpace(EmbeddingSpace.EMBEDDING_GEMMA2_FLOAT_V1, 4, VectorPrecision.FLOAT32)
    val current = VersionId.random()
    val stale = EmbeddingVector.ofFloats(space, 0, floatArrayOf(1f, 0f, 0f, 0f), VersionId.random())
    val fresh = EmbeddingVector.ofFloats(space, 0, floatArrayOf(0f, 1f, 0f, 0f), current)

    assertThat(MergeRules.chooseVector(stale, fresh, current)).isEqualTo(fresh)
    assertThat(MergeRules.chooseVector(fresh, stale, current)).isEqualTo(fresh)
    assertThat(MergeRules.chooseVector(stale, stale, current)).isEqualTo(stale)
  }
}
