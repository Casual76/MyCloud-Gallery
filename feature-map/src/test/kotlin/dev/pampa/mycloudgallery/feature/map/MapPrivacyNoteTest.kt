package dev.pampa.mycloudgallery.feature.map

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapPrivacyNote
import dev.pampa.mycloudgallery.feature.map.contract.MapPrivacyState
import dev.pampa.mycloudgallery.feature.map.privacy.MapPrivacyScreen
import dev.pampa.mycloudgallery.feature.map.privacy.NoMapPrivacyHandlers
import org.junit.Test

/**
 * R1 della revisione di sicurezza della Fase 9 (31/10), una prova sulla stringa (come `AboutScreenTest` per le licenze): la nota di Impostazioni -> Mappa **dice il vero** su cosa riceve OpenFreeMap.
 * Riceve l'indirizzo della connessione e la zona guardata, **anche quella attorno a una foto** quando la si apre sulla mappa ("Mostra sulla mappa") o ci si avvicina alle sue foto; non riceve mai le foto,
 * le coordinate esatte o quante sono. Finche' le zone senza Internet (P5) non ci sono, la nota delle zone dice la sola difesa che c'e': spegnere la mappa dettagliata prima di aprirla.
 */
internal class MapPrivacyNoteTest : MapScreenTest() {

  private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(id)

  @Test
  fun laNotaDiceCheLaZonaAttornoAUnaFotoAperta_SullaMappa_VaAOpenFreeMap() {
    val note = string(R.string.mappa_settings_note)
    assertThat(note).contains("OpenFreeMap riceve l’indirizzo della tua connessione e la zona che stai guardando")
    assertThat(note).contains("anche quella attorno a una foto")
    assertThat(note).contains("“Mostra sulla mappa”")
    assertThat(note).contains("ti avvicini alle sue foto")
    assertThat(note).contains("Non riceve mai le tue foto, le coordinate esatte né quante sono")
    assertThat(note).contains("La scelta vale solo su questo dispositivo")
    // La frase che non era vera non c'e' piu'.
    assertThat(note).doesNotContain("né la loro posizione")
    assertThat(note).doesNotContain("Non riceve le tue foto")
  }

  @Test
  fun laNotaDelleZoneConsigliaLaZonaScaricataEDiceIlLimiteVero() {
    val zones = string(R.string.mappa_settings_zones_note)
    // P5: la frase dell'utente, e quello che e' vero oltre: sopra il livello scaricato (z12 per le regioni) le tessere piu' precise si chiedono ancora.
    assertThat(zones).isEqualTo("Per non far sapere una zona, scaricala prima di aprirla, oppure spegni la mappa dettagliata. Una zona scaricata non si chiede più a OpenFreeMap, ma se ti avvicini oltre il suo dettaglio le tessere più precise sì.")
    assertThat(zones).startsWith("Per non far sapere una zona, scaricala prima di aprirla, oppure spegni la mappa dettagliata.")
    assertThat(zones).contains("non si chiede più a OpenFreeMap")
    assertThat(zones).contains("oltre il suo dettaglio")
    // La frase di prima (senza zone scaricate) non c'e' piu'.
    assertThat(zones).doesNotContain("spegni la mappa dettagliata prima di aprirla")
  }

  @Test
  fun laSceltaRovinataHaLaSuaRiga() {
    assertThat(string(R.string.mappa_settings_damaged)).isEqualTo("La scelta si era rovinata: la mappa dettagliata è spenta finché non la riaccendi.")
  }

  @Test
  fun laPaginaMostraLaNotaGiustaEDiceDellaSceltaRovinataSoloQuandoServe() {
    val state = androidx.compose.runtime.mutableStateOf(MapPrivacyState.Shown(MapOnline.On(byDefault = true), true, MapPrivacyNote.VERSION, 0))
    showAll { MapPrivacyScreen(state.value, NoMapPrivacyHandlers, Modifier) }
    assertShows("nota", "“Mostra sulla mappa”", "anche quella attorno a una foto", "scaricala prima di aprirla, oppure spegni la mappa dettagliata")
    assertDoesNotShow("nota", "La scelta si era rovinata", "né la loro posizione")
    composeRule.runOnUiThread { state.value = state.value.copy(online = MapOnline.Off, damaged = true) }
    composeRule.waitForIdle()
    assertShows("rovinata", "La scelta si era rovinata: la mappa dettagliata è spenta finché non la riaccendi.")
    // Riaccesa dall'utente: la riga sparisce.
    composeRule.runOnUiThread { state.value = state.value.copy(online = MapOnline.On(byDefault = false), damaged = false) }
    composeRule.waitForIdle()
    assertDoesNotShow("riaccesa", "La scelta si era rovinata")
  }
}
