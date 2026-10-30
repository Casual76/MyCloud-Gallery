package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import org.junit.Test

/**
 * R7 (correzioni della Fase 8, pacchetto "formato e replica dei volti"): la spazzatura messa dal PC nella **propria** `faces/<G>/<io>/head/` si toglie
 * (oltre 32 file di fila il proprietario non pubblicava piu' e chi arrivava non vedeva le sue teste), ma solo se e' stata letta e non e' una testa
 * valida, solo nella propria cartella della generazione corrente, e mai una testa che si sta scrivendo adesso (15 minuti di grazia).
 */
class FacesJunkHeadsTest {
  private val me = TrustIds.random()
  private val other = TrustIds.random()
  private val g = FaceGeneration.fromBytes(ByteArray(16) { 5 })
  private val old = FaceGeneration.fromBytes(ByteArray(16) { 6 })
  private val now = 1_800_000_000_000L
  private val policy = FacesOwnFilesPolicy(me, g)

  private fun head(v: Long, gen: FaceGeneration = g, device: String = me) = FacesOwnFile.Head(NasLayout.facesHead(gen, device, v), gen, device, v)

  private fun junk(file: FacesOwnFile.Head, at: Long?) = FacesOwnFileFacts.HeadFacts(file, signedByMe = false, ownVersions = setOf(1L), junk = true, modifiedAtNas = at)

  @Test
  fun r7_laSpazzaturaNellaMiaHeadSiToglieDopoLaGrazia() {
    val old = now - TrustLimits.OWN_FILES_JUNK_GRACE_MILLIS - 1
    assertThat(policy.decide(junk(head(40), old), now)).isEqualTo(OwnFileDecision.Remove)
    assertThat(policy.decide(junk(head(40), now - 60_000), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.TOO_RECENT))
    assertThat(policy.decide(junk(head(40), null), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.AGE_UNKNOWN))
    assertThat(policy.decide(junk(head(40), now + 10 * 60_000), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.TIME_IN_FUTURE))
  }

  @Test
  fun r7_maiFuoriDallaMiaCartellaDellaGenerazioneCorrenteEMaiSenzaLettura() {
    val old = now - 2 * TrustLimits.OWN_FILES_JUNK_GRACE_MILLIS
    assertThat(policy.decide(junk(head(40, device = other), old), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_MINE))
    assertThat(policy.decide(junk(head(40, gen = this.old), old), now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_MINE))
    // Un file non letto (oltre il tetto delle letture) non e' spazzatura: resta.
    val unread = FacesOwnFileFacts.HeadFacts(head(40), signedByMe = false, ownVersions = setOf(1L), junk = false, modifiedAtNas = old)
    assertThat(policy.decide(unread, now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_SIGNED_BY_ME))
    // Una mia testa valida segue le regole di M6, non quelle della spazzatura.
    val mine = FacesOwnFileFacts.HeadFacts(head(1), signedByMe = true, ownVersions = setOf(1L), junk = false, modifiedAtNas = old)
    assertThat(policy.decide(mine, now)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.RECENT_HEAD))
  }

  @Test
  fun r7_quarantaFileDiSpazzaturaSiTolgonoInUnGiro() {
    val facts = (2L..41L).map { junk(head(it), now - TrustLimits.OWN_FILES_JUNK_GRACE_MILLIS - 1) }
    val plan = policy.plan(facts, now, suspended = false)
    assertThat(plan.remove).hasSize(40)
  }
}
