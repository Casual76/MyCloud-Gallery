package dev.pampa.mycloudgallery.feature.viewer.menu

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Lock
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.viewer.R

// Le voci del menu del viewer per un media del NAS (Fase 4B, P14; docs/fase4-progetto.md §1.3 "menu a capacita'", D24, E-D1, E-D2,
// E-D11, E-N9): "Sposta", "Rinomina" ed "Elimina…" con la scelta del luogo ("Solo dal telefono" / "Ovunque").
//
// Questo file e' i **dati e le porte**: cosa il viewer chiede a `:app` ([NasMediaActions]), cosa gli torna ([NasActionTarget]) e come
// si disegnano le voci ([nasMenuEntries]). La macchina che porta da una voce a un'operazione e' in `NasActionsController`, i fogli in
// `NasActionsSheets`. **Il viewer non scrive mai sul NAS**: chiede un piano a `NasOperations`, mostra un foglio, e solo da un tocco
// dell'utente sul foglio nasce la conferma con cui l'operazione parte (la stessa regola di feature-nas, `ConfirmationTapTest`).

/** Di chi e' l'area in cui sta un file del NAS: solo dell'account, o di tutta la famiglia. */
enum class NasAreaScope {
  PRIVATE,
  COMMON,
}

/**
 * Un posto in cui il media si trova, per l'elenco del foglio "Elimina…". [writable] falso = sola lettura per l'utente (E-D11). [item] e' la
 * copia del NAS in quel posto (con il timbro visto): con "Ovunque" va nel cestino anche lei, se si puo' scrivere. [previousVersion] (Fase 5B,
 * E-D15): la copia di una versione precedente (in `Versioni/`): va nel cestino con "Ovunque", e il foglio la conta nella riga delle versioni
 * precedenti invece di elencarla come un'altra area.
 */
data class NasLocationInfo(
  val kind: Kind,
  val writable: Boolean = true,
  val item: NasItem? = null,
  val previousVersion: Boolean = false,
  /**
   * Fase 7, revisione B (R3, E-D16): la copia e' un elemento di un album condiviso che il livello dell'album non mi lascia eliminare. "Ovunque" la
   * lascia dov'e' e il foglio lo dice.
   */
  val albumProtected: Boolean = false,
) {
  enum class Kind { PHONE, NAS_PRIVATE, NAS_COMMON }

  override fun toString(): String = "NasLocationInfo(${kind.name})"
}

/**
 * Dove si puo' spostare il media: una cartella di una share (null = la radice), scelta da `:app` fra le aree dell'utente. [label] e'
 * un testo per le persone ("Area comune · Famiglia"): e' un dato dell'utente, mai nei log.
 */
data class NasMoveDestination(
  val share: String,
  val folder: SharePath?,
  val scope: NasAreaScope,
  val label: String,
) {
  override fun toString(): String = "NasMoveDestination(${scope.name})"
}

/**
 * Il file del NAS dietro un media e cosa ci si puo' fare adesso: lo risolve `:app` dal catalogo e dal registro delle aree
 * (`MediaRef.Nas` porta solo l'id della posizione, mai un percorso).
 *
 * @property item il file con il timbro visto qui (E-D13): l'operazione si ferma se non e' piu' quello.
 * @property writable falso se l'area e' in sola lettura per questo account (E-N9): le voci si spengono con la spiegazione.
 * @property onPhone il media ha anche una copia sul telefono (E-D1): "Elimina…" offre "Solo dal telefono" / "Ovunque".
 * @property others le altre posizioni del NAS in cui il media si trova (un'altra area), con il permesso (E-D11).
 * @property destinations dove si puo' spostare; vuoto = "Sposta" non compare.
 * @property hidden l'utente ha nascosto questo media nella Libreria (R11): spostarlo nell'area comune lo mostra ai familiari, e il foglio
 *   lo dice.
 */
class NasActionTarget(
  val item: NasItem,
  val scope: NasAreaScope,
  val writable: Boolean,
  val onPhone: Boolean,
  val isVideo: Boolean,
  val others: List<NasLocationInfo> = emptyList(),
  val destinations: List<NasMoveDestination> = emptyList(),
  val hidden: Boolean = false,
  /** Il viewer mostra la copia del **telefono** (E-D1): del NAS si offre solo "Elimina…" con la scelta del luogo. */
  val viaPhoneCopy: Boolean = false,
  /**
   * Fase 7, revisione B (R3, E-D16): il file e' un elemento di un album condiviso che il livello dell'album non mi lascia eliminare, spostare o
   * rinominare (la riga "Eliminare il file" della tabella dei permessi). Le voci si spengono con la spiegazione, come in sola lettura.
   */
  val albumProtected: Boolean = false,
) {
  /**
   * Le copie del NAS che "Ovunque" mette nel cestino: questa e le altre, solo dove si hanno i diritti (E-D11) e mai un elemento protetto di un
   * album condiviso (R3). Le altre si pianificano comunque: il piano salta le aree in sola lettura e il foglio le elenca.
   */
  val everywhereItems: List<NasItem> get() =
    listOfNotNull(item.takeIf { !albumProtected }) + others.filter { it.writable && !it.albumProtected }.mapNotNull { it.item }

  override fun toString(): String = "NasActionTarget(${scope.name}, writable=$writable, onPhone=$onPhone)"
}

/**
 * Tutto cio' che le voci del menu chiedono a `:app`. Un'istanza per app, costruita dal grafo del NAS; i test usano
 * `NasOpsTestKit` di core-nasops. Senza di lei (`null` nei servizi del viewer) le voci non compaiono.
 */
interface NasMediaActions {

  /** Le operazioni (piano, avvio): le stesse di Cartelle NAS. */
  val operations: NasOperations

  /** L'orologio monotono con cui si data una conferma: lo stesso con cui `NasOperations.start` la controlla. */
  fun monotonicMs(): Long

  /** Il file dietro [ref], o null se non si risolve (la riga del catalogo non c'e' piu', l'area non e' raggiungibile). */
  suspend fun target(ref: MediaRef.Nas): NasActionTarget?

  /**
   * Un media che il viewer mostra dalla copia del **telefono** ([id]) ma che sta anche sul NAS (E-D1, piano §2): le sue copie del NAS, con
   * [NasActionTarget.onPhone] vero, cosi' "Elimina…" offre "Solo dal telefono" / "Ovunque". Null se non ha copie del NAS nelle aree
   * dell'utente.
   */
  suspend fun targetForPhoneCopy(id: MediaId): NasActionTarget? = null
}

/** Cosa la voce di menu puo' fare per questo media. Pura: la provano i test su JVM. */
internal data class NasActionCapabilities(
  val move: Boolean,
  val rename: Boolean,
  /** "Elimina…" compare: c'e' qualcosa da eliminare dove si puo' (il NAS scrivibile, o la copia del telefono). */
  val delete: Boolean,
  /** Area in sola lettura (E-N9): le azioni compaiono spente, con la spiegazione. */
  val readOnly: Boolean,
  /** Fase 7, revisione B (R3): un elemento di un album condiviso che non posso toccare: le azioni compaiono spente, con la spiegazione. */
  val albumLocked: Boolean = false,
) {
  val any: Boolean get() = move || rename || delete || readOnly || albumLocked

  /** Le azioni compaiono spente (sola lettura, o un elemento protetto di un album condiviso). */
  val locked: Boolean get() = readOnly || albumLocked

  companion object {
    val None = NasActionCapabilities(move = false, rename = false, delete = false, readOnly = false)

    fun of(target: NasActionTarget?): NasActionCapabilities = when {
      target == null -> None
      // Il viewer mostra la copia del telefono: del NAS si offre solo "Elimina…" con la scelta del luogo (E-D1); le copie protette di un album
      // condiviso restano fuori da "Ovunque" (R3).
      target.viaPhoneCopy -> NasActionCapabilities(move = false, rename = false, delete = true, readOnly = false)
      !target.writable -> NasActionCapabilities(move = false, rename = false, delete = false, readOnly = true)
      // Revisione B (R3, E-D16): la riga "Eliminare il file" della tabella vale anche per spostare e rinominare (il file uscirebbe dal suo posto).
      target.albumProtected -> NasActionCapabilities(move = false, rename = false, delete = false, readOnly = false, albumLocked = true)
      else -> NasActionCapabilities(
        move = target.destinations.isNotEmpty(),
        rename = true,
        delete = true,
        readOnly = false,
      )
    }
  }
}

/** Cosa succede toccando una voce. */
internal class NasActionHandlers(
  val onMove: () -> Unit,
  val onRename: () -> Unit,
  val onDelete: () -> Unit,
)

/** Le voci del menu per un media del NAS, nei due gruppi del menu del viewer. */
internal class NasMenuEntries(
  /** "Sposta" e "Rinomina" (e, in sola lettura, la riga che spiega): vanno nel gruppo di Condividi, Copia e Informazioni. */
  val actions: List<GalleryMenuEntry>,
  /** "Elimina…": e' la voce che cancella, va in fondo dopo l'interruzione di gruppo, insieme a quella del telefono. */
  val delete: List<GalleryMenuEntry>,
) {
  val isEmpty: Boolean get() = actions.isEmpty() && delete.isEmpty()
}

/**
 * Le voci per un media del NAS: "Sposta", "Rinomina" ed "Elimina…". In un'area in sola lettura (E-N9) le tre voci ci sono ma
 * **spente**, con sopra la riga che dice perche': una voce che sparisce senza spiegazione e' piu' confusa di una voce grigia con la
 * sua ragione.
 */
internal fun nasMenuEntries(resources: Resources, capabilities: NasActionCapabilities, handlers: NasActionHandlers): NasMenuEntries {
  if (!capabilities.any) return NasMenuEntries(emptyList(), emptyList())
  val enabled = !capabilities.locked
  val actions = buildList {
    if (capabilities.readOnly) {
      // La spiegazione resta **leggibile** (una voce spenta si disegna al 38%, e non si leggerebbe): toccarla chiude solo il menu.
      add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_nas_action_read_only), onClick = {}, icon = Icons.Rounded.Lock))
    } else if (capabilities.albumLocked) {
      // Revisione B (R3): perche' le voci sono spente su un elemento di un album condiviso.
      add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_nas_action_album_locked), onClick = {}, icon = Icons.Rounded.Lock))
    }
    if (capabilities.move || capabilities.locked) {
      add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_nas_action_move), handlers.onMove, Icons.AutoMirrored.Rounded.DriveFileMove, enabled = enabled))
    }
    if (capabilities.rename || capabilities.locked) {
      add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_nas_action_rename), handlers.onRename, Icons.Rounded.DriveFileRenameOutline, enabled = enabled))
    }
  }
  val delete = buildList {
    if (capabilities.delete || capabilities.locked) {
      add(
        GalleryMenuEntry.Item(
          label = resources.getString(R.string.viewer_nas_action_delete),
          onClick = handlers.onDelete,
          icon = Icons.Rounded.DeleteForever,
          destructive = true,
          enabled = enabled,
        ),
      )
    }
  }
  return NasMenuEntries(actions, delete)
}
