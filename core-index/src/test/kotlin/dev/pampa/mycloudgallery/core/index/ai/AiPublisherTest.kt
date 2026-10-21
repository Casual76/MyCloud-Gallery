package dev.pampa.mycloudgallery.core.index.ai

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.p3.FaultPlan
import dev.pampa.mycloudgallery.core.index.p3.headVersions
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Il pubblicatore della testa `ai` (D8; formato-nas.md §5.5 "Testa `ai`"): M3 con le chiavi di contenuto, cancelli dell'indice,
 * una testa ogni 5 minuti salvo la fine di un'unita', blocco permanente (F4-D31) = risultati in attesa e pubblicati dopo **senza
 * ricalcolo**, NAS pieno (E-I6).
 */
class AiPublisherTest {

  @get:Rule val tmp = TemporaryFolder()

  private val common = "test-comune"

  @Test
  fun stageEFlush_unaTestaAiConISegmentiVecEOcr_eNessunaTestaIndex() = runTest {
    val ai = AiWorld(tmp.root)
    val phone = ai.device()
    val files = (1..3).map { ai.file(common, "Foto/IMG_$it.jpg") }
    val entries = files.map { phone.vector(it) } + files.take(2).map { phone.text(it) }
    val publisher = phone.publisher()
    publisher.stage(common, entries)
    assertThat(publisher.pending.value).isEqualTo(AiPendingCounts(5, 0))

    val outcome = publisher.flush(common, FlushReason.UNIT_FINISHED)

    assertThat(outcome).isEqualTo(PublishOutcome.Published(headVersion = 1, segments = 2, bytes = (outcome as PublishOutcome.Published).bytes))
    assertThat(publisher.pending.value).isEqualTo(AiPendingCounts.EMPTY)
    assertThat(aiReaderState(ai, common, phone.id)).isEqualTo(entries.associate { it.key to setOf(it.digest()) })
    assertThat(phone.aiHeads()).containsExactly(1L)
    // La testa `index` non c'e': i dati AI stanno solo in `ai/` (D6).
    assertThat(headVersions(ai.world, common, phone.id)).isEmpty()
    assertThat(ai.aiFiles(common).keys.all { it.startsWith(".mycloudgallery/devices/${phone.id}/ai/") }).isTrue()
    assertThat(phone.counters.published.get()).isEqualTo(5)
  }

  /**
   * Proprieta' M3 (C19; formato-nas.md §5.5 "Testa `ai`"): per 300 giri un chiamante "sbadato" mescola nella stessa chiamata voci di
   * file dell'area comune, dell'area privata e di file cambiati dopo la lettura. Nell'indice dell'area comune finiscono **solo** chiavi
   * di file dell'area comune con timbro fresco; tutto il resto si conta e non esce.
   */
  @Test
  fun m3_nessunaVocePerContenutiDiUnAltraShare_proprieta() = runTest {
    val ai = AiWorld(tmp.root)
    val phone = ai.device()
    val random = Random(42)
    val shared = (1..40).map { ai.file(common, "Comune/IMG_$it.jpg") }
    val private = (1..40).map { ai.file("test", "Privata/IMG_$it.jpg") }
    val changed = (1..10).map { ai.file(common, "Cambiate/IMG_$it.jpg").also { f -> ai.content.remove(common, f.contentKey) } }
    val publisher = phone.publisher()
    var foreign = 0
    val expected = HashMap<AiKey, String>()
    repeat(300) {
      val pick = when (random.nextInt(3)) {
        0 -> shared
        1 -> private
        else -> changed
      }.random(random)
      val entry = if (random.nextBoolean()) phone.vector(pick, seed = random.nextInt()) else phone.text(pick, "riga ${random.nextInt(1000)}")
      publisher.stage(common, listOf(entry))
      if (pick in shared) expected[entry.key] = entry.digest() else foreign++
      if (random.nextInt(10) == 0) {
        ai.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
        publisher.flush(common, FlushReason.PERIODIC)
      }
    }
    publisher.flush(common, FlushReason.UNIT_FINISHED)

    val published = aiReaderState(ai, common, phone.id)
    val allowed = shared.map { it.contentKey }.toSet()
    assertThat(published.keys.map { it.contentKey }.filterNot { it in allowed }).isEmpty()
    // L'ultimo corpo di ogni voce dell'area comune e' fra quelli pubblicati (i superati restano nei segmenti di prima).
    for ((key, digest) in expected) assertWithMessage("voce pubblicata").that(published[key]).contains(digest)
    assertThat(phone.counters.foreign.get()).isEqualTo(foreign)
    // Niente nell'area privata: il pubblicatore non scrive dove non gli si chiede.
    assertThat(ai.aiFiles("test")).isEmpty()
  }

  @Test
  fun unCorpoIdenticoNonSiRipubblica_unaRicettaNuovaSi() = runTest {
    val ai = AiWorld(tmp.root)
    val phone = ai.device()
    val file = ai.file(common, "Foto/IMG_1.jpg")
    val publisher = phone.publisher()
    publisher.stage(common, listOf(phone.vector(file)))
    publisher.flush(common, FlushReason.UNIT_FINISHED)
    // Una ripresa che rimette in fila lo stesso risultato (anche due volte): niente di nuovo.
    publisher.stage(common, listOf(phone.vector(file)))
    publisher.stage(common, listOf(phone.vector(file)))
    assertThat(publisher.pending.value.staged).isEqualTo(0)
    assertThat(publisher.flush(common, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.NothingPending)
    assertThat(phone.counters.unchanged.get()).isEqualTo(2)
    // Una ricetta nuova (D21) si pubblica in una testa nuova.
    publisher.stage(common, listOf(phone.vector(file, recipe = 2)))
    assertThat(publisher.flush(common, FlushReason.UNIT_FINISHED)).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(phone.aiHeads()).containsExactly(1L, 2L).inOrder()
    assertThat(aiReaderState(ai, common, phone.id)[AiKey(file.contentKey, AiClass.VEC)]).hasSize(2)
  }

  @Test
  fun unaTestaOgniCinqueMinuti_salvoLaFineDiUnUnita() = runTest {
    val ai = AiWorld(tmp.root)
    val phone = ai.device()
    val files = (1..3).map { ai.file(common, "Foto/IMG_$it.jpg") }
    val publisher = phone.publisher()
    publisher.stage(common, listOf(phone.vector(files[0])))
    assertThat(publisher.flush(common, FlushReason.PERIODIC)).isInstanceOf(PublishOutcome.Published::class.java)
    publisher.stage(common, listOf(phone.vector(files[1])))
    val tooSoon = publisher.flush(common, FlushReason.PERIODIC)
    assertThat(tooSoon).isEqualTo(PublishOutcome.TooSoon(ai.clock.nowMillis() + TrustLimits.HEAD_MIN_INTERVAL_MILLIS))
    assertThat(publisher.flush(common, FlushReason.UNIT_FINISHED)).isInstanceOf(PublishOutcome.Published::class.java)
    publisher.stage(common, listOf(phone.vector(files[2])))
    ai.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    assertThat(publisher.flush(common, FlushReason.PERIODIC)).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(phone.aiHeads()).containsExactly(1L, 2L, 3L).inOrder()
  }

  @Test
  fun cancelliDellIndice_bloccoTemporaneoLasciaTuttoNellOutbox_fiduciaPersaNonFirma() = runTest {
    val ai = AiWorld(tmp.root)
    val phone = ai.device()
    val file = ai.file(common, "Foto/IMG_1.jpg")
    val publisher = phone.publisher()
    publisher.stage(common, listOf(phone.vector(file)))
    for (block in listOf(WriteBlock.FROZEN, WriteBlock.CLOCK_JUMP, WriteBlock.EMERGENCY_STOP)) {
      phone.gate.block(common, block)
      assertThat(publisher.flush(common, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Blocked(block))
      assertThat(publisher.pending.value).isEqualTo(AiPendingCounts(1, 0))
    }
    phone.gate.block(common, null)
    ai.world.trust.changeStatus(phone.base.signer, DeviceStatus.REVOKED)
    assertThat(publisher.flush(common, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Blocked(WriteBlock.NOT_TRUSTED))
    assertThat(ai.aiFiles(common)).isEmpty()
  }

  /**
   * F4-D31 e D8: su una share vera con la pubblicazione solo sulle share di prova i risultati restano in attesa (con il corpo gia'
   * calcolato) e la testa `ai` non si scrive; quando la share si apre si pubblicano **gli stessi corpi**, senza ricalcolarli. Una voce
   * il cui file nel frattempo e' cambiato non si ripubblica.
   */
  @Test
  fun f4d31_iRisultatiRestanoLocaliESiPubblicanoDopoSenzaRicalcolo() = runTest {
    val ai = AiWorld(tmp.root)
    val phone = ai.device()
    val real = "alessio"
    val files = (1..4).map { ai.file(real, "Foto/IMG_$it.jpg") }
    var publisher = phone.publisher()
    val entries = files.map { phone.vector(it) } + files.map { phone.text(it) }
    val computedBefore = phone.computed
    publisher.stage(real, entries)
    assertThat(publisher.pending.value).isEqualTo(AiPendingCounts(staged = 0, waitingForShare = 8))
    assertThat(publisher.flush(real, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Blocked(WriteBlock.LOCAL_ONLY))
    assertThat(publisher.releasePending(real)).isEqualTo(0)
    assertThat(ai.aiFiles(real)).isEmpty()

    // Un file cambia (scansione: timbro nuovo) mentre aspetta.
    ai.content.remove(real, files[3].contentKey)
    // L'utente spegne D31 (fine della 4A): processo nuovo con le regole nuove, stesso database.
    phone.flags = FeatureFlags.forBuild(debugBuild = true, indexPublishTestSharesOnly = false)
    publisher = phone.publisher()
    publisher.recover()
    assertThat(publisher.pending.value.waitingForShare).isEqualTo(8)
    assertThat(publisher.releasePending(real)).isEqualTo(6)
    assertThat(publisher.flush(real, FlushReason.UNIT_FINISHED)).isInstanceOf(PublishOutcome.Published::class.java)

    assertThat(phone.computed).isEqualTo(computedBefore)
    val expected = entries.filter { it.contentKey != files[3].contentKey }.associate { it.key to setOf(it.digest()) }
    assertThat(aiReaderState(ai, real, phone.id)).isEqualTo(expected)
    assertThat(publisher.pending.value).isEqualTo(AiPendingCounts.EMPTY)
  }

  @Test
  fun areaInSolaLetturaScopertaAlFlush_leVociVannoInAttesaENonSiPerdono() = runTest {
    val ai = AiWorld(tmp.root)
    val phone = ai.device()
    val files = (1..2).map { ai.file(common, "Foto/IMG_$it.jpg") }
    val publisher = phone.publisher()
    publisher.stage(common, files.map { phone.vector(it) })
    phone.gate.block(common, WriteBlock.READ_ONLY_AREA)
    assertThat(publisher.flush(common, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Blocked(WriteBlock.READ_ONLY_AREA))
    assertThat(publisher.pending.value).isEqualTo(AiPendingCounts(staged = 0, waitingForShare = 2))
    // Altre voci con il blocco gia' noto: direttamente in attesa.
    publisher.stage(common, listOf(phone.text(files[0])))
    assertThat(publisher.pending.value).isEqualTo(AiPendingCounts(staged = 0, waitingForShare = 3))
    assertThat(publisher.hasPending(common, files.map { it.contentKey }.toSet())).isFalse()
    phone.gate.block(common, null)
    assertThat(publisher.releasePending(common)).isEqualTo(3)
    assertThat(publisher.hasPending(common, setOf(files[0].contentKey))).isTrue()
    assertThat(publisher.flush(common, FlushReason.UNIT_FINISHED)).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(aiReaderState(ai, common, phone.id)).hasSize(3)
  }

  @Test
  fun nasPieno_siPubblicaPerClasse_eIlRestoAspetta() = runTest {
    val ai = AiWorld(tmp.root)
    val phone = ai.device()
    val files = (1..3).map { ai.file(common, "Foto/IMG_$it.jpg") }
    val plan = FaultPlan()
    var segmentPublishes = 0
    plan.failWhen = { op, path ->
      if (op == "publish" && "/ai/seg/" in path.value && ++segmentPublishes == 2) NasError.NasFull else null
    }
    val publisher = phone.publisher(plan)
    publisher.stage(common, files.map { phone.vector(it) } + files.map { phone.text(it) })
    val partial = publisher.flush(common, FlushReason.UNIT_FINISHED)
    assertThat(partial).isInstanceOf(PublishOutcome.Partial::class.java)
    partial as PublishOutcome.Partial
    assertThat(partial.published).containsExactly(SegmentClass.VEC)
    assertThat(partial.failure).isEqualTo(RemoteFailure.DISK_FULL)
    assertThat(publisher.pending.value.staged).isEqualTo(3)
    assertThat(publisher.flush(common, FlushReason.UNIT_FINISHED)).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(aiReaderState(ai, common, phone.id)).hasSize(6)
    assertThat(phone.aiHeads()).containsExactly(1L, 2L).inOrder()
  }

  @Test
  fun unaPubblicazioneNonCaricaTuttoLArretrato_ilRestoAllaPubblicazioneDopo() = runTest {
    val ai = AiWorld(tmp.root)
    val phone = ai.device()
    val files = (1..5).map { ai.file(common, "Foto/IMG_$it.jpg") }
    val publisher = phone.publisher(maxRowsPerFlush = 2)
    val entries = files.map { phone.vector(it) }
    publisher.stage(common, entries)
    assertThat(publisher.flush(common, FlushReason.UNIT_FINISHED)).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(publisher.pending.value.staged).isEqualTo(3)
    while (publisher.flush(common, FlushReason.UNIT_FINISHED) != PublishOutcome.NothingPending) Unit
    assertThat(aiReaderState(ai, common, phone.id)).isEqualTo(entries.associate { it.key to setOf(it.digest()) })
    assertThat(phone.aiHeads()).containsExactly(1L, 2L, 3L).inOrder()
  }

  @Test
  fun leShareNonPiuConfigurateSiTolgono_eICorpiNonAmmessiNonEntrano() = runTest {
    val ai = AiWorld(tmp.root)
    val phone = ai.device()
    val kept = ai.file(common, "Foto/IMG_1.jpg")
    val gone = ai.file("test", "Foto/IMG_2.jpg")
    val publisher = phone.publisher()
    publisher.stage(common, listOf(phone.vector(kept)))
    publisher.stage("test", listOf(phone.vector(gone)))
    assertThat(publisher.purge(setOf(common.uppercase()))).isEqualTo(1)
    assertThat(publisher.pending.value.staged).isEqualTo(1)
    // Un testo con un carattere di direzione: chi legge lo rifiuterebbe, quindi non entra (contato).
    publisher.stage(common, listOf(AiEntry.Text(kept.contentKey, AiBodies.ocr("Totale‮euro"))))
    assertThat(phone.counters.unpublishable.get()).isEqualTo(1)
    assertThat(publisher.pending.value.staged).isEqualTo(1)
  }
}
