package dev.pampa.mycloudgallery.feature.integrations.share

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.LocationStripper
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.StripUnsupported
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.share.testing.FakeCacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.testing.InMemoryShareSource
import dev.pampa.mycloudgallery.feature.integrations.share.testing.RecordingUriRevoker
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 5B, P11 (D26, E-X5, E-X1, E-X4, E-X6, E-V7, L41; C17): "Condividi" senza posizione, con lo stripper vero di core-metadata e i suoi lettori.
 * Il file consegnato **non ha una posizione** (verificata con tre lettori diversi), si decodifica ancora e ha i suoi altri dati; se togliere la
 * posizione non riesce **non parte nessun file con la posizione**, e nessuna copia resta in cache; i permessi si revocano dopo l'uso.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationFreeShareTest {

  @get:Rule val folder = TemporaryFolder()

  private val source = InMemoryShareSource()
  private val uris = FakeCacheUris()
  private val revoker = RecordingUriRevoker()
  private lateinit var cache: ShareCache
  private lateinit var share: LocationFreeShare

  @Before
  fun setUp() {
    FlowCounters.reset()
    cache = ShareCache(File(folder.root, "cache"))
    share = LocationFreeShare(cache, source, ShareSamples.stripper(), uris, revoker, Dispatchers.Unconfined)
  }

  private fun media(bytes: ByteArray, mime: String, name: String? = "Mare 2024.jpg", phoneUri: Uri? = null): MediaId =
    MediaId.random().also { source.put(it, bytes, mime, name, phoneUri) }

  private fun prepare(item: MediaId, removeLocation: Boolean = true) = runBlocking { share.prepare(listOf(item), removeLocation) }

  private fun prepare(first: MediaId, second: MediaId) = runBlocking { share.prepare(listOf(first, second), true) }

  /** Uno stripper deciso dal test. */
  private fun stripperDoing(action: (File, File) -> StripOutcome): LocationStripper = object : LocationStripper {
    override fun strip(src: File, dst: File, mime: String): StripOutcome = action(src, dst)
  }

  private fun leftovers(): List<String> = File(cache.shareRoot, "").walkTopDown().filter { it.isFile }.map { it.name }.toList()

  // --- Rimuovi la posizione acceso ---------------------------------------------------------------------------------------------

  @Test
  fun unaFotoConPosizioneEsceSenzaPosizioneVerificataEConIlSuoNome() {
    val original = ShareSamples.gpsJpeg(folder.root)
    assertThat(ShareSamples.latLong(File(folder.root, "o.jpg").apply { writeBytes(original) })).isNotNull() // la fixture ha davvero una posizione
    val id = media(original, "image/jpeg")

    val result = prepare(id) as SharePreparation.Ready

    val shared = result.files.single()
    assertThat(shared.mime).isEqualTo("image/jpeg")
    assertThat(result.stripped).isEqualTo(1)
    val file = shared.file!!
    assertThat(file.name).isEqualTo("Mare 2024.jpg") // il nome dell'utente, ripulito, con l'estensione del tipo vero
    assertThat(ShareSamples.latLong(file)).isNull()
    // Correzioni 5B: si tiene per elenco. La data resta, la fotocamera no.
    assertThat(ShareSamples.model(file)).isNull()
    assertThat(android.media.ExifInterface(file.path).getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)).isEqualTo("2024:03:12 10:15:30")
    assertThat(file.length()).isEqualTo(shared.size)
    // La copia letta (con la posizione) non resta in cache: solo il file consegnato.
    assertThat(leftovers()).containsExactly("Mare 2024.jpg")
    // L'indirizzo e' quello del provider, non un percorso.
    assertThat(shared.uri.toString()).startsWith("content://prova/")
    assertThat(source.opened).isEqualTo(1)
    assertThat(source.closed).isEqualTo(1)
    assertThat(FlowCounters.share.get(ShareCounter.ITEMS_STRIPPED)).isEqualTo(1L)
  }

  @Test
  fun unVideoConPosizioneEsceSenzaStessaDimensione() {
    val video = ShareSamples.gpsMp4()
    val id = media(video, "video/mp4", "Gita.mp4")

    val result = prepare(id) as SharePreparation.Ready

    val shared = result.files.single()
    val bytes = shared.file!!.readBytes()
    assertThat(bytes.size).isEqualTo(video.size) // nessuna ricodifica
    assertThat(ShareSamples.contains(bytes, ShareSamples.ISO6709)).isFalse()
    assertThat(ShareSamples.contains(bytes, "©xyz")).isFalse()
    assertThat(shared.mime).isEqualTo("video/mp4")
    assertThat(shared.file!!.name).isEqualTo("Gita.mp4")
  }

  @Test
  fun piuFileTuttiSenzaPosizioneEPermessoSoloInLettura() {
    val a = media(ShareSamples.gpsJpeg(folder.root, "a.jpg"), "image/jpeg", "A.jpg")
    val b = media(ShareSamples.gpsMp4(), "video/mp4", "B.mp4")

    val result = prepare(a, b) as SharePreparation.Ready

    assertThat(result.files).hasSize(2)
    assertThat(result.files.map { it.file!!.parentFile!!.name }).containsExactly("0", "1") // una cartella per elemento: nessuna collisione di nomi
    val intent = ShareIntents.send(result.files)
    assertThat(intent.action).isEqualTo(android.content.Intent.ACTION_SEND_MULTIPLE)
    assertThat(intent.type).isEqualTo("*/*") // misti
    assertThat(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    assertThat(intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0) // mai in scrittura
    assertThat(intent.clipData!!.itemCount).isEqualTo(2)
  }

  // --- Si fallisce chiusi -------------------------------------------------------------------------------------------------------

  @Test
  fun unFormatoCheNonSiSaTrattareFermaTuttoENonPartePiuNientePrimaDelPrimo() {
    val ok = media(ShareSamples.gpsJpeg(folder.root, "a.jpg"), "image/jpeg")
    val gif = media("GIF89a".toByteArray() + ByteArray(100) { 0x90.toByte() }, "image/gif", "animata.gif")

    val result = prepare(ok, gif)

    val stopped = result as SharePreparation.Stopped
    assertThat(stopped.stop).isEqualTo(ShareStop.CANNOT_REMOVE_LOCATION)
    assertThat(stopped.index).isEqualTo(1)
    // Nemmeno la prima foto, che era riuscita, resta in cache o parte: una condivisione e' tutta o niente.
    assertThat(leftovers()).isEmpty()
    assertThat(cache.shareRoot.listFiles().orEmpty().toList()).isEmpty()
    assertThat(source.closed).isEqualTo(source.opened) // niente resta aperto
  }

  @Test
  fun seUnLettoreVedeAncoraUnaPosizioneLaCondivisioneSiFerma() {
    // Uno stripper sbagliato (non toglie niente): il file consegnato avrebbe ancora la posizione, ma i lettori lo fermano e la copia sparisce.
    val noop = stripperDoing { src, dst -> src.copyTo(dst); StripOutcome.StillHasLocation }
    val sharing = LocationFreeShare(cache, source, noop, uris, revoker, Dispatchers.Unconfined)
    val id = media(ShareSamples.gpsJpeg(folder.root), "image/jpeg")

    val result = runBlocking { sharing.prepare(listOf(id), removeLocation = true) } as SharePreparation.Stopped

    assertThat(result.stop).isEqualTo(ShareStop.LOCATION_REMAINS)
    assertThat(leftovers()).isEmpty()
    assertThat(uris.issued).isEmpty() // nessun indirizzo e' stato mai emesso per un file con la posizione
  }

  @Test
  fun ognEsitoDelloStripperCheNonESuccessoFermaLaCondivisione() {
    val cases = mapOf(
      StripOutcome.StillHasLocation to ShareStop.LOCATION_REMAINS,
      StripOutcome.Unsupported(StripUnsupported.FORMAT) to ShareStop.CANNOT_REMOVE_LOCATION,
      StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE) to ShareStop.CANNOT_REMOVE_LOCATION,
      StripOutcome.Failed(WriteFailure.IO) to ShareStop.FAILED,
      StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE) to ShareStop.FAILED,
      StripOutcome.Failed(WriteFailure.DESTINATION_EXISTS) to ShareStop.FAILED,
    )
    for ((outcome, stop) in cases) {
      val sharing = LocationFreeShare(cache, source, stripperDoing { _, _ -> outcome }, uris, revoker, Dispatchers.Unconfined)
      val id = media(ShareSamples.gpsJpeg(folder.root), "image/jpeg")
      val result = runBlocking { sharing.prepare(listOf(id), removeLocation = true) } as SharePreparation.Stopped
      assertThat(result.stop).isEqualTo(stop)
      assertThat(leftovers()).isEmpty()
    }
    assertThat(uris.issued).isEmpty()
  }

  @Test
  fun unaCopiaCheLoStripperDiceFattaMaNonCeNonViene_Mai_Consegnata() {
    // Un successo dichiarato senza destinazione: l'indirizzo non si emette e la condivisione si ferma.
    val liar = stripperDoing { _, _ -> StripOutcome.Stripped("image/jpeg") }
    val sharing = LocationFreeShare(cache, source, liar, uris, revoker, Dispatchers.Unconfined)
    val id = media(ShareSamples.gpsJpeg(folder.root), "image/jpeg")

    val result = runBlocking { sharing.prepare(listOf(id), removeLocation = true) }

    assertThat(result).isInstanceOf(SharePreparation.Stopped::class.java)
    assertThat(uris.issued).isEmpty()
  }

  // --- Impostazione spenta ---------------------------------------------------------------------------------------------------------

  @Test
  fun conLImpostazioneSpentaUnMediaDelTelefonoSiCondividiComeESenzaCopia() {
    val phone = Uri.parse("content://media/external/images/media/42")
    val id = media(ShareSamples.gpsJpeg(folder.root), "image/jpeg", phoneUri = phone)

    val result = prepare(id, removeLocation = false) as SharePreparation.Ready

    assertThat(result.files.single().uri).isEqualTo(phone)
    assertThat(result.files.single().file).isNull()
    assertThat(result.stripped).isEqualTo(0)
    assertThat(leftovers()).isEmpty() // nessuna copia
    assertThat(FlowCounters.share.get(ShareCounter.ITEMS_DIRECT)).isEqualTo(1L)
  }

  @Test
  fun unSoloNasSiScaricaInCacheComeEAnchePerLaPosizioneSpenta_E_V7() {
    val bytes = ShareSamples.gpsJpeg(folder.root)
    val id = media(bytes, "image/jpeg", "Solo NAS.jpg")

    val result = prepare(id, removeLocation = false) as SharePreparation.Ready

    assertThat(result.files.single().file!!.readBytes()).isEqualTo(bytes) // com'e': l'utente ha scelto di non togliere la posizione
    assertThat(result.stripped).isEqualTo(0)
    result.lease.release()
    assertThat(leftovers()).isEmpty()
  }

  // --- Tetti e NAS ---------------------------------------------------------------------------------------------------------------

  @Test
  fun iTettiFermanoPrimaDiCopiare_ENasCheNonRispondeFermaSubito_E_X1() {
    // Troppi elementi: nessuna cartella nemmeno creata.
    val many = List(ShareLimits.MAX_ITEMS + 1) { MediaId.random() }
    val tooMany = runBlocking { share.prepare(many, true) } as SharePreparation.Stopped
    assertThat(tooMany.stop).isEqualTo(ShareStop.TOO_MANY)
    assertThat(cache.shareRoot.exists()).isFalse()
    assertThat(source.opened).isEqualTo(0)

    // Niente da condividere.
    assertThat((runBlocking { share.prepare(emptyList(), true) } as SharePreparation.Stopped).stop).isEqualTo(ShareStop.NOTHING)

    // Il NAS non risponde: errore subito con il motivo; le copie gia' fatte si tolgono.
    val ok = media(ShareSamples.gpsJpeg(folder.root), "image/jpeg")
    val offline = MediaId.random().also { source.nasUnreachable += it }
    val unreachable = prepare(ok, offline) as SharePreparation.Stopped
    assertThat(unreachable.stop).isEqualTo(ShareStop.NAS_UNREACHABLE)
    assertThat(unreachable.index).isEqualTo(1)
    assertThat(leftovers()).isEmpty()

    // Un media che non c'e' piu'.
    assertThat((prepare(MediaId.random()) as SharePreparation.Stopped).stop).isEqualTo(ShareStop.UNAVAILABLE)
  }

  @Test
  fun pocoSpazioOFileTroppoGrandiFermanoPrimaDiScrivere() {
    // Un video da 3 GiB (dichiarato): oltre i 2 GiB, si ferma prima di copiare un solo byte.
    val big = MediaId.random().also { source.putVirtual(it, 3L shl 30, "video/mp4", "Grande.mp4") }
    val tooBig = prepare(big) as SharePreparation.Stopped
    assertThat(tooBig.stop).isEqualTo(ShareStop.TOO_BIG)
    assertThat(leftovers()).isEmpty()

    // Meno di 1 GiB libero dopo la copia (per togliere la posizione servono due copie): si ferma.
    val tight = LocationFreeShare(ShareCache(File(folder.root, "stretta"), free = { (1L shl 30) + 100 }), source, ShareSamples.stripper(), uris, revoker, Dispatchers.Unconfined)
    val video = media(ShareSamples.gpsMp4(mdatBytes = 4_000), "video/mp4")
    val noSpace = runBlocking { tight.prepare(listOf(video), true) } as SharePreparation.Stopped
    assertThat(noSpace.stop).isEqualTo(ShareStop.NO_SPACE)
    assertThat(File(folder.root, "stretta/share").walkTopDown().filter { it.isFile }.toList()).isEmpty()

    // Con spazio a sufficienza lo stesso file passa.
    val roomy = LocationFreeShare(ShareCache(File(folder.root, "larga"), free = { 10L shl 30 }), source, ShareSamples.stripper(), uris, revoker, Dispatchers.Unconfined)
    assertThat(runBlocking { roomy.prepare(listOf(video), true) }).isInstanceOf(SharePreparation.Ready::class.java)
    assertThat(ShareLimits.MIN_FREE_AFTER_BYTES).isEqualTo(1L shl 30)
    assertThat(ShareLimits.MAX_TOTAL_BYTES).isEqualTo(2L shl 30)
    assertThat(ShareLimits.MAX_ITEMS).isEqualTo(100)
  }

  @Test
  fun unaLetturaCheSiInterrompeFermaEPulisce() {
    source.failAfterBytes = 10
    val id = media(ShareSamples.gpsJpeg(folder.root), "image/jpeg")

    val result = prepare(id) as SharePreparation.Stopped

    assertThat(result.stop).isEqualTo(ShareStop.UNAVAILABLE)
    assertThat(leftovers()).isEmpty()
  }

  @Test
  fun annullareMentreSiPreparaTogliLeCopie_E_X4() {
    val gate = AtomicInteger(0)
    val slow = stripperDoing { src, dst ->
      gate.incrementAndGet()
      Thread.sleep(300)
      src.copyTo(dst)
      StripOutcome.Stripped("image/jpeg")
    }
    val sharing = LocationFreeShare(cache, source, slow, uris, revoker, Dispatchers.IO)
    val first = media(ShareSamples.gpsJpeg(folder.root, "a.jpg"), "image/jpeg")
    val second = media(ShareSamples.gpsJpeg(folder.root, "b.jpg"), "image/jpeg")

    runBlocking {
      val job = CoroutineScope(Dispatchers.Default).async { sharing.prepare(listOf(first, second), true) }
      while (gate.get() < 1) delay(10)
      job.cancelAndJoin()
    }

    assertThat(leftovers()).isEmpty()
    assertThat(cache.shareRoot.listFiles().orEmpty().toList()).isEmpty()
    assertThat(source.closed).isEqualTo(source.opened)
  }

  // --- Permessi e pulizia ------------------------------------------------------------------------------------------------------------

  @Test
  fun rilasciareRevocaIPermessiEToglieLeCopie_UnaVoltaSola() {
    val a = media(ShareSamples.gpsJpeg(folder.root, "a.jpg"), "image/jpeg", "A.jpg")
    val b = media(ShareSamples.gpsMp4(), "video/mp4", "B.mp4")
    val result = prepare(a, b) as SharePreparation.Ready
    val granted = result.files.map { it.uri }
    assertThat(leftovers()).hasSize(2)

    result.lease.release()
    result.lease.release() // la seconda non fa niente

    assertThat(revoker.revoked).containsExactlyElementsIn(granted)
    assertThat(leftovers()).isEmpty()
    assertThat(result.lease.isReleased).isTrue()
    assertThat(FlowCounters.share.get(ShareCounter.RELEASED)).isEqualTo(1L)
  }

  @Test
  fun lAvvioTogliTuttoCioCheERimastoInShareEInEditTrannePerLeSessioniAperte() {
    val keep = ShareCache.randomToken()
    val drop = ShareCache.randomToken()
    File(cache.shareRoot, "x/1/foto.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
    File(cache.editRoot, keep).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(2)) }
    File(cache.editRoot, drop).writeBytes(byteArrayOf(3))

    cache.sweepAtStart(keepEdit = setOf(keep))

    assertThat(cache.shareRoot.listFiles().orEmpty().toList()).isEmpty()
    assertThat(cache.editRoot.listFiles()!!.map { it.name }).containsExactly(keep)
  }

  @Test
  fun inCacheNonSiEsceDalleDueRadici() {
    val token = ShareCache.randomToken()
    assertThat(cache.editFile(token)).isEqualTo(File(cache.editRoot, token))
    for (hostile in listOf("..", "../share/x", "a/b", "/etc/passwd", "", "x".repeat(32), token.uppercase(), "$token/..", "$token.jpg")) {
      assertThat(cache.editFile(hostile)).isNull()
    }
    val outside = File(folder.root, "fuori.txt").apply { writeText("non toccare") }
    cache.deleteTree(outside)
    assertThat(outside.exists()).isTrue() // deleteTree non esce dalle due radici
  }
}
