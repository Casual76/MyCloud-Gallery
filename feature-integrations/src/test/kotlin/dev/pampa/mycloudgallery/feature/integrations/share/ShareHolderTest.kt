package dev.pampa.mycloudgallery.feature.integrations.share

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.integrations.share.testing.RecordingUriRevoker
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Correzioni 5B (MC-1): la copia consegnata a un'altra app non si toglie quando si spegne l'avviso (3,2 secondi dopo la scelta dell'app), ma quando
 * l'altra app ha finito: al ritorno dell'utente nell'app (con un minuto di margine), oppure dopo mezz'ora, e una volta sola.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareHolderTest {

  @get:Rule val folder = TemporaryFolder()

  private val revoker = RecordingUriRevoker()

  /** Una condivisione consegnata: una cartella con la copia e un indirizzo con il permesso. */
  private fun lease(name: String): Pair<ShareLease, File> {
    val dir = File(folder.root, "share/$name").apply { mkdirs() }
    File(dir, "foto.jpg").writeBytes(byteArrayOf(1, 2, 3))
    return ShareLease(listOf(Uri.parse("content://prova/$name")), listOf(dir), revoker, null) to dir
  }

  private fun TestScope.holder(): ShareHolder = ShareHolder(backgroundScope, StandardTestDispatcher(testScheduler))

  @Test
  fun laCopiaResta3SecondiDopoLaSceltaEFinCheLUtenteNonTorna() = runTest {
    val holder = holder()
    val (lease, dir) = lease("a")
    holder.hold(lease)
    holder.appLeft() // si apre la scelta dell'app

    // Il viewer spegne il suo avviso dopo 3,2 secondi: la copia c'e' ancora (prima a questo punto era gia' stata cancellata).
    advanceTimeBy(3_200)
    runCurrent()
    assertThat(dir.exists()).isTrue()
    assertThat(lease.isReleased).isFalse()
    // WhatsApp legge dopo che l'utente ha scelto la chat: dieci minuti dopo la copia c'e' ancora.
    advanceTimeBy(10 * 60_000L)
    runCurrent()
    assertThat(dir.exists()).isTrue()

    // L'utente torna nell'app: un minuto di margine, poi la copia se ne va e il permesso si revoca.
    holder.appReturned()
    advanceTimeBy(ShareHolder.AFTER_RETURN_MS - 1_000)
    runCurrent()
    assertThat(dir.exists()).isTrue()
    advanceTimeBy(2_000)
    runCurrent()
    assertThat(dir.exists()).isFalse()
    assertThat(revoker.revoked).containsExactly(Uri.parse("content://prova/a"))
    assertThat(holder.count).isEqualTo(0)
  }

  @Test
  fun seLUtenteNonTornaSiRilasciaDopoMezzOra() = runTest {
    val holder = holder()
    val (lease, dir) = lease("b")
    holder.hold(lease)
    holder.appLeft()

    advanceTimeBy(ShareHolder.MAX_HOLD_MS - 1)
    runCurrent()
    assertThat(dir.exists()).isTrue()
    advanceTimeBy(2)
    runCurrent()
    assertThat(dir.exists()).isFalse()
    // Tornare dopo non rilascia una seconda volta.
    holder.appReturned()
    advanceTimeBy(ShareHolder.AFTER_RETURN_MS * 2)
    runCurrent()
    assertThat(revoker.revoked).hasSize(1)
  }

  @Test
  fun unRitornoPrimaDiEssereUscitiNonRilascia() = runTest {
    // La scelta dell'app non e' ancora davanti (l'activity non e' andata in pausa): un `onResume` qui non vuol dire "ha finito".
    val holder = holder()
    val (lease, dir) = lease("c")
    holder.hold(lease)
    holder.appReturned()
    advanceTimeBy(ShareHolder.AFTER_RETURN_MS * 3)
    runCurrent()
    assertThat(dir.exists()).isTrue()
    // Poi esce e torna davvero.
    holder.appLeft()
    holder.appReturned()
    advanceTimeBy(ShareHolder.AFTER_RETURN_MS + 1)
    runCurrent()
    assertThat(dir.exists()).isFalse()
  }

  @Test
  fun piuCondivisioniOgnunaConIlSuoTempoELAzzeramentoLeTogliTutte() = runTest {
    val holder = holder()
    val (first, firstDir) = lease("d")
    holder.hold(first)
    holder.appLeft()
    holder.appReturned()
    // Una seconda condivisione mentre la prima aspetta il suo minuto: non la porta via con se'.
    val (second, secondDir) = lease("e")
    holder.hold(second)
    advanceTimeBy(ShareHolder.AFTER_RETURN_MS + 1)
    runCurrent()
    assertThat(firstDir.exists()).isFalse()
    assertThat(secondDir.exists()).isTrue()

    holder.releaseAll()
    assertThat(secondDir.exists()).isFalse()
    assertThat(holder.count).isEqualTo(0)
    assertThat(revoker.revoked).hasSize(2)
  }
}
