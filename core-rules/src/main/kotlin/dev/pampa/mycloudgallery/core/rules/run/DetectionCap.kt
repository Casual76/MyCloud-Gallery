package dev.pampa.mycloudgallery.core.rules.run

import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.WorkRequest

/**
 * Il **tetto del rilevamento fuori casa** (D22, §2.3 e §2.8 di docs/fase4-progetto.md): fuori dal Wi-Fi di casa il
 * rilevamento dei file nuovi o cambiati (testa e coda da 64 KiB, impronta, metadati) legge al massimo
 * [OFF_HOME_BYTES_PER_ROUND] per giro; il resto aspetta il Wi-Fi di casa o il giro dopo, dal cursore salvato. A casa nessun
 * tetto (la prima scansione dell'archivio vero legge ~2-3 GB).
 *
 * E' una funzione pura delle regole: `:app` (P11) la applica a `ScanSettings.detectionByteCap` di `core-nasmedia` con il
 * verdetto `Run` appena ottenuto (o la lascia fare a [StagedRun] con `roundCap = DetectionCap::forNetwork`). Il tetto
 * mensile dei dati ([RuleDecision.Run.remainingBytes]) resta in vigore sopra questo: vince il piu' basso.
 *
 * Non vale per il **contenuto visibile** con l'app aperta ([WorkRequest.VisibleContent]): i media sullo schermo si
 * rilevano subito (D23, Q7); li limita solo il tetto mensile.
 */
object DetectionCap {

  /** 50 MB (decimali, come i tetti dei dati) per giro, fuori casa. */
  const val OFF_HOME_BYTES_PER_ROUND: Long = 50_000_000L

  /**
   * Il tetto di un giro di rilevamento sulla rete [network]: `null` = nessun tetto (Wi-Fi di casa), [OFF_HOME_BYTES_PER_ROUND]
   * su ogni altra rete che porta traffico, zero dove il traffico non puo' passare (offline, rete non riconosciuta).
   */
  fun forNetwork(network: NetworkClass): Long? =
    when (network) {
      NetworkClass.HOME_WIFI -> null
      NetworkClass.EXTERNAL_WIFI,
      NetworkClass.MOBILE,
      NetworkClass.ROAMING -> OFF_HOME_BYTES_PER_ROUND
      NetworkClass.OFFLINE,
      NetworkClass.UNTRUSTED -> 0L
    }

  /**
   * Quanti byte puo' leggere un giro di rilevamento deciso da [run] per la richiesta [request]: il piu' basso fra il
   * tetto fuori casa e i byte che restano nel tetto mensile. `null` = nessun limite.
   *
   *  - [RunScope.LOCAL_BUFFER_ONLY] (niente rete utile): zero, il rilevamento legge dal NAS e qui non si puo'.
   *  - [WorkRequest.VisibleContent]: solo il tetto mensile.
   */
  fun byteCap(request: WorkRequest, run: RuleDecision.Run): Long? {
    if (run.scope == RunScope.LOCAL_BUFFER_ONLY) return 0L
    val round = if (request is WorkRequest.VisibleContent) null else forNetwork(run.network)
    return lowest(round, run.remainingBytes)
  }
}

/** Il minore fra due limiti opzionali (`null` = nessun limite). */
internal fun lowest(a: Long?, b: Long?): Long? =
  when {
    a == null -> b
    b == null -> a
    else -> minOf(a, b)
  }
