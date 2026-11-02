package dev.pampa.mycloudgallery.feature.integrations.drag

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.provider.DocumentsContract
import dev.pampa.mycloudgallery.core.library.contract.DragFlags
import dev.pampa.mycloudgallery.core.library.contract.DragLimits
import dev.pampa.mycloudgallery.core.library.contract.DragPrepared
import dev.pampa.mycloudgallery.core.library.contract.DragRefusal
import dev.pampa.mycloudgallery.core.library.contract.DragRequest
import dev.pampa.mycloudgallery.core.library.contract.MediaDragHandler
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.picker.indexInSnapshotOf
import dev.pampa.mycloudgallery.feature.integrations.provider.DeliveryPreparer
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIssuer
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentPolicy
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.MediaFileInfo
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NoPreparation
import dev.pampa.mycloudgallery.feature.integrations.provider.PreparedBatch
import dev.pampa.mycloudgallery.feature.integrations.share.ShareStop
import kotlinx.coroutines.withTimeoutOrNull

// I DATI DEL TRASCINAMENTO VERSO UN'ALTRA APP (Fase 10, P6, D20, C436, E-T11-E-T13, E-T17, E-T18, E-X5-E-X7; docs/fase10-progetto.md §2.5). La porta `MediaDragHandler` di
// `core-library`, sopra il **provider dei documenti** di P10: la stessa Libreria per le altre app ([IntegrationLibrary]), gli stessi id opachi ([DocumentIssuer]), la stessa copia senza
// posizione di "Condividi" ([DeliveryPreparer]). **Nessuna strada nuova verso i file.**
//
// Le regole, tutte provate da `DocumentDragPayloadsTest`:
//  - **la vista di adesso decide** (E-H11, E-T18): un id che non e' nella vista della Libreria in questo momento (nascosto, sparito, non concesso) ferma **tutto il trascinamento**
//    (`HIDDEN`), qualunque cosa dica la porta; dai Nascosti non si trascina (nessuna origine li nomina). La vista si ricontrolla anche subito prima di creare gli id;
//  - **URI solo del provider** (C436): ogni elemento, anche uno del telefono, esce come `content://<pacchetto>.integrations.documents/document/<token>` con un id opaco **consegnato**;
//    il provider legge un media del telefono dal telefono (come il selettore) e uno solo sul NAS con il **descrittore a richiesta** (a pezzi, nessun file intero). Mai `file://`, mai un
//    `FileProvider` nuovo, mai un URI del MediaStore;
//  - **permessi** (C436): sempre e solo [DragFlags.ALLOWED], lo impone anche `DragPrepared.Ready`: sola lettura sul singolo URI, mai scrittura, mai persistenti, mai per prefisso;
//  - **tetti** (E-X6, E-T12): al piu' [DragLimits.MAX_ITEMS] elementi e [DragLimits.MAX_BYTES] in tutto, detti **prima di cominciare** (`TOO_MANY`, `TOO_LARGE`);
//  - **NAS irraggiungibile** (E-T11): detto subito, con la prova del NAS a tempo, **prima** di creare qualunque id; quello che manca dopo lo dicono le scadenze di ogni lettura del provider;
//  - **"Rimuovi la posizione"** (E-T13, E-X5): con l'impostazione accesa ogni elemento passa da una copia senza posizione (lo stesso stripper di "Condividi"); se **una sola** non si puo'
//    fare, **il trascinamento si rifiuta** (`LOCATION_NOT_REMOVABLE`) e non resta nessuna copia: mai l'originale al suo posto. Una richiesta che chiede la posizione tolta con
//    l'impostazione spenta si rifiuta (il provider servirebbe l'originale);
//  - **blocco dell'app** (E-X7): un cancello che non dice "pronta e sbloccata" ferma il trascinamento; si ricontrolla subito prima di creare gli id;
//  - **annullamento** (E-T17): ogni sospensione e' prima della creazione degli id; dopo non si sospende piu'. Annullare mentre si prepara (il dito si alza, lo schermo si spegne) non lascia
//    ne' id ne' copie. Un trascinamento pronto che poi non parte si rilascia con il suo [dev.pampa.mycloudgallery.core.library.contract.DragLease];
//  - **durata** (D20, E-T14, L547): gli id e le copie vivono in prestito ([DragLeases]): finche' l'app e' in primo piano piu' 10 minuti, e non oltre il prossimo avvio.

class DocumentDragPayloads(
  private val library: IntegrationLibrary,
  private val issuer: DocumentIssuer,
  /** L'autorita' del provider di questa installazione (`DocumentsAuthority.of(context)`). */
  private val authority: String,
  /** Il cancello d'ingresso **adesso** (`IntegrationPorts.entryGate.decide()`): senza "pronta e sbloccata" non si trascina. */
  private val gate: () -> EntryDecision,
  private val leases: DragLeases,
  private val preparer: DeliveryPreparer = NoPreparation,
  private val nasProbeDeadlineMs: Long = DocumentPolicy.NAS_PROBE_DEADLINE_MS,
) : MediaDragHandler {

  override suspend fun prepare(request: DragRequest): DragPrepared {
    if (request.ids.size > DragLimits.MAX_ITEMS) return refused(DragRefusal.TOO_MANY)
    gateRefusal()?.let { return it }

    // L'impostazione "Rimuovi la posizione" e' una sola, ed e' quella che il provider legge quando l'altra app apre: la richiesta non puo' chiedere di piu' di cosi'.
    val removing = preparer.removeLocation()
    if (request.removeLocation && !removing) return refused(DragRefusal.LOCATION_NOT_REMOVABLE)

    // 1. Cosa esce, contro la vista di ADESSO (E-H11): un solo nascosto ferma tutto.
    val view = library.items.view.value
    val ids = request.ids
    var nasOnly = 0
    for (id in ids) {
      if (view.indexInSnapshotOf(id) < 0) {
        IntegrationCounters.add(IntegrationCounter.HIDDEN_STOPPED)
        return refused(DragRefusal.HIDDEN)
      }
      when (library.items.refOrNull(id)) {
        is MediaRef.Phone -> Unit
        is MediaRef.Nas -> nasOnly++
        is MediaRef.Synthetic, null -> return refused(DragRefusal.UNAVAILABLE)
      }
    }

    // 2. Il peso, dal catalogo (senza aprire i file): il tetto di byte, detto prima di cominciare (E-T12).
    val infos = library.fileInfos(ids)
    var total = 0L
    for (id in ids) {
      val info = infos[id] ?: return refused(DragRefusal.UNAVAILABLE)
      total += info.size
      if (total > DragLimits.MAX_BYTES) return refused(DragRefusal.TOO_LARGE)
    }

    // 3. Il NAS risponde? (E-T11) Detto prima di creare qualunque id; mai un'attesa infinita.
    if (nasOnly > 0) {
      val reach = withTimeoutOrNull(nasProbeDeadlineMs) { library.nasReach() }
      if (reach !is NasReach.Reachable) return refused(DragRefusal.NAS_UNREACHABLE)
    }

    // 4. Con "Rimuovi la posizione" acceso: ogni elemento passa da una copia senza posizione (puo' durare: un video solo sul NAS si scarica). Se una sola non si fa, niente parte.
    if (removing) {
      when (val batch = preparer.prepare(ids, delivered = true)) {
        is PreparedBatch.Ready -> Unit
        is PreparedBatch.Refused -> return refused(refusalOf(batch.stop))
      }
    }

    // 5. Da qui non si sospende piu': il cancello e la vista dell'ultimo istante, poi gli id e il ClipData.
    return commit(ids, infos, nasOnly, removing)
  }

  private fun commit(ids: List<MediaId>, infos: Map<MediaId, MediaFileInfo>, nasOnly: Int, removing: Boolean): DragPrepared {
    val minted = ArrayList<DocumentId>(ids.size)

    fun abort(reason: DragRefusal): DragPrepared {
      if (minted.isNotEmpty()) issuer.revoke(minted)
      // Nessuna copia resta se niente esce.
      if (removing) preparer.release(ids)
      return refused(reason)
    }

    gateRefusal()?.let { refusal ->
      if (removing) preparer.release(ids)
      return refusal
    }
    val fresh = library.items.view.value
    for (id in ids) {
      val token = if (fresh.indexInSnapshotOf(id) >= 0) issuer.issue(id) else null
      if (token == null) {
        IntegrationCounters.add(IntegrationCounter.HIDDEN_STOPPED)
        return abort(DragRefusal.HIDDEN)
      }
      minted += token
    }
    val uris: List<Uri> = minted.map { DocumentsContract.buildDocumentUri(authority, it.value) }
    val clip = clipOf(uris, mimesOf(ids, infos, removing))
    val lease = leases.hold {
      issuer.revoke(minted)
      if (removing) preparer.release(ids)
    }
    DragCounters.add(DragCounter.OUT_READY)
    DragCounters.add(DragCounter.OUT_ITEMS, ids.size.toLong())
    DragCounters.add(DragCounter.OUT_NAS_ONLY, nasOnly.toLong())
    if (removing) DragCounters.add(DragCounter.OUT_STRIPPED, ids.size.toLong())
    return DragPrepared.Ready(clip, DragFlags.ALLOWED, ids.size, lease)
  }

  /** I tipi di cio' che esce: con le copie senza posizione quelli veri delle copie (un HEIC e' diventato JPEG). Senza niente, il tipo generico. */
  private fun mimesOf(ids: List<MediaId>, infos: Map<MediaId, MediaFileInfo>, removing: Boolean): List<String> {
    val all = LinkedHashSet<String>()
    for (id in ids) {
      val raw = (if (removing) preparer.infoOf(id)?.mime else null) ?: infos[id]?.mime ?: continue
      all += LibraryMediaDocuments.safeMime(raw)
    }
    return if (all.isEmpty()) listOf(ANY_TYPE) else all.toList()
  }

  private fun clipOf(uris: List<Uri>, mimes: List<String>): ClipData {
    val clip = ClipData(ClipDescription("", mimes.toTypedArray()), ClipData.Item(uris.first()))
    for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
    return clip
  }

  private fun gateRefusal(): DragPrepared? = when (gate()) {
    EntryDecision.ALLOWED -> null
    EntryDecision.NEEDS_UNLOCK -> refused(DragRefusal.LOCKED)
    EntryDecision.NOT_READY, EntryDecision.LOADING, EntryDecision.STOPPED -> refused(DragRefusal.UNAVAILABLE)
  }

  private fun refused(reason: DragRefusal): DragPrepared {
    DragCounters.add(DragCounter.refused(reason))
    return DragPrepared.Refused(reason)
  }

  private fun refusalOf(stop: ShareStop): DragRefusal = when (stop) {
    ShareStop.NAS_UNREACHABLE -> DragRefusal.NAS_UNREACHABLE
    ShareStop.TOO_MANY -> DragRefusal.TOO_MANY
    ShareStop.TOO_BIG, ShareStop.NO_SPACE -> DragRefusal.TOO_LARGE
    ShareStop.CANNOT_REMOVE_LOCATION, ShareStop.LOCATION_REMAINS -> DragRefusal.LOCATION_NOT_REMOVABLE
    ShareStop.UNAVAILABLE, ShareStop.FAILED, ShareStop.NOTHING -> DragRefusal.UNAVAILABLE
  }

  private companion object {
    const val ANY_TYPE = "*/*"
  }
}
