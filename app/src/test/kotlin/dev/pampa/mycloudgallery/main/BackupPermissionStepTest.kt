package dev.pampa.mycloudgallery.main

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `BackupHostEffect` ha lo stesso difetto che GA-4 ha tolto alla Libreria: il ricordo "ho gia' chiesto il permesso" stava in `remember`, quindi
 * dopo una rotazione o un riavvio del processo il primo tocco su "Consenti" della pagina Backup lanciava un dialogo che Android scarta in
 * silenzio (permesso negato per sempre): il tasto non faceva niente. Ora il ricordo sta su file (`PermissionAsks`) e il passo di un tocco e' una
 * decisione pura.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupPermissionStepTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val media = listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")

  @Test
  fun dopoUnRiavvioIlPrimoTocco_conIlPermessoNegatoPerSempre_mandaAlleImpostazioni() {
    // La prima richiesta: il dialogo si apre e si ricorda di averlo aperto.
    val before = PermissionAsks(context)
    assertThat(permissionStep(media, partialUpgrade = false, asks = before, granted = emptyList()) { false }).isEqualTo(PermissionStep.ASK)
    before.markAsked(media)

    // Un processo nuovo (rotazione, riavvio): Android non propone piu' di spiegare, il dialogo non si aprirebbe.
    val after = PermissionAsks(context)
    assertThat(permissionStep(media, partialUpgrade = false, asks = after, granted = emptyList()) { false }).isEqualTo(PermissionStep.SETTINGS)
  }

  @Test
  fun chiHaRifiutatoUnaVoltaSola_ilDialogoSiApreAncora() {
    PermissionAsks(context).markAsked(media)
    assertThat(permissionStep(media, partialUpgrade = false, asks = PermissionAsks(context), granted = emptyList()) { true }).isEqualTo(PermissionStep.ASK)
  }

  @Test
  fun senzaNienteDaConcedereIlTocco_SoloRileggeLAccesso() {
    assertThat(permissionStep(emptyList(), partialUpgrade = false, asks = PermissionAsks(context), granted = media) { false }).isEqualTo(PermissionStep.GRANTED)
    // Con "foto selezionate" il permesso c'e' ma "tutto" no: il dialogo si riapre.
    assertThat(permissionStep(emptyList(), partialUpgrade = true, asks = PermissionAsks(context), granted = media) { false }).isEqualTo(PermissionStep.ASK)
  }

  @Test
  fun unPermessoConcessoEPoiTolto_nonRestaNegatoPerSempre() {
    val asks = PermissionAsks(context)
    asks.markAsked(media)
    // Uno dei due e' concesso adesso (il ricordo si toglie: se lo ritira, il dialogo di sistema puo' tornare ad aprirsi).
    permissionStep(listOf(media[1]), partialUpgrade = false, asks = asks, granted = listOf(media[0])) { false }

    assertThat(PermissionAsks(context).asked(media[0])).isFalse()
    assertThat(PermissionAsks(context).asked(media[1])).isTrue()
  }

  @Test
  fun senzaUnActivityNonSiSaSeIlDialogoSiApre_SiProva() {
    PermissionAsks(context).markAsked(media)
    assertThat(permissionStep(media, partialUpgrade = false, asks = PermissionAsks(context), granted = emptyList()) { null }).isEqualTo(PermissionStep.ASK)
  }
}
