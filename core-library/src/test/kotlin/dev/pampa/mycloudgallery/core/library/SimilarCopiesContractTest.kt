package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.noop.NoSimilarCopies
import dev.pampa.mycloudgallery.core.library.similar.ChoiceOutcome
import dev.pampa.mycloudgallery.core.library.similar.CopyPlace
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesGroup
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesProgress
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopy
import dev.pampa.mycloudgallery.core.library.testing.FakeSimilarCopies
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 5 (P0): i contratti di core-library per il backup e le copie simili. Il null-object non unisce niente e non fa aspettare il
 * riepilogo del primo backup; il fake applica "Mostra questa come principale" e "Separa" come il rilevatore vero (P15); lo stato del
 * piede porta solo codici e numeri.
 */
class SimilarCopiesContractTest {

  private fun copy(id: MediaId, place: CopyPlace, width: Int) =
    SimilarCopy(id, MediaKind.PHOTO, setOf(place), width, width * 3 / 4, bytes = 1_000_000L, mime = "image/jpeg", bitrate = null, cameraOriginal = place == CopyPlace.PHONE)

  @Test
  fun ilNullObjectNonUnisceNienteENonFaAspettare() = runTest {
    assertThat(NoSimilarCopies.progress.value.settled).isTrue()
    assertThat(NoSimilarCopies.groupOf(MediaId.random())).isNull()
    assertThat(NoSimilarCopies.separate(MediaId.random())).isEqualTo(ChoiceOutcome.NOT_IN_GROUP)
  }

  @Test
  fun copertinaSceltaESeparaSiApplicanoAlGruppo() = runTest {
    val phone = MediaId.random()
    val cloud = MediaId.random()
    val old = MediaId.random()
    val fake = FakeSimilarCopies(SimilarCopiesProgress(pending = 120, running = true))
    assertThat(fake.progress.value.settled).isFalse()
    fake.put(
      SimilarCopiesGroup(
        MediaGroupId.random(), cover = phone,
        copies = listOf(copy(phone, CopyPlace.PHONE, 4000), copy(cloud, CopyPlace.NAS_PRIVATE, 2048), copy(old, CopyPlace.NAS_PRIVATE, 1024)),
        coverChosenByUser = false, hidden = false,
      ),
    )
    assertThat(fake.chooseCover(cloud)).isEqualTo(ChoiceOutcome.APPLIED)
    assertThat(fake.groupOf(old)!!.cover).isEqualTo(cloud)
    assertThat(fake.groupOf(old)!!.coverChosenByUser).isTrue()
    assertThat(fake.separate(old)).isEqualTo(ChoiceOutcome.APPLIED)
    assertThat(fake.groupOf(old)).isNull()
    // Un gruppo rimasto con una copia si scioglie.
    assertThat(fake.separate(cloud)).isEqualTo(ChoiceOutcome.APPLIED)
    assertThat(fake.groupOf(phone)).isNull()
    fake.run(1.minutes)
    assertThat(fake.progress.value.settled).isTrue()
    assertThrows(IllegalArgumentException::class.java) {
      SimilarCopiesGroup(MediaGroupId.random(), phone, listOf(copy(phone, CopyPlace.PHONE, 10)), coverChosenByUser = false, hidden = false)
    }
  }

  @Test
  fun loStatoDelPiedePortaSoloCodiciENumeri() {
    assertThat(LibraryBackupStatus.Problem("verify_failed", 3).code).isEqualTo("verify_failed")
    assertThrows(IllegalArgumentException::class.java) { LibraryBackupStatus.Problem("Backup/S25/IMG_1.jpg", 1) }
    assertThrows(IllegalArgumentException::class.java) { LibraryBackupStatus.Problem("quota", -1) }
  }
}
