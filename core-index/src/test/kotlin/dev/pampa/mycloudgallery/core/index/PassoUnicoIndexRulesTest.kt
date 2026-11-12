package dev.pampa.mycloudgallery.core.index

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * F4-D31 dopo il passo unico del 12/11/2026 (D35, par. 2.10 di docs/fase10-progetto.md): le regole di scrittura dell'indice ([IndexWriteRules])
 * con i valori di release di adesso lasciano pubblicare anche le share vere, e tutti gli altri cancelli restano (congelamento, salto dell'ora,
 * sola lettura, emergenza, fiducia). Con la recinzione accesa ([FencedFlags], solo nelle prove) una share vera torna "solo locale" come prima.
 */
class PassoUnicoIndexRulesTest {

  private val open = IndexWriteGate { null }

  @Test
  fun conLaReleaseDiAdessoLeShareVereSiPubblicano() = runBlocking<Unit> {
    assertThat(FeatureFlags.RELEASE.indexPublishTestSharesOnly).isFalse()
    val rules = IndexWriteRules(FeatureFlags.RELEASE, open)
    for (share in listOf("alessio", "Public", "famiglia", "test", "test-comune")) {
      assertWithMessage(share).that(rules.blockFor(share)).isNull()
      assertWithMessage(share).that(rules.permanentBlockFor(share)).isNull()
    }
  }

  @Test
  fun conLaReleaseDiAdessoGliAltriCancelliRestanoTutti() = runBlocking<Unit> {
    for (block in WriteBlock.entries.filter { it != WriteBlock.LOCAL_ONLY }) {
      val rules = IndexWriteRules(FeatureFlags.RELEASE, IndexWriteGate { block })
      assertWithMessage(block.name).that(rules.blockFor("alessio")).isEqualTo(block)
    }
    // La sola lettura resta un blocco che non passa da solo (niente outbox), le altre ragioni sono temporanee.
    assertThat(IndexWriteRules(FeatureFlags.RELEASE, IndexWriteGate { WriteBlock.READ_ONLY_AREA }).permanentBlockFor("alessio"))
      .isEqualTo(WriteBlock.READ_ONLY_AREA)
    assertThat(IndexWriteRules(FeatureFlags.RELEASE, IndexWriteGate { WriteBlock.EMERGENCY_STOP }).permanentBlockFor("alessio")).isNull()
  }

  @Test
  fun conLaRecinzioneAccesaUnaShareVeraRestaSoloLocale() = runBlocking<Unit> {
    val rules = IndexWriteRules(FencedFlags.of(), open)
    for (share in listOf("alessio", "Public", "famiglia", "test ", "ｔｅｓｔ")) {
      assertWithMessage(share).that(rules.blockFor(share)).isEqualTo(WriteBlock.LOCAL_ONLY)
      assertWithMessage(share).that(rules.permanentBlockFor(share)).isEqualTo(WriteBlock.LOCAL_ONLY)
    }
    for (share in listOf("test", "test2", "test-comune")) assertWithMessage(share).that(rules.blockFor(share)).isNull()
    // D31 viene prima del cancello: anche con un'emergenza una share vera dice "solo locale".
    assertThat(IndexWriteRules(FencedFlags.of(), IndexWriteGate { WriteBlock.EMERGENCY_STOP }).blockFor("alessio")).isEqualTo(WriteBlock.LOCAL_ONLY)
  }
}
