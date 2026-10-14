package dev.pampa.mycloudgallery.core.index.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.IndexSyncStatus
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.replica.PathKeys
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.index.testing.FakeIndexPublisher
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.HQ
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.META
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.THUMB_M
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.THUMB_S
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La sincronizzazione sul NAS finto (`InMemoryRemoteIndex`): verifica senza novita', E-I5, E-I7, E-I8, E-I15, E-I17,
 * budget e ripresa, dati mobili, cancello.
 */
class IndexSyncEngineTest {

  @get:Rule val tmp = TemporaryFolder()

  private val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })

  /** Un dispositivo che pubblica in una share [count] file a partire da [from], con miniature S e M. */
  private fun publishFiles(share: String, device: String, from: Int, count: Int, seqBase: Long = 0, keepPrevious: Boolean = true) {
    val range = from until from + count
    val prepared = setOf(THUMB_S, THUMB_M)
    val records = range.map { n -> Fx.put("Foto/$n.jpg", seqBase + n + 1, Fx.file(n, prepared = prepared)) }
    nas.publish(
      share,
      device,
      listOf(
        Fx.meta(*records.toTypedArray()),
        Fx.images(THUMB_S, *range.map { Fx.key(it) to Fx.pixels(THUMB_S, it, device) }.toTypedArray()),
        Fx.images(THUMB_M, *range.map { Fx.key(it) to Fx.pixels(THUMB_M, it, device) }.toTypedArray()),
      ),
      keepPrevious = keepPrevious,
    )
  }

  @Test
  fun verificaSenzaNovitaConTreShareESeiDispositiviCostaAlPiu20Richieste() = runTest {
    val shares = listOf("alessio", "test-comune", "pampa")
    val devices = listOf("a", "b", "c", "d", "e", "f")
    var n = 0
    for (share in shares) for (device in devices) publishFiles(share, device, from = (n++) * 10, count = 10)
    val me = Harness("b", tmp.root, nas)
    val first = me.sync(*shares.toTypedArray())
    assertThat(first.complete).isTrue()
    assertThat(first.headsApplied).isEqualTo(18)

    nas.deviceListings.set(0)
    nas.headReads.set(0)
    nas.segmentReads.set(0)
    val again = me.sync(*shares.toTypedArray())
    // Per share un elenco di devices/ e una testa per ogni ALTRO dispositivo: 3 + 3 × 5 = 18.
    assertThat(me.requests()).isAtMost(20)
    assertThat(me.requests()).isEqualTo(18)
    assertThat(nas.segmentReads.get()).isEqualTo(0)
    assertThat(again.headsApplied).isEqualTo(0)
    assertThat(again.complete).isTrue()
    assertThat(me.engine.status.value).isEqualTo(IndexSyncStatus.UpToDate(Fx.NOW))
  }

  @Test
  fun eI7_laReplicaRicostruitaDaZeroEIdentica() = runTest {
    val me = Harness("b", tmp.newFolder("incrementale"), nas)
    val publisher = FakeIndexPublisher(sink = me.replica, remote = nas, deviceId = "b")

    publishFiles("alessio", "a", from = 1, count = 20)
    nas.publish("alessio", "c", listOf(Fx.meta(Fx.put("Foto/3.jpg", 1, Fx.file(3, media = 900, prepared = setOf(THUMB_S, THUMB_M, HQ))))))
    me.sync("alessio", "test-comune")

    // "a" aggiorna un file e ne toglie un altro; "b" (questo dispositivo) prepara e pubblica; "c" pubblica nell'area comune.
    nas.publish("alessio", "a", listOf(Fx.meta(Fx.put("Foto/1.jpg", 100, Fx.file(1, prepared = setOf(THUMB_S, THUMB_M, HQ))), Fx.gone("Foto/2.jpg", 101))))
    publisher.stage(
      "alessio",
      listOf(MetaChange.Put(Fx.path("Foto/50.jpg"), Fx.file(50, prepared = setOf(THUMB_S, THUMB_M)))),
      listOf(BlobPut.Image(THUMB_S, Fx.key(50), Fx.pixels(THUMB_S, 50, "b")), BlobPut.Image(THUMB_M, Fx.key(50), Fx.pixels(THUMB_M, 50, "b"))),
    )
    publisher.flush("alessio", FlushReason.UNIT_FINISHED)
    publishFiles("test-comune", "c", from = 60, count = 5)
    me.sync("alessio", "test-comune")

    // "c" compatta (testa che sostituisce tutto) e "a" pubblica HQ (non scaricata a Standard).
    nas.publish("alessio", "c", listOf(Fx.meta(Fx.put("Foto/3.jpg", 2, Fx.file(3, media = 900, prepared = setOf(THUMB_S))))), keepPrevious = false)
    nas.publish("alessio", "a", listOf(Fx.images(HQ, Fx.key(1) to Fx.pixels(HQ, 1, "a"))))
    publisher.stage("alessio", listOf(MetaChange.Remove(Fx.path("Foto/50.jpg"))), emptyList())
    publisher.flush("alessio", FlushReason.UNIT_FINISHED)
    me.sync("alessio", "test-comune")

    val fresh = Harness("b", tmp.newFolder("da-zero"), nas)
    val rebuilt = fresh.sync("alessio", "test-comune")
    assertThat(rebuilt.complete).isTrue()
    val expected = me.digest("alessio", "test-comune")
    assertThat(expected.keys.count { it.startsWith("blob|") }).isGreaterThan(40)
    assertThat(fresh.digest("alessio", "test-comune")).isEqualTo(expected)
    assertThat(fresh.catalog.identities()).isEqualTo(me.catalog.identities())
    // E il file tolto da "b" non c'e' piu' nella vista, ne' nell'una ne' nell'altra.
    assertThat(fresh.winner("alessio", "Foto/50.jpg")).isNull()
    assertThat(fresh.winner("alessio", "Foto/2.jpg")).isNull()
  }

  @Test
  fun laPropriaTestaSiLeggeSoloPerRicostruireEIPropriDatiSiVedonoSubito() = runTest {
    val me = Harness("b", tmp.root, nas)
    val publisher = FakeIndexPublisher(sink = me.replica, remote = nas, deviceId = "b")
    publisher.stage("alessio", listOf(MetaChange.Put(Fx.path("A/1.jpg"), Fx.file(1))), listOf(BlobPut.Image(THUMB_S, Fx.key(1), Fx.pixels(THUMB_S, 1))))
    // Visibili prima di qualunque pubblicazione (D31).
    assertThat(me.winner("alessio", "A/1.jpg")!!.first).isEqualTo("b")
    assertThat(me.blob(Fx.key(1), THUMB_S)).isEqualTo(Fx.pixels(THUMB_S, 1))
    publisher.flush("alessio", FlushReason.UNIT_FINISHED)

    me.sync("alessio")
    assertThat(nas.headReads.get()).isEqualTo(1)
    publisher.stage("alessio", listOf(MetaChange.Put(Fx.path("A/2.jpg"), Fx.file(2))), emptyList())
    publisher.flush("alessio", FlushReason.UNIT_FINISHED)
    me.sync("alessio")
    assertThat(nas.headReads.get()).isEqualTo(1)
    assertThat(me.winner("alessio", "A/2.jpg")!!.first).isEqualTo("b")
  }

  @Test
  fun eI5_unSegmentoRifiutatoNonFermaGliAltriEIDatiGiaNellaReplicaRestano() = runTest {
    val me = Harness("b", tmp.root, nas)
    publishFiles("alessio", "a", from = 1, count = 5)
    me.sync("alessio")
    val v2 = nas.publish("alessio", "a", listOf(Fx.meta(Fx.put("Foto/9.jpg", 50, Fx.file(9))), Fx.meta(Fx.put("Foto/10.jpg", 51, Fx.file(10)))))
    val known = me.store.segments("alessio", "a").map { it.hash }
    val bad = v2.segments.first { it.cls == META && it.ref.hash !in known }
    nas.rejectSegment(bad.ref.hash, RejectReason.MALFORMED)
    val report = me.sync("alessio")
    assertThat(report.segmentsRejected).containsExactly(RejectReason.MALFORMED, 1)
    assertThat(me.engine.counters.rejected()).containsEntry(RejectReason.MALFORMED, 1)
    (1..5).forEach { assertThat(me.winner("alessio", "Foto/$it.jpg")).isNotNull() }
    assertThat(listOf(me.winner("alessio", "Foto/9.jpg"), me.winner("alessio", "Foto/10.jpg")).count { it != null }).isEqualTo(1)
    assertThat(me.blob(Fx.key(1), THUMB_S)).isNotNull()

    // L'indice della share cancellato dal PC: le teste non si trovano piu', la replica resta com'e'.
    val before = me.digest("alessio")
    nas.wipeShare("alessio")
    me.sync("alessio")
    assertThat(me.digest("alessio")).isEqualTo(before)

    // Ricompare una testa piu' vecchia di quella accettata: "rollback" in Diagnostica, la replica non cambia.
    publishFiles("alessio", "a", from = 100, count = 1)
    val rolled = me.sync("alessio")
    assertThat(rolled.headsRejected).containsExactly(RejectReason.ROLLBACK, 1)
    assertThat(me.engine.counters.rollbacks.get()).isEqualTo(1)
    assertThat(me.digest("alessio")).isEqualTo(before)
  }

  @Test
  fun eI17_unSegmentoElencatoMaAssenteLasciaIDatiVecchiEIMediaNonCopertiTornanoDaPreparare() = runTest {
    val me = Harness("b", tmp.root, nas)
    val s = setOf(THUMB_S)
    nas.publish("alessio", "a", listOf(Fx.meta(Fx.put("F/1.jpg", 1, Fx.file(1, prepared = s))), Fx.images(THUMB_S, Fx.key(1) to Fx.pixels(THUMB_S, 1))))
    me.sync("alessio")
    assertThat(me.blob(Fx.key(1), THUMB_S)).isNotNull()

    // Compattazione: una testa nuova con tutto in due segmenti nuovi, ma quello delle miniature manca sul NAS.
    val v2 = nas.publish(
      "alessio",
      "a",
      listOf(
        Fx.meta(Fx.put("F/1.jpg", 2, Fx.file(1, prepared = s)), Fx.put("F/2.jpg", 3, Fx.file(2, prepared = s))),
        Fx.images(THUMB_S, Fx.key(1) to Fx.pixels(THUMB_S, 1), Fx.key(2) to Fx.pixels(THUMB_S, 2)),
      ),
      keepPrevious = false,
    )
    nas.dropSegment(v2.segments.first { it.cls == THUMB_S }.ref.hash)
    val report = me.sync("alessio")
    assertThat(report.segmentsMissing).isEqualTo(1)
    // I dati vecchi della classe restano (il segmento vecchio e' trattenuto), i record nuovi sono applicati.
    assertThat(me.blob(Fx.key(1), THUMB_S)).isEqualTo(Fx.pixels(THUMB_S, 1))
    assertThat(me.winner("alessio", "F/2.jpg")).isNotNull()
    // Per la scansione: il file 2 non e' coperto (torna "da preparare"), il file 1 si'.
    val stamps = mapOf(
      Fx.path("F/1.jpg") to NasStamp(Fx.content(1).size.toLong(), Fx.NOW),
      Fx.path("F/2.jpg") to NasStamp(Fx.content(2).size.toLong(), Fx.NOW),
    )
    val matched = me.replica.matching("alessio", stamps)
    assertThat(matched.getValue(Fx.path("F/1.jpg")).file.preparedClasses).containsExactly(THUMB_S)
    assertThat(matched.getValue(Fx.path("F/2.jpg")).file.preparedClasses).isEmpty()
    // Un giro senza testa nuova non riprova il segmento assente.
    val reads = nas.segmentReads.get()
    me.sync("alessio")
    assertThat(nas.segmentReads.get()).isEqualTo(reads)

    // Testa nuova completa: il trattenuto se ne va, tutto e' coperto.
    nas.publish(
      "alessio",
      "a",
      listOf(
        Fx.meta(Fx.put("F/1.jpg", 4, Fx.file(1, prepared = s)), Fx.put("F/2.jpg", 5, Fx.file(2, prepared = s))),
        Fx.images(THUMB_S, Fx.key(1) to Fx.pixels(THUMB_S, 1), Fx.key(2) to Fx.pixels(THUMB_S, 2)),
      ),
      keepPrevious = false,
    )
    me.sync("alessio")
    assertThat(me.store.segments("alessio", "a").filter { !it.listed }).isEmpty()
    assertThat(me.replica.matching("alessio", stamps).getValue(Fx.path("F/2.jpg")).file.preparedClasses).containsExactly(THUMB_S)
    // Un solo pacco nella replica (sul disco quello vecchio puo' restare finche' il sistema lo lascia cancellare).
    assertThat(me.store.packs()).hasSize(1)
  }

  @Test
  fun eI15_iDatiDiUnDispositivoRevocatoOPersoSpariscono() = runTest {
    val me = Harness("b", tmp.root, nas)
    publishFiles("alessio", "a", from = 1, count = 3)
    publishFiles("alessio", "c", from = 10, count = 3)
    publishFiles("test-comune", "d", from = 20, count = 3)
    me.sync("alessio", "test-comune")
    assertThat(me.winner("alessio", "Foto/1.jpg")).isNotNull()

    // La testa di "a" ora e' rifiutata perche' il firmatario e' revocato: i suoi dati via, quelli di "c" restano.
    nas.rejectHead("alessio", "a", RejectReason.SIGNER_REVOKED)
    val report = me.sync("alessio", "test-comune")
    assertThat(report.headsRejected).containsExactly(RejectReason.SIGNER_REVOKED, 1)
    assertThat(me.winner("alessio", "Foto/1.jpg")).isNull()
    assertThat(me.blob(Fx.key(1), THUMB_S)).isNull()
    assertThat(me.winner("alessio", "Foto/10.jpg")).isNotNull()
    assertThat(me.catalog.mediaAt("alessio", PathKeys.of(Fx.path("Foto/1.jpg")))).isNull()

    // "d" non compare piu' in devices/ (RemoteIndex tiene solo approvati e dismessi) ed e' perso nel registro: via.
    nas.wipeShare("test-comune")
    me.standings["d"] = DeviceStanding.LOST
    me.sync("alessio", "test-comune")
    assertThat(me.winner("test-comune", "Foto/20.jpg")).isNull()
    assertThat(me.engine.counters.devicesForgotten.get()).isEqualTo(2)
  }

  @Test
  fun eI8_unaShareNegataInDueGiorniDiversiSiToglieDallaReplica() = runTest {
    var now = Fx.NOW
    val local = InMemoryRemoteIndex(nasNow = { now })
    val me = Harness("b", tmp.root, local)
    local.publish("alessio", "a", listOf(Fx.meta(Fx.put("A/1.jpg", 1, Fx.file(1, prepared = setOf(THUMB_S)))), Fx.images(THUMB_S, Fx.key(1) to Fx.pixels(THUMB_S, 1))))
    me.sync("alessio")

    // Un solo errore, o errori nello stesso giorno, o la rete che cade: la share resta "non raggiungibile".
    local.failWith = RemoteFailure.ACCESS_DENIED
    me.sync("alessio")
    me.nasNow += 3_600_000
    me.sync("alessio")
    assertThat(me.engine.status.value).isEqualTo(IndexSyncStatus.Unavailable(RemoteFailure.ACCESS_DENIED))
    assertThat(me.winner("alessio", "A/1.jpg")).isNotNull()
    // Una risposta buona azzera il conto.
    local.failWith = null
    me.sync("alessio")
    local.failWith = RemoteFailure.SHARE_MISSING
    me.nasNow += ShareAccessPolicy.DAY_MILLIS
    me.sync("alessio")
    assertThat(me.winner("alessio", "A/1.jpg")).isNotNull()
    local.failWith = RemoteFailure.UNREACHABLE
    me.nasNow += 2 * ShareAccessPolicy.DAY_MILLIS
    me.sync("alessio")
    assertThat(me.winner("alessio", "A/1.jpg")).isNotNull()

    // Secondo giorno diverso con ACCESS_DENIED/SHARE_MISSING: via dalla replica (solo i dati locali).
    local.failWith = RemoteFailure.ACCESS_DENIED
    me.nasNow += ShareAccessPolicy.DAY_MILLIS
    me.sync("alessio")
    assertThat(me.engine.counters.sharesDropped.get()).isEqualTo(1)
    assertThat(me.winner("alessio", "A/1.jpg")).isNull()
    assertThat(me.blob(Fx.key(1), THUMB_S)).isNull()
    assertThat(me.store.pathKeys("alessio")).isEmpty()
    assertThat(me.store.heads("alessio")).isEmpty()

    // Torna accessibile: si ricostruisce da zero.
    local.failWith = null
    now = me.nasNow
    me.sync("alessio")
    assertThat(me.winner("alessio", "A/1.jpg")).isNotNull()
    assertThat(me.blob(Fx.key(1), THUMB_S)).isNotNull()
  }

  @Test
  fun ilBudgetLasciaIlPuntoDiRipresaNelDatabaseEIlGiroDopoNonRiscarica() = runTest {
    val me = Harness("b", tmp.root, nas)
    for (i in 0 until 4) publishFiles("alessio", "a", from = i * 10, count = 10)
    // Un "nanosecondo" per segmento scaricato: il budget ne permette 6.
    me.clock = { nas.segmentReads.get().toLong() }
    val partial = me.sync("alessio", budget = 6.nanoseconds)
    assertThat(partial.complete).isFalse()
    assertThat(me.engine.status.value).isInstanceOf(IndexSyncStatus.Aligning::class.java)
    val aligning = me.engine.status.value as IndexSyncStatus.Aligning
    assertThat(aligning.bytesDone).isLessThan(aligning.bytesTotal)
    assertThat(me.store.segmentsWhere(setOf(SegmentState.PENDING), setOf(THUMB_S, THUMB_M))).isNotEmpty()
    // Prima le miniature S (la griglia si riempie), poi le M.
    val applied = me.store.segmentsWhere(setOf(SegmentState.APPLIED), setOf(THUMB_S, THUMB_M))
    assertThat(applied.map { it.cls }.toSet()).containsExactly(THUMB_S)

    me.clock = null
    val rest = me.sync("alessio")
    assertThat(rest.complete).isTrue()
    // 4 meta + 4 S + 4 M, ognuno una volta sola.
    assertThat(nas.segmentReads.get()).isEqualTo(12)
    assertThat(me.store.segmentsWhere(setOf(SegmentState.PENDING), SegmentClass.entries.toSet())).isEmpty()
    (0 until 40).forEach { assertThat(me.blob(Fx.key(it), THUMB_M)).isNotNull() }
  }

  @Test
  fun conIDatiMobiliSoloIMetadatiPoiLeMiniatureAlGiroSuccessivo() = runTest {
    val me = Harness("b", tmp.root, nas)
    publishFiles("alessio", "a", from = 1, count = 5)
    me.sync("alessio", visibleOnly = true)
    assertThat(me.winner("alessio", "Foto/1.jpg")).isNotNull()
    assertThat(me.blob(Fx.key(1), THUMB_S)).isNull()
    assertThat(nas.segmentReads.get()).isEqualTo(1)

    me.sync("alessio")
    assertThat(me.blob(Fx.key(1), THUMB_S)).isEqualTo(Fx.pixels(THUMB_S, 1, "a"))
    assertThat(me.blob(Fx.key(1), THUMB_M)).isEqualTo(Fx.pixels(THUMB_M, 1, "a"))
  }

  @Test
  fun ilCancelloFermaIlGiroPrimaDiQualunqueRichiesta() = runTest {
    val me = Harness("b", tmp.root, nas)
    publishFiles("alessio", "a", from = 1, count = 5)
    me.block = SyncBlock.NOT_TRUSTED
    val report = me.sync("alessio")
    assertThat(report.complete).isFalse()
    assertThat(me.requests()).isEqualTo(0)
    assertThat(me.engine.status.value).isEqualTo(IndexSyncStatus.Unavailable(RemoteFailure.NOT_ALLOWED))
  }

  @Test
  fun iBlobSiPrendonoDaQualunqueDispositivoInModoDeterministico() = runTest {
    // "a" e "c" hanno preparato lo stesso contenuto (lavoro doppio, E-I1): si mostra quello del dispositivo minore,
    // e quando "a" sparisce resta quello di "c".
    publishFiles("alessio", "c", from = 1, count = 2)
    publishFiles("alessio", "a", from = 1, count = 2)
    val me = Harness("b", tmp.root, nas)
    me.sync("alessio")
    assertThat(me.blob(Fx.key(1), THUMB_S)).isEqualTo(Fx.pixels(THUMB_S, 1, "a"))
    nas.rejectHead("alessio", "a", RejectReason.SIGNER_REVOKED)
    me.sync("alessio")
    assertThat(me.blob(Fx.key(1), THUMB_S)).isEqualTo(Fx.pixels(THUMB_S, 1, "c"))
  }
}
