package dev.pampa.mycloudgallery.feature.nas.contract

// Fase 4B (docs/fase4-progetto.md §1.1, §1.5; riga P14 del §4.4): le schermate di feature-nas e le loro rotte. Il contratto fra questo
// modulo e `:app` (che le inserisce nella navigazione, P16): `:app` conosce le rotte, non le composable. I feature non si conoscono
// fra loro; per aprire il viewer o altre schermate di altri moduli c'e' una callback scritta da `:app`.

/**
 * Le schermate della 4B. [route] e' stabile (nessun dato dell'utente dentro: mai un percorso del NAS in una rotta);
 * [needsWriteAccess] dice se la schermata ha azioni che modificano il NAS (si spengono con la spiegazione su una share in sola
 * lettura, E-N9).
 */
enum class NasScreen(val route: String, val needsWriteAccess: Boolean) {
  /** Cartelle NAS: sfoglia, crea cartella, rinomina, sposta, elimina. Sta in Raccolte. */
  FOLDERS("nas/folders", needsWriteAccess = true),

  /** Spazio NAS: spazio libero, quota, file piu' grandi, cestino, indice. Sola lettura. */
  SPACE("nas/space", needsWriteAccess = false),

  /** Eliminati di recente del NAS: ripristina ed "elimina subito" (le azioni passano da una conferma). */
  TRASH("nas/trash", needsWriteAccess = true),

  /** Operazioni in corso: avanzamento, "Ferma", file bloccati. */
  OPERATIONS("nas/operations", needsWriteAccess = false),
}
