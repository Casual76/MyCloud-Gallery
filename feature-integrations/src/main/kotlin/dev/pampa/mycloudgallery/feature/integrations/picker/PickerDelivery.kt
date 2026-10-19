package dev.pampa.mycloudgallery.feature.integrations.picker

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIssuer
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentPolicy
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import kotlinx.coroutines.withTimeoutOrNull

// Fase 5B (P10, docs/fase5-progetto.md §2.7, D23, C17; E-H1, E-X1, E-X4, E-X6): dalla scelta dell'utente al risultato per l'altra app.
//
// Le regole, tutte provate da `PickerDeliveryTest`:
//  - **la vista di adesso decide** (E-H1): un id che non e' nella vista della Libreria in questo momento (nascosto dopo che la griglia era
//    stata disegnata, sparito, non concesso) non esce, qualunque cosa dica la porta;
//  - **copie del telefono**: l'URI del MediaStore; **solo-NAS**: l'URI del nostro provider con un id opaco **consegnato** (D23);
//  - **tetti** (E-X6): al piu' [DocumentPolicy.MAX_PICKED_ITEMS] elementi, e fra questi al piu' [DocumentPolicy.MAX_NAS_ONLY_ITEMS] solo-NAS e
//    [DocumentPolicy.MAX_NAS_ONLY_BYTES]: oltre, rifiuto motivato, e la scelta resta com'e' perche' l'utente la corregga;
//  - **NAS irraggiungibile** (E-X1): detto subito, con il motivo, **prima** di creare qualunque id; mai un'attesa infinita;
//  - **annullamento** (E-X4): ogni sospensione e' prima della creazione degli id; dopo non si sospende piu'. Un'annullamento a meta' non lascia
//    id, e se qualcosa fallisce dopo la creazione gli id si revocano.

/** Gli elementi della griglia: la vista di adesso, senza cio' che la richiesta non ammette. */
class PickerItems internal constructor(val snapshot: TimelineSnapshot, private val positions: IntArray) {

  val size: Int get() = positions.size

  fun idAt(position: Int): MediaId = snapshot.idAt(positions[position])

  /** La posizione dell'elemento [position] nell'indice (per la chiave della miniatura e il riferimento). */
  fun snapshotIndex(position: Int): Int = positions[position]

  companion object {
    val Empty: PickerItems by lazy { PickerItems(EmptyTimelineSnapshot, IntArray(0)) }

    /** Gli elementi di [snapshot] che [request] ammette: per tipo, e (con `EXTRA_LOCAL_ONLY`) solo quelli che stanno sul telefono. */
    fun of(snapshot: TimelineSnapshot, request: PickerRequest): PickerItems {
      val out = IntArray(snapshot.size)
      var n = 0
      for (i in 0 until snapshot.size) {
        if (!request.acceptsKind(snapshot.kindAt(i))) continue
        if (request.localOnly && !snapshot.hasFlag(i, CellFlags.ON_PHONE)) continue
        out[n++] = i
      }
      return PickerItems(snapshot, out.copyOf(n))
    }
  }
}

/** Un messaggio per l'utente, quando una consegna non puo' avvenire. Solo numeri e codici: nessun nome. */
sealed interface PickerNotice {

  /** Il NAS non risponde (E-X1): i media solo-NAS non si possono consegnare adesso. */
  data class NasUnreachable(val reason: NasUnreachableReason) : PickerNotice

  /** Troppi solo-NAS insieme (E-X6). */
  data class TooManyNasOnly(val count: Int, val bytes: Long) : PickerNotice

  /** Troppi elementi (E-X6). */
  data class TooMany(val max: Int) : PickerNotice

  /** Fra quelli scelti nessuno si puo' consegnare adesso. */
  data object NothingAvailable : PickerNotice
}

/** L'esito di una preparazione. */
sealed interface PickerDelivery {

  /**
   * Gli URI da consegnare, nell'ordine dell'indice; [skipped] quanti elementi scelti non erano piu' disponibili; [minted] gli id creati per i
   * solo-NAS: chi cambia idea prima di consegnare li revoca (il blocco si e' richiuso, E-X4).
   */
  class Ready(val uris: List<Uri>, val skipped: Int, val nasOnly: Int, val minted: List<DocumentId>) : PickerDelivery {
    override fun toString(): String = "Ready(${uris.size}, skipped=$skipped, nasOnly=$nasOnly)"
  }

  class Refused(val notice: PickerNotice) : PickerDelivery
}

/** Prepara la consegna di una scelta. */
class PickerDeliverer(
  private val library: IntegrationLibrary,
  private val issuer: DocumentIssuer?,
  private val request: PickerRequest,
  private val authority: String,
  private val nasProbeDeadlineMs: Long = DocumentPolicy.NAS_PROBE_DEADLINE_MS,
) {

  /**
   * Gli URI di [ids]. Sospende solo prima di creare gli id dei solo-NAS (le informazioni dal catalogo, la prova del NAS): un annullamento li
   * trova tutti ancora da creare.
   */
  suspend fun prepare(ids: Set<MediaId>): PickerDelivery {
    if (ids.size > DocumentPolicy.MAX_PICKED_ITEMS) {
      IntegrationCounters.add(IntegrationCounter.PICKER_TOO_MANY)
      return PickerDelivery.Refused(PickerNotice.TooMany(DocumentPolicy.MAX_PICKED_ITEMS))
    }
    val snapshot = library.items.view.value.snapshot
    var skipped = 0

    // 1. Cosa esce, in ordine d'indice (il piu' recente prima), contro la vista di ADESSO (E-H1).
    val ordered = ids.mapNotNull { id ->
      val index = snapshot.indexOf(id)
      if (index < 0) {
        IntegrationCounters.add(IntegrationCounter.HIDDEN_STOPPED)
        skipped++
        null
      } else {
        index to id
      }
    }.sortedBy { it.first }
    val phone = ArrayList<Pair<MediaId, MediaRef.Phone>>()
    val nas = ArrayList<Pair<MediaId, MediaRef.Nas>>()
    for ((_, id) in ordered) {
      when (val ref = library.items.refOrNull(id)) {
        is MediaRef.Phone -> phone += id to ref
        is MediaRef.Nas -> nas += id to ref
        is MediaRef.Synthetic, null -> skipped++
      }
    }

    // 2. Il tipo dei file, dove serve: tipi precisi richiesti, o i solo-NAS (dimensione per il tetto, e per sapere che ci sono ancora).
    val needInfo = request.hasSpecificTypes || nas.isNotEmpty()
    val infos = if (needInfo) library.fileInfos(phone.map { it.first } + nas.map { it.first }) else emptyMap()
    val phoneOk = phone.filter { (id, _) ->
      val info = infos[id]
      val ok = if (request.hasSpecificTypes) info != null && request.acceptsMime(info.mime) else info == null || request.acceptsMime(info.mime)
      if (!ok) skipped++
      ok
    }
    val nasOk = nas.filter { (id, _) ->
      val info = infos[id]
      val ok = info != null && request.acceptsMime(info.mime)
      if (!ok) skipped++
      ok
    }

    // 3. I tetti dei solo-NAS (E-X6).
    val nasBytes = nasOk.sumOf { infos.getValue(it.first).size }
    if (nasOk.size > DocumentPolicy.MAX_NAS_ONLY_ITEMS || nasBytes > DocumentPolicy.MAX_NAS_ONLY_BYTES) {
      IntegrationCounters.add(IntegrationCounter.PICKER_TOO_MANY)
      return PickerDelivery.Refused(PickerNotice.TooManyNasOnly(nasOk.size, nasBytes))
    }

    // 4. Il NAS risponde? (E-X1) Detto prima di creare qualunque id, mai un'attesa infinita.
    if (nasOk.isNotEmpty()) {
      if (issuer == null) {
        IntegrationCounters.add(IntegrationCounter.PICKER_NAS_UNREACHABLE)
        return PickerDelivery.Refused(PickerNotice.NasUnreachable(NasUnreachableReason.UNAVAILABLE))
      }
      val reach = withTimeoutOrNull(nasProbeDeadlineMs) { library.nasReach() }
      if (reach !is NasReach.Reachable) {
        IntegrationCounters.add(IntegrationCounter.PICKER_NAS_UNREACHABLE)
        val reason = (reach as? NasReach.Unreachable)?.reason ?: NasUnreachableReason.OFFLINE
        return PickerDelivery.Refused(PickerNotice.NasUnreachable(reason))
      }
    }

    // 5. Da qui non si sospende piu': gli id, poi gli URI. Un errore revoca gli id appena creati.
    val minted = ArrayList<DocumentId>()
    var committed = false
    try {
      // La vista di QUESTO istante: durante le attese qui sopra un media puo' essere stato nascosto (E-H1).
      val fresh = library.items.view.value.snapshot
      val uris = ArrayList<Uri>(phoneOk.size + nasOk.size)
      val byIndex = ArrayList<Pair<Int, Uri>>(phoneOk.size + nasOk.size)
      for ((id, ref) in phoneOk) {
        val index = fresh.indexOf(id)
        val uri = if (index >= 0) library.phoneUri(ref) else null
        if (uri == null || uri.scheme != "content") {
          if (index < 0) IntegrationCounters.add(IntegrationCounter.HIDDEN_STOPPED)
          skipped++
          continue
        }
        byIndex += index to uri
      }
      var nasCount = 0
      for ((id, _) in nasOk) {
        val index = fresh.indexOf(id)
        val token = if (index >= 0) issuer?.issue(id) else null
        if (token == null) {
          if (index < 0) IntegrationCounters.add(IntegrationCounter.HIDDEN_STOPPED)
          skipped++
          continue
        }
        minted += token
        nasCount++
        byIndex += index to DocumentsContract.buildDocumentUri(authority, token.value)
      }
      byIndex.sortBy { it.first }
      byIndex.mapTo(uris) { it.second }
      if (uris.isEmpty()) {
        IntegrationCounters.add(IntegrationCounter.PICKER_SKIPPED, skipped.toLong())
        return PickerDelivery.Refused(PickerNotice.NothingAvailable)
      }
      committed = true
      IntegrationCounters.add(IntegrationCounter.PICKER_DELIVERED, uris.size.toLong())
      IntegrationCounters.add(IntegrationCounter.PICKER_DELIVERED_NAS_ONLY, nasCount.toLong())
      IntegrationCounters.add(IntegrationCounter.PICKER_SKIPPED, skipped.toLong())
      return PickerDelivery.Ready(uris, skipped, nasCount, minted.toList())
    } finally {
      if (!committed && minted.isNotEmpty()) issuer?.revoke(minted)
    }
  }
}

/** Il risultato per l'altra app: gli URI in `ClipData` (e in `data` se e' uno solo), con **solo** il permesso di lettura, non persistente. */
object PickerResults {

  fun intent(uris: List<Uri>): Intent {
    require(uris.isNotEmpty()) { "Niente da consegnare." }
    val clip = ClipData.newRawUri("", uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    return Intent().apply {
      clipData = clip
      if (uris.size == 1) data = uris.first()
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }
}
