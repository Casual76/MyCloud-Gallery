package dev.pampa.mycloudgallery.core.backup.safety

import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.plan.BackupPaths
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale

// Fase 5A, P3 (docs/fase5-progetto.md §2.3, D4, D8, D9, D14, F4-D8, L28): la RILETTURA COMPLETA di un file del NAS, l'unica prova che
// questo pacchetto accetta per "al sicuro", per un collegamento della deduplica e per l'hash completo di `FULL_HASH`. Comune a deduplica
// ([PrivateCopyFinder]), verifiche ([BackupSafetyVerifier]) e guardiano ([BackupPrivateCopyGuardian]).
//
// Regole: mai una cache (ogni byte arriva dal NAS adesso, `readAll`); il timbro dell'apertura e' quello che si registra; con
// [NasRereader.reread] `stableAfter` il file si ricontrolla dopo la lettura (stesso timbro, altrimenti la lettura non vale: un file che il
// PC cambia mentre lo leggiamo non dimostra niente). Nessun nome, percorso, share o impronta esce di qui nei messaggi.

/** L'esito di una rilettura completa. */
internal sealed interface Reread {
  /** Letto per intero: [sha] dei byte letti adesso, [stamp] dell'apertura (sullo stesso handle). */
  class Ok(val sha: Sha256, val stamp: NasStamp) : Reread {
    override fun toString(): String = "Reread.Ok(${stamp.size} B)"
  }

  /** Il file non c'e' (o e' una cartella). */
  data object Missing : Reread

  /** Aperto dal PC (`FileBusy`, E-N13): si riprova piu' tardi. */
  data object Busy : Reread

  /** Il timbro non e' quello atteso, o e' cambiato durante la lettura: i byte letti non dimostrano niente. */
  data object Changed : Reread

  /** Dimensione o impronta rapida diverse da quelle attese: certamente altri byte (letti solo testa e coda). */
  data object Different : Reread

  /** Un errore del NAS che ferma il giro (rete, sessione, autenticazione, permessi). */
  class Fail(val error: NasError) : Reread {
    override fun toString(): String = "Reread.Fail(${error::class.simpleName})"
  }
}

/** La rilettura completa dal NAS ([NasMediaReader.readAll]: 4 flussi, SHA-256 mentre arriva, sorvegliante per blocco). */
internal class NasRereader(private val reader: NasMediaReader, private val purpose: Purpose) {

  /**
   * Rilegge per intero [path] di [share].
   *
   * @param expectStamp se non null, il timbro all'apertura deve essere questo (`NasStamp.sameAs`), altrimenti [Reread.Changed].
   * @param expectSize se non null, la dimensione all'apertura deve essere questa, altrimenti [Reread.Different].
   * @param expectQuick se non null, prima della lettura completa si leggono testa e coda (`qh1`, 128 KiB al massimo): diverso =
   *   [Reread.Different] senza leggere il resto (un video da GB della stessa dimensione non si rilegge per niente).
   * @param stableAfter dopo la lettura, uno `stat`: il timbro deve essere ancora quello dell'apertura.
   * @param onBytes i byte letti (il traffico, il conteggio del giro).
   */
  suspend fun reread(
    share: String,
    path: SharePath,
    expectStamp: NasStamp? = null,
    expectSize: Long? = null,
    expectQuick: QuickHash? = null,
    stableAfter: Boolean = true,
    onBytes: (Long) -> Unit = {},
  ): Reread {
    val file = when (val opened = reader.open(share, path, purpose)) {
      is NasResult.Err -> return outcomeOf(opened.error)
      is NasResult.Ok -> opened.value
    }
    file.use { f ->
      if (expectStamp != null && !f.stamp.sameAs(expectStamp)) return Reread.Changed
      if (expectSize != null && f.size != expectSize) return Reread.Different
      if (expectQuick != null) {
        val quick = try {
          quickOf(f, onBytes)
        } catch (e: NasIoException) {
          return outcomeOf(e.error)
        } ?: return Reread.Changed
        if (quick != expectQuick) return Reread.Different
      }
      val sha = when (val all = reader.readAll(f, { _, n -> onBytes(n.toLong()) }, null)) {
        is NasResult.Err -> return outcomeOf(all.error)
        is NasResult.Ok -> all.value
      }
      if (stableAfter) {
        when (val st = reader.stat(share, path, purpose)) {
          is NasResult.Err -> return outcomeOf(st.error)
          is NasResult.Ok -> {
            val entry = st.value ?: return Reread.Missing
            if (entry.isDirectory || !entry.stamp.sameAs(f.stamp)) return Reread.Changed
          }
        }
      }
      return Reread.Ok(sha, f.stamp)
    }
  }

  /** Il timbro attuale di [path] (null = assente o cartella). */
  suspend fun stamp(share: String, path: SharePath): NasResult<NasStamp?> = when (val st = reader.stat(share, path, purpose)) {
    is NasResult.Err -> st
    is NasResult.Ok -> NasResult.Ok(st.value?.takeIf { !it.isDirectory }?.stamp)
  }

  /** `qh1` del file aperto (testa e coda, sullo stesso handle); null se il file si accorcia mentre lo leggiamo. */
  private suspend fun quickOf(file: NasRandomFile, onBytes: (Long) -> Unit): QuickHash? {
    val size = file.size
    val regions = QuickHashAlgorithm.regions(size)
    val head = if (regions.isEmpty()) ByteArray(0) else file.readRange(regions[0])
    val tail = if (regions.size > 1) file.readRange(regions[1]) else ByteArray(0)
    onBytes((head.size + tail.size).toLong())
    if (regions.isNotEmpty() && head.size.toLong() != regions[0].last - regions[0].first + 1) return null
    if (regions.size > 1 && tail.size.toLong() != regions[1].last - regions[1].first + 1) return null
    return QuickHashAlgorithm.compute(size, head, tail)
  }

  companion object {
    /** Un errore del NAS come esito di una lettura: il file assente o occupato non ferma il giro, il resto si'. */
    fun outcomeOf(error: NasError): Reread = when (error) {
      NasError.NotFound -> Reread.Missing
      NasError.FileBusy -> Reread.Busy
      else -> Reread.Fail(error)
    }
  }
}

/** Funzioni comuni del pacchetto `safety`. */
internal object SafetyCommon {

  /** Il problema del giro per un errore del NAS (§2.2 "Rete", §2.6): come il motore. */
  fun problemFor(error: NasError): BackupProblemCode = when (error) {
    NasError.NasFull -> BackupProblemCode.NAS_FULL
    NasError.QuotaExceeded -> BackupProblemCode.QUOTA
    is NasError.Auth, is NasError.AuthBlocked, NasError.CredentialsMissing -> BackupProblemCode.AUTH
    NasError.PermissionDenied -> BackupProblemCode.READ_ONLY_AREA
    else -> BackupProblemCode.NAS_UNREACHABLE
  }

  /** Le share si confrontano senza maiuscole (Samba). */
  fun sameShare(a: String, b: String): Boolean = a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)

  /**
   * Il nome "nome (n)" del tentativo [attempt] (0 = il nome previsto, 1 = "(2)"...), nella stessa cartella: l'estensione resta (E-B3,
   * come il motore e i nomi alternativi della 4B). Un nome che con " (n)" supererebbe i 255 byte si accorcia (R1 della revisione 5A):
   * la regola e' una sola, [BackupPaths.alternateName].
   */
  fun nameFor(planned: SharePath, attempt: Int): SharePath = BackupPaths.alternateName(planned, attempt)
}
