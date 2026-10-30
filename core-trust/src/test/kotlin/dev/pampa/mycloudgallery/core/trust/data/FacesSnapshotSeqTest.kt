package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import org.junit.Test

/**
 * R4 (correzioni della Fase 8, pacchetto "formato e replica dei volti"): un'istantanea con la `seq` oltre [TrustLimits.MAX_SNAPSHOT_SEQ] non vale, ne'
 * per chi legge ne' per chi scrive (stessa regola, `FacesDocs.checkSnapshot`); la testa che la dichiara resta leggibile (il segno della testa ammette
 * ancora fino a `MAX_VERSION`: le app della Fase 8 leggono tutto cio' che scrivono le app di oggi).
 */
class FacesSnapshotSeqTest {
  private val person = ClusterEntry.Person(
    PersonId.fromBytes(ByteArray(16) { 3 }), ClusterPersonState.ACTIVE, null, 1, 1, 0, ShortArray(FacesLimits.DIMENSION), emptyList(),
  )

  @Test
  fun r4_unaSeqOltreIlTettoNonVale() {
    val near = SnapshotMark(1_000_000_000L, 999_999_999L)
    val verdict = FacesDocs.checkSnapshot(near, listOf(person))
    assertThat(verdict).isInstanceOf(Verdict.Rejected::class.java)
    assertThat((verdict as Verdict.Rejected).reason).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(FacesDocs.checkSnapshot(SnapshotMark(TrustLimits.MAX_SNAPSHOT_SEQ + 1, 1), listOf(person))).isInstanceOf(Verdict.Rejected::class.java)
  }

  @Test
  fun r4_sottoIlTettoVale() {
    assertThat(FacesDocs.checkSnapshot(SnapshotMark(TrustLimits.MAX_SNAPSHOT_SEQ, 1), listOf(person))).isInstanceOf(Verdict.Accepted::class.java)
    assertThat(FacesDocs.checkSnapshot(SnapshotMark(2, 1), listOf(person))).isInstanceOf(Verdict.Accepted::class.java)
  }

  @Test
  fun r4_ilTettoStaMoltoSottoIlTettoDeiNumeri() {
    assertThat(TrustLimits.MAX_SNAPSHOT_SEQ * 10).isAtMost(TrustLimits.MAX_VERSION)
  }
}
