package dev.pampa.mycloudgallery.core.backup.settings

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import org.junit.Test

/** La cartella del dispositivo (Fase 5A, P4; D7): E-B6 (due dispositivi, un nome gia' presente), E-B16 (reinstallazione), E-N6 (nomi). */
class DeviceFolderPolicyTest {

  private fun decide(name: String, existing: List<String> = emptyList(), other: Boolean = false) =
    DeviceFolderPolicy.decide(name, existing, usedByOtherDevice = other)

  // --- Il nome ripulito (E-N6) ---------------------------------------------------------------------------------------------------

  @Test
  fun ilNomeDelDispositivo_diventaLaCartella_ripulitoPerSharePath() {
    assertThat(DeviceFolderPolicy.folderFor("S25 di Mario").name).isEqualTo("S25 di Mario")
    assertThat(DeviceFolderPolicy.folderFor("Il telefono di Zoë 📱").name).isEqualTo("Il telefono di Zoë 📱")
    assertThat(DeviceFolderPolicy.folderFor("  Tab: S9 \n del salotto ").name).isEqualTo("Tab_ S9 del salotto")
    assertThat(DeviceFolderPolicy.folderFor("CON").name).isEqualTo("_CON")
    assertThat(DeviceFolderPolicy.folderFor("S25.").name).isEqualTo("S25")
    assertThat(DeviceFolderPolicy.folderFor("").name).isEqualTo(DeviceFolderPolicy.FALLBACK_NAME)
    assertThat(DeviceFolderPolicy.folderFor("   ").name).isEqualTo(DeviceFolderPolicy.FALLBACK_NAME)
    assertThat(DeviceFolderPolicy.folderFor("....").name).isEqualTo(DeviceFolderPolicy.FALLBACK_NAME)
    val long = DeviceFolderPolicy.folderFor("x".repeat(500)).name
    assertThat(SharePath.isValidComponent(long)).isTrue()
    assertThat(long.toByteArray().size).isAtMost(TrustLimits.MAX_COMPONENT_UTF8_BYTES)
  }

  // --- Nome libero, nome gia' presente ------------------------------------------------------------------------------------------

  @Test
  fun unNomeLibero_siCreaESiUsa() {
    val decision = decide("S25 di Mario", existing = listOf("Tab S9 di Anna", "Backup vecchio"))

    assertThat(decision).isEqualTo(FolderDecision.Create(DeviceFolder("S25 di Mario")))
    assertThat(decide("S25 di Mario")).isEqualTo(FolderDecision.Create(DeviceFolder("S25 di Mario")))
  }

  @Test
  fun unNomeGiaPresente_dopoUnaReinstallazione_siProponeDiContinuareLi_E_B16() {
    val decision = decide("S25 di Mario", existing = listOf("Tab S9 di Anna", "S25 di Mario"))

    decision as FolderDecision.AskExisting
    assertThat(decision.existing).isEqualTo(DeviceFolder("S25 di Mario"))
    assertThat(decision.newFolder).isEqualTo(DeviceFolder("S25 di Mario (2)"))
    assertThat(decision.suggested).isEqualTo(FolderChoice.CONTINUE_THERE)
  }

  @Test
  fun unNomeGiaUsatoDaUnAltroDispositivoDelloStessoAccount_siChiede_conNuovaCartellaPreselezionata_E_B6() {
    val decision = decide("S25 di Mario", existing = listOf("S25 di Mario"), other = true)

    decision as FolderDecision.AskExisting
    assertThat(decision.suggested).isEqualTo(FolderChoice.NEW_FOLDER)
    assertThat(decision.existing).isEqualTo(DeviceFolder("S25 di Mario"))
    assertThat(decision.newFolder).isEqualTo(DeviceFolder("S25 di Mario (2)"))
  }

  @Test
  fun leMaiuscoleNonContano_comeSuSmb_eSiContinuaConLaGrafiaCheCeSulNas() {
    val decision = decide("S25 di Mario", existing = listOf("s25 DI mario"))

    decision as FolderDecision.AskExisting
    assertThat(decision.existing.name).isEqualTo("s25 DI mario")
  }

  @Test
  fun iNomiDiConfusione_nonFannoUnaSecondaCartella() {
    // Cifre a larghezza piena: stessa cartella per chi la guarda (e per le forme di compatibilita').
    val decision = decide("S25 di Mario", existing = listOf("S２５ di Mario"))

    decision as FolderDecision.AskExisting
    assertThat(decision.existing.name).isEqualTo("S２５ di Mario")
  }

  @Test
  fun ilNuovoNomeEIlPrimoLibero() {
    val decision = decide("S25", existing = listOf("S25", "S25 (2)", "s25 (3)"))

    decision as FolderDecision.AskExisting
    assertThat(decision.newFolder.name).isEqualTo("S25 (4)")
  }

  @Test
  fun ilNomeConIlSuffissoRestaEntroIlLimite() {
    val base = "é".repeat(127) // 254 byte
    val decision = decide(base, existing = listOf(base))

    decision as FolderDecision.AskExisting
    assertThat(decision.newFolder.name).endsWith(" (2)")
    assertThat(decision.newFolder.name.toByteArray().size).isAtMost(TrustLimits.MAX_COMPONENT_UTF8_BYTES)
  }

  @Test
  fun ilNomeDellaCartellaDelGuardianoNonEUnDispositivo() {
    // "Backup/Area comune/" e' del guardiano (D9): un dispositivo con quel nome ne prende un altro, anche se la cartella non esiste ancora.
    assertThat(decide("Area comune")).isEqualTo(FolderDecision.Create(DeviceFolder("Area comune (2)")))
    assertThat(decide("area COMUNE", existing = listOf("Area comune"))).isEqualTo(FolderDecision.Create(DeviceFolder("area COMUNE (2)")))
    // E il suo nome non si propone mai come nuova cartella.
    val decision = decide("Area comune (2)", existing = listOf("Area comune (2)"))
    decision as FolderDecision.AskExisting
    assertThat(decision.newFolder.name).isEqualTo("Area comune (2) (2)")
  }

  @Test
  fun unaCartellaDelNasCheSharePathNonAccetta_nonSiContinua_maNonFaDanni() {
    val hostile = "S25 di Mario\u0000"
    // Non e' la stessa cartella (il nome ripulito non ha il carattere di controllo): si crea quella nostra.
    assertThat(decide("S25 di Mario", existing = listOf(hostile))).isEqualTo(FolderDecision.Create(DeviceFolder("S25 di Mario")))
  }
}
