package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.noop.NoPhoneMediaRewriter
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.write.MediaStorePhoneMediaRewriter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Le correzioni delle prove guidate dalla verifica funzionale della Fase 5B (`docs/review/funzionale-5b.md`): LA-6 ("Azzera i numeri" cancellava
 * anche le scelte della schermata), LA-7 (il riferimento e le righe di T-09 e T-16 si perdevano con la ricreazione della schermata o la morte del
 * processo) e LA-8 (righe che le app si cancellavano a vicenda, una sola prima lettura, il file dei numeri riscritto a ogni lettura misurata).
 * Ogni prova e' un "nuovo processo": un nuovo oggetto sulla stessa cartella dello store, come dopo la morte del processo.
 */
@RunWith(AndroidJUnit4::class)
class FieldProbeCorrezioni5BTest {

  @get:Rule val temp = TemporaryFolder()

  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private lateinit var provider: ProbeMediaProvider
  private lateinit var phone: FieldProbePhone
  private lateinit var intents: FakePhoneMediaIntents
  private val dir: File get() = File(temp.root, "store")

  private val now = 1_760_000_000L

  @Before
  fun setUp() {
    provider = ProbeMediaProvider.register()
    phone = FieldProbePhone(context)
    intents = FakePhoneMediaIntents()
    for (id in 1L..4L) provider.insertRow(id, "DCIM/mcg-prova/")
    provider.insertRow(5, "Pictures/mcg-prova/", mime = "video/mp4", content = ByteArray(64))
  }

  /** Un T-09 nuovo sullo store della cartella di prova: il processo e' ripartito (o la schermata e' stata ricreata). */
  private fun t09(store: FieldProbeStore, rewriter: dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaRewriter = NoPhoneMediaRewriter) = FieldProbeT09(
    phone = phone,
    store = store,
    intents = intents,
    rewriter = rewriter,
    cacheDir = File(temp.root, "cache"),
    deletedSince = { emptyList() },
    nowSeconds = { now },
    elapsedMs = { 10L },
  )

  // --- LA-6: "Azzera i numeri" non cancella le scelte -----------------------------------------------------------------------------

  @Test
  fun azzerareLeMisureTieneLeScelteDellaSchermata() {
    val store = FieldProbeStore.forTest(dir)
    store.set(FieldProbeKey.T14_FORM, 1)
    store.set(FieldProbeKey.T14_LATENCY_MS, 200)
    store.set(FieldProbeKey.T14_COUNT, 3)
    store.set(FieldProbeKey.T15_CLOSED_FIRST, 1)
    store.add(FieldProbeKey.T15_RECEIVED, 4)
    store.set(FieldProbeKey.T14_SERVE_READS, 99)

    store.resetNumbers()

    // Prima si perdevano anche loro: la schermata mostrava ancora la forma "diretta" e i componenti usavano quella di partenza.
    assertThat(store.get(FieldProbeKey.T14_FORM)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T14_LATENCY_MS)).isEqualTo(200L)
    assertThat(store.get(FieldProbeKey.T14_COUNT)).isEqualTo(3L)
    assertThat(store.get(FieldProbeKey.T15_CLOSED_FIRST)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T15_RECEIVED)).isNull()
    assertThat(store.get(FieldProbeKey.T14_SERVE_READS)).isNull()
    // E il file lo dice anche a un altro processo.
    assertThat(FieldProbeStore.forTest(dir).get(FieldProbeKey.T14_FORM)).isEqualTo(1L)
    assertThat(FieldProbeForm.of(FieldProbeStore.forTest(dir).get(FieldProbeKey.T14_FORM))).isEqualTo(FieldProbeForm.MEDIASTORE)
  }

  @Test
  fun leScelteNonFatteDiventanoEsplicitePerCheIlResocontoDicaQualeFormaSiEUsata() {
    val store = FieldProbeStore.forTest(dir)
    assertThat(store.render()).isEmpty()

    store.seedChoices()

    // I valori di partenza sono quelli che provider e activity usavano gia' senza una scelta (forma a richiesta, nessuna latenza, un file).
    assertThat(store.render().lines()).containsExactly("t14.count=1", "t14.form=2", "t14.latency_ms=0", "t15.closed_first=0").inOrder()
    assertThat(FieldProbeForm.of(store.get(FieldProbeKey.T14_FORM))).isEqualTo(FieldProbeForm.PROXY)
    // Una scelta gia' fatta non si tocca.
    store.set(FieldProbeKey.T14_FORM, 3)
    store.seedChoices()
    assertThat(store.get(FieldProbeKey.T14_FORM)).isEqualTo(3L)
    assertThat(FieldProbeReport.isNumbersOnly(store.render())).isTrue()
  }

  // --- LA-7: lo stato delle prove a piu' tempi sopravvive ------------------------------------------------------------------------

  @Test
  fun ilRiferimentoDiT09SopravvivePerdendoLaSchermataOIlProcesso() = runBlocking<Unit> {
    val first = FieldProbeStore.forTest(dir)
    t09(first).takeBaseline()
    provider.vanishRow(2)

    // Un processo nuovo (o la schermata ricreata al ritorno da Samsung Gallery): prima "Confronta" diceva "Prima la fotografia di riferimento".
    val second = FieldProbeStore.forTest(dir)
    val probe = t09(second)
    assertThat(probe.compare()).isTrue()
    assertThat(second.get(FieldProbeKey.T09_DIFF_MISSING, 1)).isEqualTo(1L)
    assertThat(second.get(FieldProbeKey.T09_DIFF_SAME_ID_PRESENT, 1)).isEqualTo(4L)
    assertThat(FieldProbeReport.isNumbersOnly(second.render())).isTrue()
  }

  @Test
  fun leRigheCestinateSiRitrovanoPerFinireERipristinare() {
    val first = FieldProbeStore.forTest(dir)
    t09(first).prepareTrash(2, many = false)
    provider.trashRow(1, now + 30 * 86_400L)
    provider.trashRow(2, now + 30 * 86_400L)

    // Il dialogo di sistema risponde dopo che la schermata si e' ricreata.
    val second = FieldProbeStore.forTest(dir)
    val probe = t09(second)
    probe.finishTrash(Activity.RESULT_OK, many = false)
    assertThat(second.get(FieldProbeKey.T09_TRASH_IS_TRASHED)).isEqualTo(2L)
    assertThat(second.get(FieldProbeKey.T09_TRASH_EXPIRES_DAYS_MIN)).isEqualTo(30L)
    // E "Ripristina" trova le righe da ripristinare (il telefono finto non produce un IntentSender: si guarda la richiesta fatta).
    probe.prepareRestore()
    val (restoreRefs, restoreTrash) = intents.trashRequests.last()
    assertThat(restoreRefs.map { it.mediaStoreId }).containsExactly(1L, 2L)
    assertThat(restoreTrash).isFalse()
  }

  @Test
  fun laRiscritturaConLoStessoPermessoSiFinisceDopoLaMorteDelProcesso() = runBlocking<Unit> {
    val rewriter = MediaStorePhoneMediaRewriter(context, undoDir = File(temp.root, "undo"))
    val before = provider.contentOf(1)!!.toList()
    t09(FieldProbeStore.forTest(dir), rewriter).prepareWrite()

    val second = FieldProbeStore.forTest(dir)
    t09(second, rewriter).finishWrite(Activity.RESULT_OK)

    assertThat(second.get(FieldProbeKey.T09_WRITE_CODE)).isEqualTo(0L)
    assertThat(second.get(FieldProbeKey.T09_WRITE_SIZE_BEFORE)).isEqualTo(16L)
    assertThat(second.get(FieldProbeKey.T09_WRITE_SIZE_AFTER)).isEqualTo(16L)
    assertThat(provider.contentOf(1)!!.toList()).isEqualTo(before)
  }

  @Test
  fun ilRiferimentoDiT16SopravvivePerdendoLaSchermata() {
    val first = FieldProbeStore.forTest(dir)
    FieldProbeT16(context, phone, first).prepareView()!!
    provider.overwrite(1, ByteArray(40) { 9 }, modifiedSec = 1_760_000_500L)

    val second = FieldProbeStore.forTest(dir)
    val t16 = FieldProbeT16(context, phone, second)
    assertThat(t16.compareView()).isTrue()
    assertThat(second.get(FieldProbeKey.T16_VIEW_SAME_ID, 1)).isEqualTo(1L)
    assertThat(second.get(FieldProbeKey.T16_VIEW_ROW_CHANGED, 1)).isEqualTo(1L)
    assertThat(second.get(FieldProbeKey.T16_VIEW_SIZE_DELTA, 1)).isEqualTo(24L)
  }

  @Test
  fun laCopiaInModificaSiRitrovaEFinisceConIPermessiRevocatiELaCopiaTolta() {
    val editDir = File(context.cacheDir, "edit").also { it.mkdirs() }
    val file = File(editDir, "probe-1.jpg").apply { writeBytes(ByteArray(16) { 1 }) }
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val store = FieldProbeStore.forTest(dir)
    store.saveSession("t16_edit", listOf("edit_file=probe-1.jpg", "edit_sha=$sha", "edit_size=16", "edit_sibling=altro.jpg"))
    // L'editor ha riscritto la copia mentre la schermata era chiusa.
    file.writeBytes(ByteArray(24) { 2 })

    FieldProbeT16(context, phone, FieldProbeStore.forTest(dir)).finishEdit(Activity.RESULT_OK, null)

    val after = FieldProbeStore.forTest(dir)
    assertThat(after.get(FieldProbeKey.T16_EDIT_BYTES_CHANGED)).isEqualTo(1L)
    assertThat(after.get(FieldProbeKey.T16_EDIT_SIZE_BEFORE)).isEqualTo(16L)
    assertThat(after.get(FieldProbeKey.T16_EDIT_SIZE_AFTER)).isEqualTo(24L)
    assertThat(file.exists()).isFalse()
    // Finita, la prova non lascia lo stato: un'altra schermata non riprova a chiudere una modifica gia' chiusa.
    assertThat(after.loadSession("t16_edit")).isNull()
  }

  @Test
  fun lOStatoNonLasciaMaiEntrareUnPercorsoNeUnNomeFuoriDallaCartellaDiProva() {
    val store = FieldProbeStore.forTest(dir)
    assertThrows(IllegalArgumentException::class.java) { store.saveSession("../fuori", listOf("x")) }
    assertThrows(IllegalArgumentException::class.java) { store.loadSession("Con Spazi") }
    // Una riga di riferimento con del testo non e' una riga: non entra.
    assertThat(ProbeRowMarks.decode("1\tdue\t3\t\t")).isNull()
    assertThat(ProbeRowMarks.decode("1\t2\t3")).isNull()
    assertThat(ProbeRowMarks.decode("1\t2\t3\t\t")!!.dateTakenMs).isNull()
    val row = ProbeRowMarks.decode("7\t100\t1760000000\t1760000000001\t55")!!
    assertThat(ProbeRowMarks.encode(row)).isEqualTo("7\t100\t1760000000\t1760000000001\t55")
    // Un file di stato toccato a mano con un nome di copia di modifica che non e' quello di una prova non riapre niente.
    store.saveSession("t16_edit", listOf("edit_file=../../databases/gallery.db", "edit_size=1"))
    val t16 = FieldProbeT16(context, phone, store)
    t16.finishEdit(Activity.RESULT_OK, null)
    assertThat(store.get(FieldProbeKey.T16_EDIT_RESULT_CODE)).isNull()
  }

  // --- LA-8: righe che non si cancellano a vicenda, scritture fuori dalla lettura misurata ----------------------------------------------

  @Test
  fun ogniChiamanteDiverseHaLaSuaRigaEUnoGiaVistoNonNeAggiungeUnaTerza() {
    val store = FieldProbeStore.forTest(dir)
    store.recordDistinct(FieldProbeKey.T14_DOCS_CALLER, FieldProbeCaller.GMAIL.code)
    store.recordDistinct(FieldProbeKey.T14_DOCS_CALLER, FieldProbeCaller.WHATSAPP.code)
    store.recordDistinct(FieldProbeKey.T14_DOCS_CALLER, FieldProbeCaller.GMAIL.code)
    store.recordDistinct(FieldProbeKey.T14_DOCS_CALLER, FieldProbeCaller.TELEGRAM.code)

    // Prima restava solo l'ultima app: `t14.docs_caller=3`.
    assertThat(store.render().lines()).containsExactly("t14.docs_caller.1=1", "t14.docs_caller.2=2", "t14.docs_caller.3=3").inOrder()
    assertThat(FieldProbeReport.isNumbersOnly(store.render())).isTrue()
    assertThat(FieldProbeStore.forTest(dir).render()).isEqualTo(store.render())
  }

  @Test
  fun laPrimaLetturaSiMisuraPerOgniAperturaENonSoloLaPrimaDiTutte() {
    val store = FieldProbeStore.forTest(dir)
    store.setIfAbsent(FieldProbeKey.T14_SERVE_FIRST_READ_MS, 40, index = 1)
    store.setIfAbsent(FieldProbeKey.T14_SERVE_FIRST_READ_MS, 999, index = 1) // la stessa apertura: la prima resta
    store.setIfAbsent(FieldProbeKey.T14_SERVE_FIRST_READ_MS, 75, index = 2)

    assertThat(store.get(FieldProbeKey.T14_SERVE_FIRST_READ_MS, 1)).isEqualTo(40L)
    assertThat(store.get(FieldProbeKey.T14_SERVE_FIRST_READ_MS, 2)).isEqualTo(75L)
  }

  @Test
  fun lePrimeRiprovePrimaDelleSecondeNonSiCancellano() {
    val store = FieldProbeStore.forTest(dir)
    // Due riprove di T-15 a distanza di tempo: la seconda ha un blocco suo.
    for ((ok, denied) in listOf(3L to 0L, 1L to 2L)) {
      val index = store.nextIndex(FieldProbeKey.T15_REOPEN_OK)
      store.set(FieldProbeKey.T15_REOPEN_OK, ok, index)
      store.set(FieldProbeKey.T15_REOPEN_DENIED, denied, index)
    }
    assertThat(store.get(FieldProbeKey.T15_REOPEN_OK, 1)).isEqualTo(3L)
    assertThat(store.get(FieldProbeKey.T15_REOPEN_OK, 2)).isEqualTo(1L)
    assertThat(store.get(FieldProbeKey.T15_REOPEN_DENIED, 2)).isEqualTo(2L)
  }

  @Test
  fun unaLetturaMisurataNonRiscriveIlFileAOgniNumero() {
    // Lo store di prima (e dei test) scrive a ogni cambiamento: una lettura che ne cambia quattro riscriveva il file quattro volte.
    val eager = FieldProbeStore.forTest(dir)
    repeat(100) {
      eager.add(FieldProbeKey.T14_SERVE_READS)
      eager.add(FieldProbeKey.T14_SERVE_BYTES, 65_536)
    }
    assertThat(eager.writes).isEqualTo(200)

    // Quello del processo tiene i numeri in memoria e li scrive fuori dalla lettura, una volta ogni tanto: gli stessi cambiamenti, pochissime scritture.
    val deferred = FieldProbeStore.forTestDeferred(File(temp.root, "differito"))
    repeat(100) {
      deferred.add(FieldProbeKey.T14_SERVE_READS)
      deferred.add(FieldProbeKey.T14_SERVE_BYTES, 65_536)
    }
    assertThat(deferred.get(FieldProbeKey.T14_SERVE_READS)).isEqualTo(100L)
    assertThat(deferred.writes).isAtMost(2)
    // I numeri sono comunque nel resoconto subito, e su file appena si chiede (o dopo qualche decina di ms).
    assertThat(deferred.render()).contains("t14.serve_reads=100")
    deferred.flush()
    assertThat(FieldProbeStore.forTest(File(temp.root, "differito")).get(FieldProbeKey.T14_SERVE_BYTES)).isEqualTo(6_553_600L)
  }
}
