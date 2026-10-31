package dev.pampa.mycloudgallery.core.map.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto delle impostazioni della mappa (Fase 9, P0; `docs/fase9-progetto.md` D24, D29; **risposta Q1 (c) del 27/10**). Gira sul
 * riferimento e, da P4, sul DataStore vero (locale, mai sincronizzato).
 */
abstract class MapSettingsContract {

  /** Le impostazioni di un dispositivo nuovo, con l'interruttore di build [buildFlag]. */
  protected abstract fun settings(buildFlag: Boolean): MapSettings

  /** Q1 (c): accesa di default, senza pagina di consenso; "Solo sul Wi-Fi" acceso; nessuna camera salvata. */
  @Test
  fun accesaDiDefault() {
    val s = settings(buildFlag = true)
    assertThat(s.online.value).isEqualTo(MapOnline.On(byDefault = true))
    assertThat(s.wifiOnly.value).isTrue()
    assertThat(s.lastCamera.value).isNull()
  }

  /** D24: si spegne con un tocco e resta spenta; si riaccende solo con la scelta della pagina (non piu' "di default"). */
  @Test
  fun spegnereERiaccendere() = runTest {
    val s = settings(buildFlag = true)
    s.disable()
    assertThat(s.online.value).isEqualTo(MapOnline.Off)
    s.enable(MapOnlineChoice(MapPrivacyNote.VERSION, 1_000))
    assertThat(s.online.value).isEqualTo(MapOnline.On(byDefault = false))
  }

  /** L'interruttore di build spento: la mappa online non c'e', qualunque cosa si chieda. */
  @Test
  fun senzaLInterruttoreDiBuildNonCe() = runTest {
    val s = settings(buildFlag = false)
    assertThat(s.online.value).isEqualTo(MapOnline.Unavailable)
    s.enable(MapOnlineChoice(MapPrivacyNote.VERSION, 1_000))
    assertThat(s.online.value).isEqualTo(MapOnline.Unavailable)
  }

  /** D29: l'ultima camera solo in locale; "Solo sul Wi-Fi" si spegne. */
  @Test
  fun cameraEWifi() = runTest {
    val s = settings(buildFlag = true)
    val spot = CameraSpot(GeoE6(45_000_000, 9_000_000), 11.0)
    s.saveCamera(spot)
    assertThat(s.lastCamera.value).isEqualTo(spot)
    s.saveCamera(null)
    assertThat(s.lastCamera.value).isNull()
    s.setWifiOnly(false)
    assertThat(s.wifiOnly.value).isFalse()
    assertThat(MapCameraRules.FIRST_MAX_ZOOM).isEqualTo(5.0)
  }

  /** R5, D37: l'azzeramento svuota tutto (la mappa online vale "spenta", mai piu' "accesa") e da li' nessuna scrittura rimette niente. */
  @Test
  fun lAzzeramentoSvuotaETutteLeScrittureDopoSonoIgnorate() = runTest {
    val s = settings(buildFlag = true)
    s.saveCamera(CameraSpot(GeoE6(45_000_000, 9_000_000), 11.0))
    s.setWifiOnly(false)
    s.clear()
    assertThat(s.lastCamera.value).isNull()
    assertThat(s.wifiOnly.value).isTrue()
    assertThat(s.online.value).isEqualTo(MapOnline.Off)
    s.saveCamera(CameraSpot(GeoE6(46_000_000, 10_000_000), 12.0))
    s.setWifiOnly(false)
    s.enable(MapOnlineChoice(MapPrivacyNote.VERSION, 2_000))
    assertThat(s.lastCamera.value).isNull()
    assertThat(s.wifiOnly.value).isTrue()
    assertThat(s.online.value).isEqualTo(MapOnline.Off)
  }

  /** R2: di serie nessun problema col file (chi non ha un file, come il riferimento, non ne ha mai). */
  @Test
  fun diSerieNessunProblemaColFile() {
    assertThat(settings(buildFlag = true).problem.value).isEqualTo(MapSettingsProblem.NONE)
  }
}

class InMemoryMapSettingsContractTest : MapSettingsContract() {
  override fun settings(buildFlag: Boolean): MapSettings = InMemoryMapSettings(buildFlag)
}
