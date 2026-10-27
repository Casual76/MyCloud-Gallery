package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.FacesLeases
import dev.pampa.mycloudgallery.core.faces.erase.FacesAccountFacts
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesRemote
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// IL NAS DEL BANCO. Un'interfaccia sola per i tre NAS: il NAS finto in memoria ([MemoryChaosNas], 2.000 giri), il simulatore SMB con la sessione di
// produzione, le vere `SmbNasFileOps` e il vero `NasFacesEraser` ([SmbChaosNas], 600 giri) e il NAS vero di prova dal PC (`FacesAreaNas`, 400 interruzioni).
// I giri sono gli stessi: cambia solo cio' che sta sotto. Il banco guarda il NAS con l'**oracolo** ([paths], [bytes]): una strada sua, senza colpi.

/** Cio' che un dispositivo usa per pubblicare, replicare e prenotare. */
internal class ChaosKit(val remote: NasFacesRemote, val leases: FacesLeases)

internal interface ChaosNas : AutoCloseable {
  /** Il nome, per il rapporto. */
  val name: String

  /** Vero per il NAS vero: l'ora e lo spazio non si cambiano, gli handle restano sul NAS. */
  val real: Boolean

  /** L'area privata dichiarata dell'account e l'area comune (di prova: `test` e `test-comune`). */
  val privateShare: String
  val commonShare: String

  /** Congelamento (D35): le scritture dei volti sono ferme finche' non torna a `false`. */
  var frozen: Boolean

  /** Il cestino di rete delle aree private (E-F34): un file tolto ci resta finche' non lo si svuota. */
  var recycleBin: Boolean

  /** Le copie nel cestino di rete adesso. */
  fun binCopies(): Int

  /** L'utente svuota il cestino di rete dal pannello del NAS. */
  fun emptyRecycleBin()

  /** Quanti file al piu' la cancellazione toglie in un giro. */
  var maxFilesPerRound: Int

  /** La porta del NAS e le prenotazioni di [device]; chiamato a ogni processo nuovo. */
  fun kit(device: ChaosDevice): ChaosKit

  /** La cancellazione stretta sul NAS vista da [device] (la porta `FacesEraseOps` legata sopra il cancello e la regola di D31). */
  fun eraseOps(device: ChaosDevice, facts: FacesAccountFacts): FacesEraseOps

  /** Il processo di [device] muore, o la rete cade: si chiudono le sue connessioni (come il sistema col processo ucciso). */
  fun sever(device: Int)

  /** Tutti i file sotto la share privata (percorsi nella radice dell'area), con l'oracolo: nessun colpo. */
  fun paths(): List<SharePath>

  /** I byte di un file della share privata, o null se non c'e'. */
  fun bytes(path: SharePath): ByteArray?

  /** Tutti i file sotto l'area comune dichiarata (deve restare senza niente dei volti). */
  fun commonPaths(): List<SharePath>

  /** Mette un file nell'area privata come chi ha la password SMB (un media, un file dell'app, un attaccante): nessuna firma. */
  fun plant(path: SharePath, bytes: ByteArray)

  /** L'utente toglie un file dal PC. */
  fun removeAsUser(path: SharePath)

  /** La prova "nell'area comune non si scrive": una porta dei volti costruita sull'area comune rifiuta senza mandare richieste. Restituisce (rifiutata, richieste arrivate). */
  fun commonProbe(device: ChaosDevice): Pair<Boolean, Int>

  /** La porta di lettura di chi guarda (l'osservatore): niente colpi, niente scritture. */
  fun observer(device: ChaosDevice): NasFacesRemote

  /** Sospende i guasti del NAS (le verifiche del banco) fino alla chiusura del valore restituito. */
  fun pauseFaults(): AutoCloseable
}
