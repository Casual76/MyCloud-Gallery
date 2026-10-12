package dev.pampa.mycloudgallery.core.model.domain

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fixtures.T0
import dev.pampa.mycloudgallery.core.model.Fixtures.full
import dev.pampa.mycloudgallery.core.model.Fixtures.located
import dev.pampa.mycloudgallery.core.model.Fixtures.media
import dev.pampa.mycloudgallery.core.model.Fixtures.nas
import dev.pampa.mycloudgallery.core.model.Fixtures.original
import dev.pampa.mycloudgallery.core.model.Fixtures.phone
import dev.pampa.mycloudgallery.core.model.Fixtures.stamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.WorkStage
import org.junit.Assert.assertThrows
import org.junit.Test

class VersionRulesTest {

  /** Un media del telefono gia' salvato sul NAS: posizione 1 telefono, 2 NAS, entrambe sull'originale. */
  private fun backedUp(): Media {
    val v1 = original(full(1))
    return media(
      listOf(v1),
      listOf(located(1, v1, phone("a.jpg", mediaStoreId = 10)), located(2, v1, nas("Backup/a.jpg"), verifiedAt = T0)),
    )
  }

  @Test
  fun unaModificaSulTelefonoCreaUnaVersioneNuovaSenzaToccareLaCopiaSulNas() {
    val media = backedUp()
    val v1 = media.currentVersionId

    val plan = VersionRules.contentChangedAt(media, LocationId(1), full(2), FileStamp(full(2).size, T0 + 1_000), nowMillis = T0 + 2_000)

    val after = plan.media
    assertThat(after.id).isEqualTo(media.id)
    assertThat(plan.newVersion!!.kind).isEqualTo(VersionKind.EDITED)
    assertThat(plan.newVersion!!.parentId).isEqualTo(v1)
    assertThat(after.currentVersionId).isEqualTo(plan.newVersion!!.id)
    assertThat(after.location(LocationId(1)).versionId).isEqualTo(plan.newVersion!!.id)
    assertThat(after.location(LocationId(1)).verifiedAtMillis).isNull()
    // L'originale resta sul NAS, verificato.
    assertThat(after.location(LocationId(2)).versionId).isEqualTo(v1)
    assertThat(after.location(LocationId(2)).verifiedAtMillis).isEqualTo(T0)
    assertThat(plan.stagesToRedo).containsExactlyElementsIn(WorkStage.entries)
  }

  /** E-E1: piu' modifiche successive conservano originale e ultima; le intermedie sono opzionali. */
  @Test
  fun piuModificheSuccessiveConservanoOriginaleEUltimaVersione() {
    val first = VersionRules.contentChangedAt(backedUp(), LocationId(1), full(2), stamp(full(2), T0 + 1), T0 + 1).media
    val second = VersionRules.contentChangedAt(first, LocationId(1), full(3), stamp(full(3), T0 + 2), T0 + 2).media

    assertThat(second.versions).hasSize(3)
    assertThat(second.originalVersion.fingerprint).isEqualTo(full(1))
    assertThat(second.currentVersion.fingerprint).isEqualTo(full(3))
    assertThat(VersionRules.intermediateVersions(second).map { it.fingerprint }).containsExactly(full(2))
    assertThat(second.currentVersion.parentId).isEqualTo(VersionRules.intermediateVersions(second).single().id)
  }

  /** E-L8: correggere la data non cambia i pixel, quindi miniatura, embedding e OCR restano validi. */
  @Test
  fun unaCorrezioneDiDataRifaSoloIMetadati() {
    val media = backedUp()
    val plan = VersionRules.corrected(
      media,
      setOf(CorrectionField.DATE),
      full(4),
      holders = mapOf(LocationId(2) to stamp(full(4), T0 + 9)),
      nowMillis = T0 + 9,
    )

    assertThat(plan.newVersion!!.kind).isEqualTo(VersionKind.CORRECTED)
    assertThat(plan.newVersion!!.corrections).containsExactly(CorrectionField.DATE)
    assertThat(plan.stagesToRedo).containsExactly(WorkStage.META)
    assertThat(plan.media.location(LocationId(2)).versionId).isEqualTo(plan.newVersion!!.id)
    // Il telefono non e' stato riscritto: resta sull'originale, che resta conservato.
    assertThat(plan.media.location(LocationId(1)).versionId).isEqualTo(media.originalVersion.id)
  }

  @Test
  fun unaRotazioneCambiaIPixelERifaTutto() {
    val plan = VersionRules.corrected(backedUp(), setOf(CorrectionField.ROTATION, CorrectionField.DATE), full(4), emptyMap(), T0)
    assertThat(plan.stagesToRedo).containsExactlyElementsIn(WorkStage.entries)
  }

  @Test
  fun dueCorrezioniDiMetadatiDiFilaRestanoSoloMetadati() {
    val dated = VersionRules.corrected(backedUp(), setOf(CorrectionField.DATE), full(4), emptyMap(), T0 + 1).media
    val placed = VersionRules.corrected(dated, setOf(CorrectionField.LOCATION), full(5), emptyMap(), T0 + 2).media

    assertThat(VersionRules.stagesToRedo(placed, placed.originalVersion.id, placed.currentVersionId)).containsExactly(WorkStage.META)
    assertThat(VersionRules.stagesToRedo(placed, placed.currentVersionId, placed.originalVersion.id)).containsExactly(WorkStage.META)
  }

  @Test
  fun ripristinaOriginaleRendeCorrenteLOriginaleSenzaPerdereLaModifica() {
    val edited = VersionRules.contentChangedAt(backedUp(), LocationId(1), full(2), stamp(full(2), T0 + 1), T0 + 1).media

    val plan = VersionRules.restoreOriginal(edited)

    assertThat(plan.media.currentVersionId).isEqualTo(edited.originalVersion.id)
    assertThat(plan.media.versions).hasSize(2)
    assertThat(plan.changedLocations).isEmpty()
    assertThat(plan.stagesToRedo).containsExactlyElementsIn(WorkStage.entries)
  }

  @Test
  fun tornareAUnaVersioneNotaNonCreaVersioniNuove() {
    val edited = VersionRules.contentChangedAt(backedUp(), LocationId(1), full(2), stamp(full(2), T0 + 1), T0 + 1).media
    val plan = VersionRules.revertedAt(edited, LocationId(1), edited.originalVersion.id, stamp(full(1), T0 + 5), T0 + 5)

    assertThat(plan.newVersion).isNull()
    assertThat(plan.media.versions).hasSize(2)
    assertThat(plan.media.currentVersionId).isEqualTo(edited.originalVersion.id)
    assertThat(plan.media.location(LocationId(1)).versionId).isEqualTo(edited.originalVersion.id)
  }

  /**
   * E-E7: "Scarica sul telefono" mette una copia in `Pictures/MyCloud Gallery` come posizione del media
   * del NAS (proposta 8). Modificarla crea una versione di **quel** media, non un nuovo backup.
   */
  @Test
  fun modificareLaCopiaScaricataCreaUnaVersioneDelMediaDelNas() {
    val v1 = original(full(1))
    val fromNas = media(
      listOf(v1),
      listOf(located(1, v1, nas("Foto/a.jpg")), located(2, v1, phone("a.jpg", mediaStoreId = 50, path = "Pictures/MyCloud Gallery/"))),
    )

    val plan = VersionRules.contentChangedAt(fromNas, LocationId(2), full(2), stamp(full(2), T0 + 1), T0 + 1)

    assertThat(plan.media.id).isEqualTo(fromNas.id)
    assertThat(plan.media.versions).hasSize(2)
    assertThat(plan.media.location(LocationId(1)).versionId).isEqualTo(v1.id)
  }

  @Test
  fun unaCorrezioneSenzaCampiERifiutata() {
    assertThrows(IllegalArgumentException::class.java) {
      VersionRules.corrected(backedUp(), emptySet(), full(4), emptyMap(), T0)
    }
  }
}
