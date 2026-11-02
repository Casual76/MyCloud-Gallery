package dev.pampa.mycloudgallery.feature.integrations.drag

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DropTarget
import dev.pampa.mycloudgallery.core.designsystem.adaptive.IncomingDrag
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.receive.ReceiveRequest
import dev.pampa.mycloudgallery.feature.integrations.receive.ShareReceiverActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * Fase 10 (P6, D21, C437; E-T15, E-T16, E-X2, E-X7): il trascinamento in arrivo. Un rilascio non apre una strada nuova verso il NAS: porta i flussi al **foglio della ricezione** che c'e' gia'
 * (`ShareReceiverActivity`) e basta. Un trascinamento partito da noi non si riceve; il blocco dell'app ferma il rilascio; la destinazione viaggia in un evento interno fresco e una volta sola,
 * mai nell'intento.
 */
@RunWith(AndroidJUnit4::class)
class IncomingDropsTest {

  private var decision = EntryDecision.ALLOWED
  private var now = 5_000L
  private val launched = mutableListOf<Intent>()
  private val notices = mutableListOf<Int>()
  private var launcherAnswer = true

  private fun drops() = IncomingDrops(
    context = ApplicationProvider.getApplicationContext(),
    gate = { decision },
    monotonicMs = { now },
    launcher = { _, intent -> launched += intent; launcherAnswer },
    notice = { notices += it },
  )

  private val foreign = Uri.parse("content://com.example.photos/media/42")

  private fun clipOf(vararg uris: Uri): ClipData {
    val clip = ClipData(ClipDescription("", arrayOf("image/jpeg")), ClipData.Item(uris.first()))
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    return clip
  }

  private fun drag(clip: ClipData?, own: Boolean = false, description: ClipDescription? = clip?.description, onGrant: () -> Unit = {}) = IncomingDrag(description, clip, own, onGrant)

  @Before
  fun setUp() {
    DragCounters.reset()
    DropHandoff.reset()
  }

  @After
  fun tearDown() {
    DragCounters.reset()
    DropHandoff.reset()
  }

  // --- Cosa si offre -------------------------------------------------------------------------------------------------------------

  @Test
  fun siOffreSoloPerFotoEVideoDiUnAltraApp() {
    val drops = drops()
    assertThat(drops.accepts(DropTarget.Library, drag(clipOf(foreign)))).isTrue()
    val video = ClipData(ClipDescription("", arrayOf("video/mp4")), ClipData.Item(foreign))
    assertThat(drops.accepts(DropTarget.Library, drag(video))).isTrue()
    val text = ClipData.newPlainText("", "ciao")
    assertThat(drops.accepts(DropTarget.Library, drag(text))).isFalse()
    // Un elenco di indirizzi di tipo sconosciuto non basta.
    val unknown = ClipData.newRawUri("", foreign)
    assertThat(drops.accepts(DropTarget.Library, drag(unknown))).isFalse()
    assertThat(drops.accepts(DropTarget.Library, drag(null, description = null))).isFalse()
  }

  @Test
  fun unTrascinamentoPartitoDaNoiNonSiRiceve() {
    val drops = drops()
    assertThat(drops.accepts(DropTarget.Album("a"), drag(clipOf(foreign), own = true))).isFalse()
    assertThat(drops.drop(DropTarget.Album("a"), drag(clipOf(foreign), own = true), null)).isFalse()
    assertThat(launched).isEmpty()
    assertThat(DragCounters.get(DragCounter.IN_OWN_IGNORED)).isEqualTo(1L)
  }

  // --- Il rilascio -------------------------------------------------------------------------------------------------------------------

  @Test
  fun ilRilascioApreSoloIlFoglioDellaRicezioneConIFlussiEBasta() {
    var granted = 0
    val activity = Robolectric.buildActivity(Activity::class.java).get()

    val taken = drops().drop(DropTarget.Library, drag(clipOf(foreign, Uri.parse("content://com.example.photos/media/43")), onGrant = { granted++ }), activity)

    assertThat(taken).isTrue()
    assertThat(granted).isEqualTo(1)
    val intent = launched.single()
    // Il nostro foglio, esplicito, con il permesso di sola lettura sui flussi: mai scrittura, mai persistente.
    assertThat(intent.component?.className).isEqualTo(ShareReceiverActivity::class.java.name)
    assertThat(intent.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
    assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    assertThat(intent.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)).isEqualTo(0)
    assertThat(intent.clipData!!.itemCount).isEqualTo(2)
    // Lo legge il filtro del foglio come qualunque condivisione.
    assertThat(ReceiveRequest.from(intent)!!.uris).hasSize(2)
    assertThat(DragCounters.get(DragCounter.IN_DROPPED)).isEqualTo(1L)
  }

  /** CA-2 delle revisioni della Fase 10: con la versione fermata dal manifest un rilascio non apre il foglio e dice perche' ("aggiorna l'app"). */
  @Test
  fun ca2_conLaVersioneFermataIlRilascioNonPortaNienteEDiceDiAggiornare() {
    decision = EntryDecision.STOPPED

    assertThat(drops().drop(DropTarget.Library, drag(clipOf(foreign)), null)).isFalse()

    assertThat(launched).isEmpty()
    assertThat(DropHandoff.take(now)).isNull()
    assertThat(notices).containsExactly(dev.pampa.mycloudgallery.feature.integrations.R.string.integrations_drag_in_stopped)
  }

  @Test
  fun conIlBloccoAttivoIlRilascioNonPortaNiente_E_X7() {
    decision = EntryDecision.NEEDS_UNLOCK

    assertThat(drops().drop(DropTarget.Library, drag(clipOf(foreign)), null)).isFalse()

    assertThat(launched).isEmpty()
    assertThat(DropHandoff.take(now)).isNull()
    assertThat(notices).hasSize(1)
    for (other in listOf(EntryDecision.NOT_READY, EntryDecision.LOADING)) {
      decision = other
      assertThat(drops().drop(DropTarget.Library, drag(clipOf(foreign)), null)).isFalse()
    }
    assertThat(launched).isEmpty()
    assertThat(DragCounters.get(DragCounter.IN_REFUSED)).isEqualTo(3L)
  }

  @Test
  fun unRilascioSenzaFlussiOChe_ilFoglioNonSaAprireNonLasciaNiente() {
    assertThat(drops().drop(DropTarget.Library, drag(null), null)).isFalse()
    val text = ClipData.newPlainText("", "ciao")
    assertThat(drops().drop(DropTarget.Library, drag(text), null)).isFalse()
    assertThat(launched).isEmpty()
    launcherAnswer = false
    assertThat(drops().drop(DropTarget.Album("a"), drag(clipOf(foreign)), null)).isFalse()
    // Se l'avvio non riesce l'offerta della destinazione non resta ad aspettare un altro foglio.
    assertThat(DropHandoff.take(now)).isNull()
  }

  @Test
  fun ilFoglioRifiutaFileEProviderNostriComeOgniCondivisione_C18() {
    // Il filtro del foglio e' lo stesso: gli indirizzi file:// e quelli del nostro provider arrivano al foglio, che li rifiuta senza aprirli (IncomingOrigins, E-X2).
    val own = Uri.parse("content://${ApplicationProvider.getApplicationContext<android.content.Context>().packageName}.integrations.documents/document/aaaaaaaaaaaaaaaaaaaaaaaa")
    val file = Uri.parse("file:///sdcard/DCIM/a.jpg")
    assertThat(drops().drop(DropTarget.Library, drag(clipOf(own, file)), null)).isTrue()
    assertThat(ReceiveRequest.from(launched.single())!!.uris).containsExactly(own, file).inOrder()
  }

  // --- La destinazione: un evento interno, fresco, una volta -------------------------------------------------------------

  @Test
  fun laDestinazioneViaggiaNellEventoInternoENonNellIntento() {
    drops().drop(DropTarget.Album("album-1"), drag(clipOf(foreign)), null)

    val intent = launched.single()
    assertThat(intent.extras?.keySet().orEmpty().filter { it != Intent.EXTRA_STREAM }).isEmpty()
    assertThat(intent.dataString).isNull()
    assertThat(intent.toString()).doesNotContain("album-1")
    assertThat(DropHandoff.take(now)).isEqualTo(DropHint.Album("album-1"))
    // Una volta sola.
    assertThat(DropHandoff.take(now)).isNull()
  }

  @Test
  fun lOffertaScadeEUnOrologioCheTornaIndietroLaFaScadere() {
    DropHandoff.offer(DropHint.Library, 1_000L)
    assertThat(DropHandoff.take(1_000L + DropHandoff.FRESH_MS + 1)).isNull()
    DropHandoff.offer(DropHint.Library, 1_000L)
    assertThat(DropHandoff.take(500L)).isNull()
    DropHandoff.offer(DropHint.Folder("s", "a/b"), 1_000L)
    assertThat(DropHandoff.take(1_000L + DropHandoff.FRESH_MS)).isEqualTo(DropHint.Folder("s", "a/b"))
  }

  @Test
  fun ogniDestinazioneDiventaLaSuaScelta_ENessunDatoEnellToString() {
    assertThat(DropHint.of(DropTarget.Library)).isEqualTo(DropHint.Library)
    assertThat(DropHint.of(DropTarget.Album("x"))).isEqualTo(DropHint.Album("x"))
    assertThat(DropHint.of(DropTarget.NasFolder("share", "a/b"))).isEqualTo(DropHint.Folder("share", "a/b"))
    assertThat(DropHint.Album("segreto").toString()).doesNotContain("segreto")
    assertThat(DropHint.Folder("laShare", "il/percorso").toString()).doesNotContain("percorso")
    assertThat(DropTarget.NasFolder("laShare", "il/percorso").toString()).doesNotContain("percorso")
  }
}
