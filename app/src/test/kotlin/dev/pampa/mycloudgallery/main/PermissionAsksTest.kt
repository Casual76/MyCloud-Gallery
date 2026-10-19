package dev.pampa.mycloudgallery.main

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GA-4 (verifica funzionale): il primo tocco su "Consenti" non deve restare muto. Con il permesso negato per sempre Android non
 * apre piu' il dialogo, e la Libreria deve mandare alle Impostazioni: ma lo sa solo se ricorda di aver gia' chiesto. Il ricordo
 * stava in `remember` e si perdeva con una rotazione o con un riavvio del processo; ora sta su un file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PermissionAsksTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  private val media = listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")

  @Test
  fun ilRicordoDiAverChiestoSopravvivePiuProcessi() {
    val before = PermissionAsks(context)
    assertThat(media.map(before::asked)).containsExactly(false, false)
    before.markAsked(media)

    // Un processo nuovo (rotazione o riavvio): un oggetto nuovo sullo stesso file.
    val after = PermissionAsks(context)
    assertThat(media.map(after::asked)).containsExactly(true, true)
  }

  @Test
  fun dopoUnRiavvioIlPrimoTocco_conIlPermessoNegatoPerSempre_mandaAlleImpostazioni() {
    PermissionAsks(context).markAsked(media)
    val restarted = PermissionAsks(context)

    // Android non propone piu' di spiegare: il dialogo non si aprirebbe, quindi il tocco deve aprire le Impostazioni.
    assertThat(PermissionAsks.deniedForGood(media, restarted::asked) { false }).isTrue()
  }

  @Test
  fun senzaUnaRichiestaPrecedenteIlDialogoSiApre() {
    val never = PermissionAsks(context)

    // `shouldShowRequestPermissionRationale` e' falso anche a chi non ha mai chiesto: da solo non basta a dire "negato per sempre".
    assertThat(PermissionAsks.deniedForGood(media, never::asked) { false }).isFalse()
    // Chiesto una volta e rifiutato una volta: Android spiega ancora, il dialogo si apre.
    PermissionAsks(context).markAsked(media)
    assertThat(PermissionAsks.deniedForGood(media, PermissionAsks(context)::asked) { true }).isFalse()
  }

  @Test
  fun senzaPermessiMancantiOSenzaActivityNonSiDiceNegato() {
    val asks = PermissionAsks(context).apply { markAsked(media) }

    assertThat(PermissionAsks.deniedForGood(emptyList(), asks::asked) { false }).isFalse()
    assertThat(PermissionAsks.deniedForGood(media, asks::asked) { null }).isFalse()
  }

  @Test
  fun unPermessoConcessoNonRestaNegato() {
    val asks = PermissionAsks(context)
    asks.markAsked(media)

    // L'utente lo ha concesso dalle Impostazioni e poi lo ha tolto: il dialogo di sistema puo' tornare ad aprirsi.
    asks.forgetGranted(listOf(media[0]))

    assertThat(PermissionAsks(context).asked(media[0])).isFalse()
    assertThat(PermissionAsks(context).asked(media[1])).isTrue()
  }
}
