package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.dao.AiKeyRow
import dev.pampa.mycloudgallery.core.database.entity.AiOwnHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.AiOwnLiveEntity
import dev.pampa.mycloudgallery.core.database.entity.AiOwnSegmentEntity
import dev.pampa.mycloudgallery.core.database.entity.AiPendingPublishEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaAppliedEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaDirtyEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaHeadEntity
import dev.pampa.mycloudgallery.core.database.entity.AiReplicaSegmentEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le tabelle `ai_*` dell'indice condiviso (schema v5; D7, D8): i DAO bastano a scrivere gli adattatori di `AiPublishStore` e
 * `AiReplicaStore` (P11) con **una transazione per metodo**. Qui le stesse sequenze delle porte (`AiStores.kt` di core-index), con i
 * DAO dentro `GalleryDatabase.transaction`, e la prova che una transazione interrotta non lascia niente a meta'.
 */
@RunWith(AndroidJUnit4::class)
class AiShareTablesTest : CatalogTestBase() {

  private val publish get() = db.aiPublishDao()
  private val replica get() = db.aiReplicaDao()

  private fun key(n: Int) = n.toString(16).padStart(64, '0')

  private fun pending(share: String, state: String, n: Int, cls: Int = 7, recipe: Int = 1) =
    AiPendingPublishEntity(share = share, state = state, cls = cls, contentKey = key(n), recipe = recipe, body = byteArrayOf(n.toByte(), 1), digest = "d$n")

  // --- Pubblicazione ---------------------------------------------------------------------------------------------------

  @Test
  fun lOutboxELeVociInAttesaStannoInUnaTabellaSolaConIdCrescentiMaiRiusati() = runBlocking<Unit> {
    val ids = db.transaction { publish.stage(listOf(pending("alessio", "outbox", 1), pending("alessio", "outbox", 2), pending("nas", "held", 3))) }
    assertThat(ids).isInOrder()
    assertThat(publish.rows("alessio", "outbox", 10).map { it.contentKey }).containsExactly(key(1), key(2)).inOrder()
    assertThat(publish.rows("alessio", "outbox", 1).single().contentKey).isEqualTo(key(1))
    assertThat(publish.rows("alessio", "held", 10)).isEmpty()
    assertThat(publish.refs("alessio", "outbox").map { it.digest }).containsExactly("d1", "d2").inOrder()
    assertThat(publish.count("outbox")).isEqualTo(2)
    assertThat(publish.count("held")).isEqualTo(1)
    assertThat(publish.shares()).containsExactly("alessio", "nas").inOrder()

    // Spostare e' un UPDATE: l'id e il corpo restano, cambia lo stato (outbox <-> in attesa).
    publish.move("alessio", listOf(ids[0]), "held")
    assertThat(publish.rows("alessio", "held", 10).single().id).isEqualTo(ids[0])
    assertThat(publish.rows("alessio", "held", 10).single().body.toList()).containsExactly(1.toByte(), 1.toByte()).inOrder()
    // Un id di un'altra share non si muove.
    publish.move("alessio", listOf(ids[2]), "outbox")
    assertThat(publish.rows("nas", "held", 10)).hasSize(1)

    // AUTOINCREMENT: l'id piu' alto tolto non si riusa (per (classe, chiave) vince la riga piu' recente).
    publish.discard("nas", listOf(ids[2]))
    val next = publish.stage(listOf(pending("nas", "outbox", 4))).single()
    assertThat(next).isGreaterThan(ids.max())
    assertThat(publish.shares()).containsExactly("alessio", "nas").inOrder()
    publish.discard("nas", listOf(next))
    assertThat(publish.shares()).containsExactly("alessio")
  }

  @Test
  fun unaPubblicazioneEAtomica_TestaSegmentiVociVivaEOutbox() = runBlocking<Unit> {
    val ids = publish.stage(listOf(pending("alessio", "outbox", 1), pending("alessio", "outbox", 2), pending("alessio", "outbox", 3, cls = 8)))
    // La testa 1 elenca due segmenti.
    db.transaction {
      publish.saveOwnHead(AiOwnHeadEntity("alessio", 1, "h1", 1_000))
      publish.saveOwnSegments(listOf(AiOwnSegmentEntity("alessio", "s-b", 7, 100, 2), AiOwnSegmentEntity("alessio", "s-a", 8, 50, 1)))
      publish.saveOwnLive(listOf(AiOwnLiveEntity("alessio", 7, key(1), 1, "d1", "s-b"), AiOwnLiveEntity("alessio", 7, key(2), 1, "d2", "s-b")))
      publish.discard("alessio", ids.take(2))
    }
    assertThat(publish.ownSegments("alessio").map { it.hash }).containsExactly("s-a", "s-b").inOrder()
    assertThat(publish.ownLive("alessio", 7, listOf(key(1), key(2), key(3))).map { it.contentKey }).containsExactly(key(1), key(2))
    assertThat(publish.ownLive("alessio", 8, listOf(key(1))).map { it.contentKey }).isEmpty()
    assertThat(publish.rows("alessio", "outbox", 10).map { it.contentKey }).containsExactly(key(3))

    // Una morte nel mezzo di una testa nuova (compattazione): niente cambia.
    runCatching {
      db.transaction {
        publish.saveOwnHead(AiOwnHeadEntity("alessio", 2, "h2", 2_000))
        publish.saveOwnSegments(listOf(AiOwnSegmentEntity("alessio", "s-c", 7, 90, 2)))
        publish.deleteOwnSegmentsNotIn("alessio", listOf("s-c"))
        publish.discard("alessio", ids.drop(2))
        error("morte del processo")
      }
    }
    assertThat(publish.ownHead("alessio")!!.version).isEqualTo(1)
    assertThat(publish.ownSegments("alessio").map { it.hash }).containsExactly("s-a", "s-b")
    assertThat(publish.rows("alessio", "outbox", 10)).hasSize(1)

    // La stessa transazione, senza la morte: la compattazione sostituisce i segmenti e ritrova le voci di un segmento tolto.
    db.transaction {
      publish.saveOwnHead(AiOwnHeadEntity("alessio", 2, "h2", 2_000))
      publish.saveOwnSegments(listOf(AiOwnSegmentEntity("alessio", "s-c", 7, 90, 2)))
      publish.deleteOwnSegmentsNotIn("alessio", listOf("s-c"))
    }
    assertThat(publish.ownSegments("alessio").map { it.hash }).containsExactly("s-c")
    assertThat(publish.ownLiveInSegment("alessio", "s-b").map { it.contentKey }).containsExactly(key(1), key(2))
    publish.saveOwnLive(listOf(AiOwnLiveEntity("alessio", 7, key(1), 2, "d1b", "s-c")))
    assertThat(publish.ownLive("alessio", 7, listOf(key(1))).single().recipe).isEqualTo(2)
  }

  // --- Replica ---------------------------------------------------------------------------------------------------------

  private fun entry(device: String, segment: String, n: Int, cls: Int = 7, recipe: Int = 1) =
    AiReplicaEntryEntity("alessio", device, segment, cls, key(n), recipe, "g$n$device", byteArrayOf(n.toByte()))

  /** `applyHead` della porta: testa, segmenti, voci dei segmenti scaricati; tocca le chiavi dei segmenti sostituiti o tolti. */
  private suspend fun applyHead(device: String, version: Long, listed: List<AiReplicaSegmentEntity>, downloaded: Map<String, List<AiReplicaEntryEntity>>): Set<Pair<Int, String>> =
    db.transaction {
      val touched = HashSet<Pair<Int, String>>()
      val keep = listed.map { it.hash }.toSet()
      for (old in replica.segments("alessio", device).filter { it.hash !in keep }) {
        touched += replica.keysOfSegment("alessio", device, old.hash).map { it.cls to it.contentKey }
        replica.deleteEntriesOfSegment("alessio", device, old.hash)
        replica.deleteSegment("alessio", device, old.hash)
      }
      for ((hash, rows) in downloaded) {
        touched += replica.keysOfSegment("alessio", device, hash).map { it.cls to it.contentKey }
        replica.deleteEntriesOfSegment("alessio", device, hash)
        replica.putEntries(rows)
        touched += rows.map { it.cls to it.contentKey }
      }
      replica.saveSegments(listed)
      replica.saveHead(AiReplicaHeadEntity("alessio", device, version, "c$version", 1, 5_000))
      replica.markDirty(touched.map { (cls, k) -> AiReplicaDirtyEntity("alessio", cls, k) })
      touched
    }

  private fun segment(device: String, hash: String, cls: Int = 7, state: String = "applied", entries: Int = 0) =
    AiReplicaSegmentEntity("alessio", device, hash, cls, 10, state, entries)

  @Test
  fun unaTestaSiApplicaInUnaTransazioneEISegmentiRiscaricatiNonRaddoppiano() = runBlocking<Unit> {
    val touched = applyHead("tablet", 1, listOf(segment("tablet", "s1", entries = 2)), mapOf("s1" to listOf(entry("tablet", "s1", 1), entry("tablet", "s1", 2))))
    assertThat(touched).containsExactly(7 to key(1), 7 to key(2))
    assertThat(replica.heads("alessio").single().version).isEqualTo(1)
    assertThat(replica.dirty("alessio").map { it.contentKey }).containsExactly(key(1), key(2)).inOrder()
    assertThat(replica.keys("alessio")).hasSize(2)

    // Lo stesso segmento riscaricato: le stesse voci, non il doppio. Un'altra testa: un altro dispositivo, la stessa chiave.
    applyHead("tablet", 1, listOf(segment("tablet", "s1", entries = 2)), mapOf("s1" to listOf(entry("tablet", "s1", 1), entry("tablet", "s1", 2))))
    applyHead("telefono", 1, listOf(segment("telefono", "s9", entries = 1)), mapOf("s9" to listOf(entry("telefono", "s9", 1, recipe = 2))))
    val candidates = replica.candidates("alessio", 7, listOf(key(1), key(2), key(3)))
    assertThat(candidates.map { it.device to it.contentKey }).containsExactly("tablet" to key(1), "tablet" to key(2), "telefono" to key(1))
    assertThat(candidates.first { it.device == "telefono" }.recipe).isEqualTo(2)
    // `vec` e `ocr` sono classi indipendenti: la stessa chiave in un'altra classe non e' un candidato di questa.
    assertThat(replica.candidates("alessio", 8, listOf(key(1)))).isEmpty()
    assertThat(replica.body("alessio", "tablet", "s1", 7, key(2))!!.toList()).containsExactly(2.toByte())
    assertThat(replica.body("alessio", "tablet", "s1", 7, key(3))).isNull()
    assertThat(replica.keys("alessio")).hasSize(2)
    assertThat(replica.shares()).containsExactly("alessio")
  }

  @Test
  fun unaTestaConUnSegmentoInMenoTogliLeSueVociEConsegnaEChiaviDaRifondere() = runBlocking<Unit> {
    applyHead("tablet", 1, listOf(segment("tablet", "s1"), segment("tablet", "s2", cls = 8)), mapOf("s1" to listOf(entry("tablet", "s1", 1)), "s2" to listOf(entry("tablet", "s2", 2, cls = 8))))
    // La fusione consegna all'indice locale e libera le chiavi da rifondere (`commitApplied`).
    db.transaction {
      replica.deleteApplied("alessio", 7, listOf(key(1)))
      replica.saveApplied(listOf(AiReplicaAppliedEntity("alessio", 7, key(1), "tablet", 1, "g1tablet")))
      replica.clearDirty("alessio", 7, listOf(key(1)))
    }
    assertThat(replica.dirty("alessio").map { it.cls to it.contentKey }).containsExactly(8 to key(2))
    assertThat(replica.applied("alessio", 7, listOf(key(1), key(2))).single().device).isEqualTo("tablet")
    assertThat(replica.allApplied("alessio")).hasSize(1)

    // La testa nuova non elenca piu' s1: le sue voci escono e la chiave torna da rifondere.
    val touched = applyHead("tablet", 2, listOf(segment("tablet", "s2", cls = 8)), emptyMap())
    assertThat(touched).containsExactly(7 to key(1))
    assertThat(replica.keys("alessio")).containsExactly(AiKeyRow(8, key(2)))
    assertThat(replica.dirty("alessio").map { it.cls to it.contentKey }).containsExactly(7 to key(1), 8 to key(2)).inOrder()
    assertThat(replica.segments("alessio", "tablet").map { it.hash }).containsExactly("s2")
  }

  @Test
  fun ilDispositivoEUnaShareSiDimenticano() = runBlocking<Unit> {
    applyHead("tablet", 1, listOf(segment("tablet", "s1")), mapOf("s1" to listOf(entry("tablet", "s1", 1))))
    applyHead("telefono", 1, listOf(segment("telefono", "s2")), mapOf("s2" to listOf(entry("telefono", "s2", 1))))
    db.transaction {
      replica.saveApplied(listOf(AiReplicaAppliedEntity("alessio", 7, key(1), "tablet", 1, "g1tablet")))
      // `forgetDevice`: le sue voci, i segmenti e la testa escono; le chiavi toccate tornano da rifondere.
      val touched = replica.keysOfDevice("alessio", "tablet")
      replica.deleteEntriesOfDevice("alessio", "tablet")
      replica.deleteSegments("alessio", "tablet")
      replica.deleteHead("alessio", "tablet")
      replica.markDirty(touched.map { AiReplicaDirtyEntity("alessio", it.cls, it.contentKey) })
    }
    assertThat(replica.heads("alessio").map { it.device }).containsExactly("telefono")
    assertThat(replica.candidates("alessio", 7, listOf(key(1))).map { it.device }).containsExactly("telefono")

    // `forgetShare` (E-I8): tutto di quella share, consegne comprese.
    db.transaction {
      replica.deleteHeads("alessio")
      replica.deleteSegmentsOfShare("alessio")
      replica.deleteEntriesOfShare("alessio")
      replica.deleteAppliedOfShare("alessio")
      replica.deleteDirtyOfShare("alessio")
    }
    assertThat(replica.shares()).isEmpty()
    assertThat(replica.keys("alessio")).isEmpty()
  }
}
