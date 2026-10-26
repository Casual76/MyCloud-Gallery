package dev.pampa.mycloudgallery.core.nas.ops.models

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.Harness
import dev.pampa.mycloudgallery.core.nas.SHARE
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.runBlockingBounded
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test

/**
 * MO-4 (revisione di sicurezza della Fase 6): la **scrittura a flusso** della copia di un modello sul NAS, sul simulatore Samba. Il modello pesa
 * fino a 370 MiB: si scrive a blocchi da 1 MiB in un temporaneo invisibile, si verifica la lunghezza sullo stesso handle e solo allora si da' il
 * nome definitivo con una rinomina senza sostituire. Prima dell'ultimo passo nessun altro vede una copia a meta'; un nome occupato non e' mai
 * sovrascritto; un'interruzione non lascia niente di visibile.
 */
class NasModelShelfOpsTest {

  private val me = Base32.encode(ByteArray(16) { 0x11 })
  private val other = Base32.encode(ByteArray(16) { 0x22 })
  private val purpose = Purpose.BACKGROUND

  /** 2,5 MiB: tre blocchi da 1 MiB, l'ultimo a meta'. */
  private val bytes = Random(7).nextBytes(2 * (1 shl 20) + (1 shl 19))
  private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
  private val finalPath get() = NasLayout.modelCopy(me, sha).value
  private val tmpDir = ".mycloudgallery/tmp"

  // Tempo e thread veri (come OwnHandlesTest): le letture della sorgente girano su Dispatchers.IO e con il tempo virtuale di runTest il tempo
  // saltava mentre il thread del test aspettava, facendo scadere la sessione a caso.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val clock = MonotonicClock { System.nanoTime() / 1_000_000 }
  private val harnessInstance = Harness(scope, clock, io = Dispatchers.IO)

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun harness(): Harness = harnessInstance

  private fun Harness.ops(): NasModelShelfOps = NasModelShelfOpsFactory.create(manager, me)

  private fun source(data: ByteArray = bytes, declared: Long = data.size.toLong(), onRead: (Long) -> Unit = {}) = object : NasModelCopySource {
    override val size: Long = declared

    override fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
      onRead(offset)
      if (offset >= data.size) return -1
      val n = minOf(len.toLong(), data.size - offset).toInt()
      System.arraycopy(data, offset.toInt(), buf, off, n)
      return n
    }
  }

  private fun Harness.temps(): List<String> = if (backend.exists(SHARE, tmpDir)) backend.children(SHARE, tmpDir) else emptyList()

  @Test
  fun laCopiaSiScriveABlocchiEIlTemporaneoNonSiVedeMaiComeCopia() = runBlockingBounded<Unit> {
    val h = harness()
    val seenDuring = mutableListOf<Pair<Long, Boolean>>()
    val ops = h.ops()
    val out = ops.publishCopy(SHARE, sha, source { offset -> seenDuring += offset to h.backend.exists(SHARE, finalPath) }, purpose)
    assertWithMessage("esito: $out").that(out.okOrNull()).isEqualTo(ModelCopyWrite.Written)
    assertThat(h.backend.bytes(SHARE, finalPath)).isEqualTo(bytes)
    // Il nome definitivo non esiste finche' non e' tutto scritto e verificato: nessuna lettura ha mai visto una copia a meta'.
    assertThat(seenDuring.size).isAtLeast(3)
    assertThat(seenDuring.none { it.second }).isTrue()
    // E il temporaneo non resta.
    assertThat(h.temps()).isEmpty()
  }

  @Test
  fun ilTemporaneoEUnTemporaneoProprioDellaPuliziaEstaNellaCartellaDelDispositivo() = runBlockingBounded<Unit> {
    val h = harness()
    var tempName: String? = null
    h.ops().publishCopy(SHARE, sha, source { if (tempName == null) tempName = h.temps().singleOrNull() }, purpose)
    val name = checkNotNull(tempName) { "nessun temporaneo visto durante la scrittura" }
    val path = NasLayout.ROOT.child("tmp").child(name)
    // La pulizia dei propri file lo riconosce come temporaneo del dispositivo (dopo un giorno toglie i resti di una morte del processo).
    assertThat(NasOpsPaths.isTempOf(path, me)).isTrue()
    assertThat(NasOpsPaths.isTempOf(path, other)).isFalse()
    assertThat(name).startsWith("$me-md-")
  }

  @Test
  fun unaSorgenteCheDaMenoByteDiQuelliDichiaratiFalliscePrimaDelNomeDefinitivo() = runBlockingBounded<Unit> {
    val h = harness()
    // Dichiara 2,5 MiB ma ne da' due: la lettura fallisce a meta' (sorgente rotta).
    val short = bytes.copyOf(2 * (1 shl 20))
    val out = h.ops().publishCopy(SHARE, sha, source(data = short, declared = bytes.size.toLong()), purpose)
    assertThat(out.errorOrNull()).isNotNull()
    assertThat(h.backend.exists(SHARE, finalPath)).isFalse()
    assertThat(h.temps()).isEmpty()
  }

  @Test
  fun unNomeOccupatoNonSiSostituisceMaiEIlTemporaneoSiToglie() = runBlockingBounded<Unit> {
    val h = harness()
    val theirs = ByteArray(bytes.size) { 5 }
    h.backend.putContent(SHARE, finalPath, NasContent.of(theirs))
    val out = h.ops().publishCopy(SHARE, sha, source(), purpose)
    assertThat(out.okOrNull()).isEqualTo(ModelCopyWrite.Exists)
    assertThat(h.backend.bytes(SHARE, finalPath)).isEqualTo(theirs)
    assertThat(h.temps()).isEmpty()
  }

  @Test
  fun unaCartellaAlPostoDellaCopiaNonSiSostituisce() = runBlockingBounded<Unit> {
    val h = harness()
    h.backend.putDir(SHARE, finalPath)
    val out = h.ops().publishCopy(SHARE, sha, source(), purpose)
    assertWithMessage("cartella al posto del file: $out").that(out.okOrNull()).isNotEqualTo(ModelCopyWrite.Written)
    assertThat(h.backend.bytes(SHARE, finalPath)).isNull()
    assertThat(h.temps()).isEmpty()
  }

  @Test
  fun unaQuotaPienaFalliscePulitoSenzaLasciareNiente() = runBlockingBounded<Unit> {
    val h = harness()
    h.backend.addShare("piccola", mapOf(dev.pampa.mycloudgallery.core.nas.USER to SimAccess.READ_WRITE), quotaBytes = 1_000_000)
    val out = h.ops().publishCopy("piccola", sha, source(), purpose)
    assertThat(out.errorOrNull()).isEqualTo(NasError.QuotaExceeded)
    assertThat(h.backend.exists("piccola", finalPath)).isFalse()
    assertThat(h.backend.exists("piccola", tmpDir) && h.backend.children("piccola", tmpDir).isNotEmpty()).isFalse()
  }

  @Test
  fun annullandoAMetaNonRestaNienteDiVisibileENiente_DiCopiato() = runBlockingBounded<Unit> {
    val h = harness()
    lateinit var job: Job
    var result: NasResult<ModelCopyWrite>? = null
    var reads = 0
    job = launch {
      result = h.ops().publishCopy(SHARE, sha, source { offset -> reads++; if (offset >= (1 shl 20)) job.cancel() }, purpose)
    }
    job.join()
    assertWithMessage("esito=$result, letture=$reads").that(job.isCancelled).isTrue()
    assertThat(h.backend.exists(SHARE, finalPath)).isFalse()
    assertThat(h.temps()).isEmpty()
  }

  @Test
  fun laCopiaSiScriveSoloNellaCartellaDelProprioDispositivo() = runBlockingBounded<Unit> {
    val h = harness()
    h.ops().publishCopy(SHARE, sha, source(), purpose)
    assertThat(finalPath).isEqualTo(".mycloudgallery/devices/$me/models/$sha")
    // Nessun file e' comparso altrove: la copia e il suo (ormai assente) temporaneo.
    assertThat(h.backend.fileCount(SHARE)).isEqualTo(1)
  }

  @Test
  fun unaDimensioneNonAmmessaOUnaImprontaNonValidaNonScrivonoNiente() = runBlockingBounded<Unit> {
    val h = harness()
    val ops = h.ops()
    val before = h.backend.shareOps.get()
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { ops.publishCopy(SHARE, sha, source(data = ByteArray(0), declared = 0), purpose) }
    }
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { ops.publishCopy(SHARE, "../x", source(), purpose) }
    }
    assertThat(h.backend.shareOps.get()).isEqualTo(before)
    assertThat(h.backend.fileCount(SHARE)).isEqualTo(0)
  }

  // --- La rimozione delle proprie copie -----------------------------------------------------------------------------------------

  @Test
  fun siTogliSoloLaPropriaCopiaEPurche_NonSiaCambiata() = runBlockingBounded<Unit> {
    val h = harness()
    val ops = h.ops()
    val old = ByteArray(10) { 1 }
    val oldSha = "a".repeat(64)
    h.backend.putContent(SHARE, NasLayout.modelCopy(me, oldSha).value, NasContent.of(old))
    h.backend.putContent(SHARE, NasLayout.modelCopy(other, oldSha).value, NasContent.of(old))
    // La dimensione e' cambiata dopo l'elenco: non si tocca.
    assertThat(ops.deleteOwnCopy(SHARE, oldSha, size = 11, purpose = purpose).okOrNull()).isEqualTo(ModelCopyDelete.SizeChanged)
    assertThat(h.backend.exists(SHARE, NasLayout.modelCopy(me, oldSha).value)).isTrue()
    assertThat(ops.deleteOwnCopy(SHARE, oldSha, size = 10, purpose = purpose).okOrNull()).isEqualTo(ModelCopyDelete.Deleted)
    assertThat(h.backend.exists(SHARE, NasLayout.modelCopy(me, oldSha).value)).isFalse()
    // La copia di un altro dispositivo non e' raggiungibile da qui.
    assertThat(h.backend.exists(SHARE, NasLayout.modelCopy(other, oldSha).value)).isTrue()
    assertThat(ops.deleteOwnCopy(SHARE, oldSha, size = 10, purpose = purpose).okOrNull()).isEqualTo(ModelCopyDelete.Missing)
  }

  // --- L'insieme chiuso dei metodi ------------------------------------------------------------------------------------------------

  /** Il confine di sicurezza e' il perimetro: due metodi e basta, nessuno scrive o toglie un percorso qualunque. */
  @Test
  fun ilContrattoHaSoloLeDueOperazioniDelloScaffale() {
    val methods = NasModelShelfOps::class.java.declaredMethods.map { it.name }.toSet()
    assertThat(methods).containsExactly("getOwnerDeviceId", "publishCopy", "deleteOwnCopy")
    val impl = SmbNasModelShelfOps::class.java.declaredMethods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }.map { it.name }.toSet()
    assertThat(impl.filterNot { it.contains("$") }).containsExactly("getOwnerDeviceId", "publishCopy", "deleteOwnCopy")
  }

  @Test
  fun senzaUnaSessioneSmbOgniChiamataENegataPrimaDiToccareLaRete() = runBlockingBounded<Unit> {
    val ops = NasModelShelfOpsFactory.create(dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager(), me)
    assertThat(ops).isInstanceOf(NoNasModelShelfOps::class.java)
    assertThat(ops.publishCopy(SHARE, sha, source(), purpose)).isInstanceOf(NasResult.Err::class.java)
    assertThat(ops.deleteOwnCopy(SHARE, sha, 1, purpose)).isInstanceOf(NasResult.Err::class.java)
  }
}
