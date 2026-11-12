package dev.pampa.mycloudgallery.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.FencedFlags
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 8, P13 (§1.4 "Cancelli", D34, D35): `FacesWriteGate` = la recinzione D34 (`facesSyncMayUse`) + `ShareFence` (solo debug) + i cancelli dell'indice
 * (`IndexWriteRules`: emergenza, fiducia, congelamento, salto dell'ora, sola lettura). Con la recinzione accesa D34 viene **prima di tutto**: su una share
 * vera nessun altro cancello puo' dire "si puo'" (e nemmeno chiede l'account o il NAS).
 *
 * **Dal passo unico del 12/11/2026** D34 e' spenta in `FeatureFlags`: le prove di D34 usano flag con la recinzione accesa ([FencedFlags], solo nelle
 * prove); `passoUnico_*` usa i valori di release di adesso (una share vera passa, gli altri cancelli restano).
 */
class AppFacesWriteGateTest {

  /** La release com'era prima del passo unico: D34 accesa. */
  private val release = FencedFlags.of()
  private val open = IndexWriteGate { null }

  private fun gate(
    flags: FeatureFlags = release,
    guard: ShareGuard? = null,
    user: String? = "test",
    index: IndexWriteGate? = open,
  ) = AppFacesWriteGate(flags, guard, { user }, { index })

  @Test
  fun inReleaseUnaShareVeraRispondeSoloLeShareDiProvaPassano() = runBlocking<Unit> {
    val gate = gate()
    for (share in listOf("famiglia", "alessio", "pampa", "nas", "public", "NAS", "test ", "ｔｅｓｔ")) {
      assertThat(gate.blockFor(share)).isEqualTo(FacesWriteBlock.TEST_SHARES_ONLY)
    }
    for (share in listOf("test", "test2", "test-comune", "TEST")) {
      assertThat(gate.blockFor(share)).isNull()
    }
  }

  @Test
  fun laRecinzioneVieneAnchePrimaDelNasEDellAccount() = runBlocking<Unit> {
    // Senza account e senza cancelli dell'indice una share vera dice comunque TEST_SHARES_ONLY (D34), una share di prova dice NO_NAS.
    val blind = gate(user = null, index = null)
    assertThat(blind.blockFor("famiglia")).isEqualTo(FacesWriteBlock.TEST_SHARES_ONLY)
    assertThat(blind.blockFor("test")).isEqualTo(FacesWriteBlock.NO_NAS)
    assertThat(gate(index = null).blockFor("test")).isEqualTo(FacesWriteBlock.NO_NAS)
  }

  @Test
  fun laRecinzioneDelleProveSulCampoVieneDopoD34EPrimaDeiCancelliDellIndice() = runBlocking<Unit> {
    val deny = ShareGuard { _, _ -> false }
    val allow = ShareGuard { _, _ -> true }
    assertThat(gate(guard = deny).blockFor("test")).isEqualTo(FacesWriteBlock.FENCE)
    assertThat(gate(guard = deny).blockFor("famiglia")).isEqualTo(FacesWriteBlock.TEST_SHARES_ONLY)
    assertThat(gate(guard = allow).blockFor("test")).isNull()
    // La recinzione D33 chiede l'account che scrive: la riceve com'e'.
    var asked: Pair<String, String>? = null
    val spy = ShareGuard { user, share ->
      asked = user to share
      true
    }
    gate(guard = spy, user = "test2").blockFor("test2")
    assertThat(asked).isEqualTo("test2" to "test2")
  }

  @Test
  fun iCancelliDellIndiceSiTraducono() = runBlocking<Unit> {
    val expected = mapOf(
      WriteBlock.FROZEN to FacesWriteBlock.FROZEN,
      WriteBlock.CLOCK_JUMP to FacesWriteBlock.CLOCK_JUMP,
      WriteBlock.READ_ONLY_AREA to FacesWriteBlock.READ_ONLY,
      WriteBlock.EMERGENCY_STOP to FacesWriteBlock.EMERGENCY_STOP,
      WriteBlock.NOT_TRUSTED to FacesWriteBlock.NOT_TRUSTED,
    )
    assertThat(expected.keys + WriteBlock.LOCAL_ONLY).containsExactlyElementsIn(WriteBlock.entries)
    for ((block, want) in expected) assertThat(gate(index = IndexWriteGate { block }).blockFor("test")).isEqualTo(want)
  }

  @Test
  fun f4D31LIndiceSoloLocaleNonFermaIVolti() = runBlocking<Unit> {
    // L'indice "solo locale" sulle share vere e' un'altra recinzione (F4-D31): per i volti decide D34, gia' passata.
    assertThat(gate(index = IndexWriteGate { WriteBlock.LOCAL_ONLY }).blockFor("test")).isNull()
  }

  @Test
  fun laCancellazioneStrettaRiceveLoStessoCancelloConUnArrestoPerOgniBlocco() = runBlocking<Unit> {
    val expected = mapOf(
      null to null,
      FacesWriteBlock.FROZEN to FacesEraseStop.FROZEN,
      FacesWriteBlock.EMERGENCY_STOP to FacesEraseStop.EMERGENCY_STOP,
      FacesWriteBlock.CLOCK_JUMP to FacesEraseStop.CLOCK_JUMP,
      FacesWriteBlock.NOT_TRUSTED to FacesEraseStop.NOT_TRUSTED,
      FacesWriteBlock.READ_ONLY to FacesEraseStop.READ_ONLY,
      FacesWriteBlock.TEST_SHARES_ONLY to FacesEraseStop.TEST_SHARES_ONLY,
      FacesWriteBlock.FENCE to FacesEraseStop.TEST_SHARES_ONLY,
      FacesWriteBlock.NO_PRIVATE_AREA to FacesEraseStop.NOT_PRIVATE_AREA,
      FacesWriteBlock.NO_NAS to FacesEraseStop.UNREACHABLE,
    )
    assertThat(expected.keys.filterNotNull()).containsExactlyElementsIn(FacesWriteBlock.entries)
    for ((block, stop) in expected) {
      val erase = FacesWriteGate { block }.asEraseGate()
      assertThat(erase("test")).isEqualTo(stop)
    }
  }

  @Test
  fun passoUnico_laRecinzioneESpentaInReleaseEInDebug() {
    // Una costante, non un parametro: nessuna build la cambia da fuori (l'ha spenta l'utente, a mano, con il passo unico del 12/11/2026).
    assertThat(FeatureFlags.RELEASE.facesSyncTestSharesOnly).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = true).facesSyncTestSharesOnly).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = false).facesSyncTestSharesOnly).isFalse()
    assertThat(FeatureFlags.RELEASE.facesSyncMayUse("famiglia")).isTrue()
    assertThat(FeatureFlags.RELEASE.facesSyncMayUse("test")).isTrue()
  }

  @Test
  fun passoUnico_conLaReleaseDiAdessoUnaShareVeraPassaEGliAltriCancelliRestano() = runBlocking<Unit> {
    val now = FeatureFlags.RELEASE
    for (share in listOf("alessio", "famiglia", "pampa")) assertThat(gate(flags = now, user = "alessio").blockFor(share)).isNull()
    assertThat(gate(flags = now, user = null, index = null).blockFor("alessio")).isEqualTo(FacesWriteBlock.NO_NAS)
    assertThat(gate(flags = now, user = "alessio", index = IndexWriteGate { WriteBlock.EMERGENCY_STOP }).blockFor("alessio")).isEqualTo(FacesWriteBlock.EMERGENCY_STOP)
    assertThat(gate(flags = now, user = "alessio", index = IndexWriteGate { WriteBlock.FROZEN }).blockFor("alessio")).isEqualTo(FacesWriteBlock.FROZEN)
    assertThat(gate(flags = now, user = "alessio", index = IndexWriteGate { WriteBlock.NOT_TRUSTED }).blockFor("alessio")).isEqualTo(FacesWriteBlock.NOT_TRUSTED)
  }
}
