package dev.pampa.mycloudgallery.core.nas.integration

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.session.OpContext
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException

/**
 * La pulizia finale dell'area di prova (§5.4, punto 5): **ogni** cartella `mcg-it/<giro>/` delle share di prova si
 * cancella con l'account che ci ha accesso, e `mcg-it` si toglie se resta vuota.
 *
 * Perche' piu' account: `test` non entra nella share privata `test2` (e viceversa), e in `test-comune` le cartelle
 * create dall'uno possono non essere cancellabili dall'altro. Per ogni share si prova dunque con **ogni** account
 * (nell'ordine dato): chi non ha il permesso su una voce la lascia (`ACCESS_DENIED`) e se ne occupa il successivo.
 * Cancellare con l'account sbagliato non e' un errore, ma un residuo: lo dice [verifyAndRemoveRoot], che con ogni
 * account rilegge `mcg-it` e vuole **niente** (toglie `mcg-it` se vuota).
 *
 * Tutto passa dal trasporto recintato ([TestAreaTransport]): solo `mcg-it/<giro>/` (i giri non correnti dopo
 * [TestAreaGuard.approveStale], quindi solo nomi nel formato dei giri) e `mcg-it` stessa (elenco e, vuota, rimozione).
 * Una voce di `mcg-it` che non ha il nome di un giro non si tocca: resta e fa fallire la verifica.
 *
 * Gli errori di rete o di sessione non si nascondono: [sweep] e [verifyAndRemoveRoot] li riportano come problemi, e
 * proseguono con le altre share. Nessun tentativo con password sbagliate: un login rifiutato apre l'interruttore e
 * le richieste successive di quell'account falliscono subito.
 */
internal class TestAreaCleanup(
  private val guard: TestAreaGuard,
  private val accounts: List<Account>,
  /**
   * Con piu' account configurati ogni share di prova deve essere leggibile da almeno uno; con uno solo (file senza
   * `user2`) una share privata dell'altro non si puo' verificare e non e' un errore.
   */
  private val strict: Boolean,
  private val log: (String) -> Unit,
) {

  /** Un account di prova con la sua sessione. [label] e' il nome utente (mai la password). */
  class Account(val label: String, val manager: SmbNasSessionManager)

  /**
   * Cancella i giri sotto `mcg-it` di ogni share di prova: quelli precedenti rimasti (qualunque eta') e, con
   * [includeCurrent], il giro corrente. Restituisce i problemi (vuoto = nessuno).
   */
  suspend fun sweep(includeCurrent: Boolean): List<String> {
    val problems = ArrayList<String>()
    for (share in guard.shares) {
      for (account in accounts) {
        try {
          sweepShare(account, share, includeCurrent)
        } catch (e: AssertionError) {
          problems += e.message ?: "pulizia di $share con ${account.label} fallita"
        }
      }
    }
    return problems
  }

  private suspend fun sweepShare(account: Account, share: String, includeCurrent: Boolean) {
    val root = readRoot(account, share) as? Root.Present ?: return
    for (child in root.children) {
      if (!child.isDirectory || !TestAreaGuard.RUN_ID.matches(child.name)) continue
      val current = child.name.equals(guard.runId, ignoreCase = true)
      if (current && !includeCurrent) continue
      if (!current) guard.approveStale(child.name)
      val tally = Tally()
      val gone = deleteTree(account, share, "${TestAreaGuard.ROOT}\\${child.name}", tally)
      val kind = if (current) "corrente" else "precedente"
      log(
        if (gone) {
          "pulizia $share: giro $kind ${child.name} tolto con ${account.label} (${tally.removed} voci)"
        } else {
          "pulizia $share: giro $kind ${child.name} tolto in parte con ${account.label} " +
            "(${tally.removed} voci tolte, ${tally.left} rimaste: le tocca a un altro account)"
        },
      )
    }
  }

  /**
   * La verifica finale: con ogni account sotto `mcg-it` di ogni share di prova non resta niente. Una `mcg-it` vuota si
   * toglie. Restituisce i residui e i problemi (vuoto = a posto).
   */
  suspend fun verifyAndRemoveRoot(): List<String> {
    val residue = ArrayList<String>()
    for (share in guard.shares) {
      var readable = 0
      var emptyRootStuck = false
      var rootRemoved = false
      for (account in accounts) {
        try {
          when (val root = readRoot(account, share)) {
            Root.Denied -> Unit
            Root.Absent -> readable++
            is Root.Present -> {
              readable++
              if (root.children.isNotEmpty()) {
                residue += "$share: sotto mcg-it resta " + root.children.joinToString(", ") { it.name } + " (visto da ${account.label})"
              } else if (removeRoot(account, share)) {
                rootRemoved = true
                log("pulizia $share: mcg-it vuota, tolta con ${account.label}")
              } else {
                emptyRootStuck = true
              }
            }
          }
        } catch (e: AssertionError) {
          residue += e.message ?: "verifica di $share con ${account.label} fallita"
        }
      }
      if (emptyRootStuck && !rootRemoved) residue += "$share: mcg-it vuota ma nessun account riesce a toglierla"
      if (readable == 0 && strict) residue += "$share: nessun account riesce a leggere mcg-it (non verificabile)"
    }
    return residue
  }

  // --- Lettura di mcg-it --------------------------------------------------------------------------------------

  private class Child(val name: String, val isDirectory: Boolean)

  private sealed interface Listing {
    /** La cartella non c'e' (piu'). */
    object Gone : Listing

    /** L'account non puo' elencarla. */
    object Denied : Listing

    class Entries(val children: List<Child>) : Listing
  }

  private sealed interface Root {
    object Absent : Root

    /** L'account non entra nella share o non apre `mcg-it`: la gestira' un altro account. */
    object Denied : Root

    class Present(val children: List<Child>) : Root
  }

  private suspend fun readRoot(account: Account, share: String): Root {
    val result = account.manager.runOp(share, Purpose.BACKGROUND, idempotent = true, touchesDisk = true) { ctx ->
      list(ctx.share, TestAreaGuard.ROOT)
    }
    return when (result) {
      is NasResult.Ok -> when (val listing = result.value) {
        Listing.Gone -> Root.Absent
        Listing.Denied -> Root.Denied
        is Listing.Entries -> Root.Present(listing.children)
      }
      // Niente accesso alla share (tree connect negato): e' la share privata dell'altro account.
      is NasResult.Err ->
        if (result.error == NasError.PermissionDenied) Root.Denied else throw failed("lettura di mcg-it", share, account, result.error)
    }
  }

  private fun list(share: SmbShare, dir: String): Listing =
    try {
      share.openDirectory(dir, DirAccess.LIST).use { directory ->
        val children = ArrayList<Child>()
        val iterator = directory.entries()
        while (iterator.hasNext()) iterator.next().let { children += Child(it.name, it.isDirectory) }
        Listing.Entries(children)
      }
    } catch (e: SmbStatusException) {
      when (e.status) {
        NtStatus.OBJECT_NAME_NOT_FOUND, NtStatus.OBJECT_PATH_NOT_FOUND -> Listing.Gone
        NtStatus.ACCESS_DENIED, NtStatus.MEDIA_WRITE_PROTECTED -> Listing.Denied
        else -> throw e
      }
    }

  // --- Cancellazione --------------------------------------------------------------------------------------------

  private class Tally {
    var removed = 0
    var left = 0
  }

  private enum class Removal { DONE, DENIED, NOT_EMPTY }

  private inline fun attempt(block: () -> Unit): Removal =
    try {
      block()
      Removal.DONE
    } catch (e: SmbStatusException) {
      when (e.status) {
        // Gia' tolta (un nuovo tentativo dopo una caduta) o in via di cancellazione: e' come se non ci fosse.
        NtStatus.OBJECT_NAME_NOT_FOUND, NtStatus.OBJECT_PATH_NOT_FOUND, NtStatus.NO_SUCH_FILE, NtStatus.DELETE_PENDING -> Removal.DONE
        NtStatus.ACCESS_DENIED, NtStatus.MEDIA_WRITE_PROTECTED -> Removal.DENIED
        NtStatus.DIRECTORY_NOT_EMPTY -> Removal.NOT_EMPTY
        else -> throw e
      }
    }

  /**
   * Toglie [dir] e quello che contiene con [account], a operazioni corte (lotti di [BATCH] file). Cio' che l'account non
   * puo' togliere resta e si conta in [tally]. Vero se la cartella non c'e' piu'.
   */
  private suspend fun deleteTree(account: Account, share: String, dir: String, tally: Tally): Boolean {
    val listing = step(account, share, "elenco di $dir") { ctx -> list(ctx.share, dir) }
    val children = when (listing) {
      Listing.Gone -> return true
      Listing.Denied -> {
        tally.left++
        return false
      }
      is Listing.Entries -> listing.children
    }
    var clean = true
    for (batch in children.filter { !it.isDirectory }.chunked(BATCH)) {
      val outcomes = step(account, share, "cancellazione in $dir") { ctx ->
        batch.map { file -> attempt { ctx.share.deleteFile("$dir\\${file.name}") } }
      }
      for (outcome in outcomes) {
        if (outcome == Removal.DONE) {
          tally.removed++
        } else {
          tally.left++
          clean = false
        }
      }
    }
    for (sub in children.filter { it.isDirectory }) {
      if (!deleteTree(account, share, "$dir\\${sub.name}", tally)) clean = false
    }
    // Con una voce rimasta la cartella non e' vuota: ci pensera' l'account che puo' togliere anche quella.
    if (!clean) return false
    return when (step(account, share, "rimozione di $dir") { ctx -> attempt { ctx.share.removeEmptyDirectory(dir) } }) {
      Removal.DONE -> {
        tally.removed++
        true
      }
      else -> {
        tally.left++
        false
      }
    }
  }

  private suspend fun removeRoot(account: Account, share: String): Boolean {
    guard.allowRootRemoval()
    val outcome = step(account, share, "rimozione di mcg-it") { ctx -> attempt { ctx.share.removeEmptyDirectory(TestAreaGuard.ROOT) } }
    return outcome == Removal.DONE
  }

  /** Un'operazione sulla share con [account]; un errore del NAS (rete, sessione...) fa fallire la pulizia. */
  private suspend fun <T> step(account: Account, share: String, what: String, block: (OpContext) -> T): T =
    when (val result = account.manager.runOp(share, Purpose.BACKGROUND, idempotent = true, touchesDisk = true, op = block)) {
      is NasResult.Ok -> result.value
      is NasResult.Err -> throw failed(what, share, account, result.error)
    }

  private fun failed(what: String, share: String, account: Account, error: NasError) =
    AssertionError("pulizia fallita ($what su $share con ${account.label}): $error")

  private companion object {
    /** Un lotto = un'operazione corta (qualche centinaio di ms sul NAS), come la creazione dei file di prova. */
    const val BATCH = 100
  }
}
