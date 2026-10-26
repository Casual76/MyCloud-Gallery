package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.albums.share.ShareBench
import dev.pampa.mycloudgallery.core.albums.share.ShareNasTarget
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome

// GLI ERRORI INTRODOTTI APPOSTA (`ShareChaosMutationTest`). Come per le Fasi 4, 5 e 6 il codice di produzione non si tocca: ogni errore e' uno stack
// rotto che avvolge le porte dello stack sano e ne riproduce l'EFFETTO, sopra gli osservatori e i colpi del banco (i guasti del banco colpiscono anche
// lui). Sono i quattro errori della riga P5 del §4.4: la sorgente nel cestino prima della verifica, la voce pubblicata prima del completamento, il
// completamento dopo il cestino, la conferma riusata.

object ShareChaosMutants {

  /** La sorgente nel cestino PRIMA della verifica: appena il piano parte, i file escono dall'area privata e vanno nel cestino, prima di essere copiati. */
  fun trashBeforeVerify(): ShareChaosHooks = ShareChaosHooks(target = { EarlyTrashTarget(it) })

  /** La voce `item:` pubblicata PRIMA del completamento: appena il trasferimento e' chiesto, la voce col percorso di destinazione e' in testa al NAS. */
  fun entryBeforeCompletion(): ShareChaosHooks = ShareChaosHooks(
    ports = { bench, handle ->
      val base = bench.wrapMover
      bench.wrapMover = { real -> EarlyEntryMover(base(real), bench, handle) }
      bench.rewire()
    },
  )

  /** Il COMPLETAMENTO dopo il cestino: il catalogo viene a sapere della destinazione solo al giro dopo, quando la sorgente e' gia' nel cestino. */
  fun completionAfterTrash(): ShareChaosHooks {
    var current: DeferredCompletions? = null
    return ShareChaosHooks(
      sink = { inner -> DeferredCompletions(inner).also { current = it } },
      ports = { bench, _ ->
        val deferred = checkNotNull(current) { "la porta di completamento non e' stata avvolta" }
        val base = bench.wrapTickets
        bench.wrapTickets = { real ->
          val wrapped = base(real)
          object : TicketStatus {
            override suspend fun status(ticket: TransferTicket): TicketState {
              deferred.flush()
              return wrapped.status(ticket)
            }

            override suspend fun find(key: TransferKey): TransferTicket? = wrapped.find(key)

            override suspend fun cancel(ticket: TransferTicket) = wrapped.cancel(ticket)
          }
        }
        bench.rewire()
      },
    )
  }

  /** La conferma RIUSATA: la prima conferma emessa si tiene (anche oltre la morte del processo) e serve per ogni trasferimento dopo, di ogni piano. */
  fun reusedConfirmation(): ShareChaosHooks = ShareChaosHooks(
    ports = { bench, _ ->
      val remembered = ArrayList<ShareConfirmation>()
      val base = bench.wrapMover
      bench.wrapMover = { real -> ReusingMover(base(real), remembered) }
      bench.rewire()
    },
  )
}

/** Il NAS che, appena il piano parte, mette ogni sorgente nel cestino (con i suoi byte) e la toglie da dov'era: poi il motore non la trova piu'. */
class EarlyTrashTarget(private val inner: ShareNasTarget) : ShareNasTarget by inner {
  private var counter = 0

  override suspend fun start(plan: OpPlan): StartOutcome {
    for (item in plan.items.filter { !it.isFolder }) {
      val bytes = inner.bytes(item.srcShare, item.srcPath.value) ?: continue
      inner.put(item.srcShare, ".mycloudgallery/trash/${ShareNasTarget.DEVICE}/files/early-${counter++}", bytes)
      inner.delete(item.srcShare, item.srcPath.value)
    }
    return inner.start(plan)
  }

  override fun close() = inner.close()
}

/** Lo spostamento che pubblica la voce `item:` di ogni file prima di chiedere il trasferimento (il file non e' ancora nell'area comune). */
class EarlyEntryMover(private val inner: AlbumMover, private val bench: ShareBench, private val handle: ChaosPortsHandle) : AlbumMover {
  override suspend fun move(key: TransferKey, items: List<NasItemRef>, dest: ShareFolder, leave: Set<NasItemRef>, confirmation: ShareConfirmation): TransferStart {
    val op = bench.diary.allOps().firstOrNull { it.id.value == key.value.substringBefore(':') }
    val publisher = handle.publisher
    if (op != null && op.kind == SharePlanKind.SHARE && publisher != null) {
      val me = bench.me.account
      val changes = items.mapIndexedNotNull { i, ref ->
        val media = bench.library.all().firstOrNull { bench.library.nasRefOf(it) == ref } ?: return@mapIndexedNotNull null
        SharedAlbumChange.Item(media.id, media.version, media.contentKey, dest.path.child(ref.path.value.substringAfterLast('/')), 1_000L + i, me)
      }
      if (changes.isNotEmpty()) {
        publisher.stage(op.album, changes)
        publisher.flush(op.album, FlushReason.SYNC_ROUND)
      }
    }
    return inner.move(key, items, dest, leave, confirmation)
  }
}

/** La porta di completamento che risponde subito "fatto" e collega il catalogo solo quando le si dice di farlo (al giro dopo). */
class DeferredCompletions(private val inner: MoveCompletionSink) : MoveCompletionSink {
  private val pending = ArrayList<suspend () -> Unit>()

  override suspend fun published(op: OpId, index: Int, source: NasFileRef, dest: NasFileRef, stamp: NasStamp, sha256: Sha256) {
    pending += { inner.published(op, index, source, dest, stamp, sha256) }
  }

  suspend fun flush() {
    val todo = pending.toList()
    pending.clear()
    todo.forEach { it() }
  }
}

/** Lo spostamento che usa sempre la prima conferma vista, qualunque sia il piano e il processo che la chiede. */
class ReusingMover(private val inner: AlbumMover, private val remembered: MutableList<ShareConfirmation>) : AlbumMover {
  override suspend fun move(key: TransferKey, items: List<NasItemRef>, dest: ShareFolder, leave: Set<NasItemRef>, confirmation: ShareConfirmation): TransferStart {
    if (remembered.isEmpty()) remembered += confirmation
    return inner.move(key, items, dest, leave, remembered.first())
  }
}
