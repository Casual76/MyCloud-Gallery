package dev.pampa.mycloudgallery.core.metadata.contract

import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource

// Sorgente, tratti e gruppi (docs/fase3-progetto.md §2.2, tabelle "Classificazione" e "Gruppi"). Segnaposto di P0:
// le firme sono il contratto, i corpi sono di P3.

/** L'esito della classificazione di un file: da dove viene e quali tratti ha gia' il suo percorso o il suo nome. */
data class Classified(
  val source: MediaSource,
  val traits: Set<MediaTrait>,
)

/**
 * Da dove viene un media: screenshot, WhatsApp (anche Business, adesivi esclusi), Quick Share, fotocamera, altro.
 * Si decide da volume, percorso relativo, nome, pacchetto proprietario (`OWNER_PACKAGE_NAME`) e tipo MIME. Il
 * tratto `SCREEN_RECORDING` viene dai video in `DCIM/Screen recordings/`.
 *
 * **Segnaposto di P0.** Dice sempre `OTHER` ("non lo so ancora"), che in un'unione cede a qualunque sorgente piu'
 * precisa (`MediaSource.isSpecific`). P3 lo sostituisce con le regole del §2.2 senza cambiare la firma.
 */
object SourceClassifier {

  @Suppress("UNUSED_PARAMETER")
  fun classify(volume: String, relativePath: String, displayName: String, ownerPackage: String?, mime: String?): Classified =
    Classified(MediaSource.OTHER, emptySet())
}

/**
 * Un file che potrebbe far parte di un gruppo. Tutto cio' che serve per decidere senza rileggere il file: il
 * catalogo e l'estrazione lo forniscono.
 */
data class GroupCandidate(
  val id: MediaId,
  /** La chiave della cartella (`Location.folderKey`): un gruppo sta in una cartella sola. */
  val folderKey: String,
  val displayName: String,
  val mime: String?,
  val kind: MediaKind,
  val takenAtMs: Long,
  val hint: GroupHint?,
) {
  // Il nome del file e' un dato personale.
  override fun toString(): String = "GroupCandidate($kind)"
}

/** Un gruppo proposto: i membri e la copertina (il JPEG di una coppia, `_COVER` o il primo di una raffica). */
data class ProposedGroup(
  val kind: MediaGroupKind,
  val members: List<MediaId>,
  val cover: MediaId,
) {
  init {
    require(members.size >= 2) { "Un gruppo ha almeno due membri." }
    require(cover in members) { "La copertina e' un membro del gruppo." }
  }
}

/**
 * Raffiche e coppie RAW+JPEG. **Nel dubbio non si raggruppa**: un gruppo sbagliato nasconde una foto, un gruppo
 * mancato costa solo una cella in piu' (D20).
 *
 * - RAW+JPEG: stessa cartella, stesso nome base, `.dng` + `.jpg`/`.heic`, scatti a meno di 2 s; copertina il JPEG.
 * - Raffica: almeno 2 foto con suffissi `_BURSTnnn`, `_nnn` o `(n)` dello stesso nome base, scatti consecutivi a
 *   meno di 1 s, stesso modello; copertina `_COVER` o la prima.
 *
 * **Segnaposto di P0.** Non propone mai gruppi, che e' la risposta sicura. P3 lo sostituisce con le regole vere
 * senza cambiare la firma.
 */
object GroupingRules {

  @Suppress("UNUSED_PARAMETER")
  fun propose(candidates: List<GroupCandidate>): List<ProposedGroup> = emptyList()
}
