package dev.pampa.mycloudgallery.core.nasmedia.scan

import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/** Una sottocartella vista in un elenco: il nome, il percorso, il `fileId` e la data di modifica dell'elenco. */
internal class ScanFolder(val name: String, val path: SharePath, val fileId: Long?, val modifiedAt: Long)

/**
 * L'ordine in cui la scansione visita le sottocartelle di una cartella: **le piu' recenti per prime** (proposta 11), cosi'
 * in `Backup/<dispositivo>/AAAA/MM/` si parte dall'anno e dal mese piu' recenti e non dal 2009.
 *
 * 1. Per **data di modifica** decrescente, se l'elenco la da' ed e' affidabile: una data e' affidabile se e' un'epoca vera
 *    (dal 2000 in poi: lo zero e il 1601 di un valore perso, o un 1979 copiato da un vecchio disco, non sono date) e per
 *    averne un'informazione servono almeno due date diverse fra le cartelle datate. Una data nel futuro dell'ora del NAS
 *    (orologio sbagliato di chi ha scritto) conta come "adesso". La data e' limitata all'inizio del giro ([order] `ceiling`):
 *    una cartella toccata dal PC durante il giro non cambia posto a meta' strada.
 * 2. A pari data, e per le cartelle senza una data affidabile (che vengono dopo quelle datate), per **nome decrescente**: le
 *    cartelle che sono un anno (`\d{4}`) o un mese (`\d{2}`) prima, in ordine numerico decrescente; poi le altre, per nome
 *    decrescente senza distinguere maiuscole e forma Unicode. Se le date non dicono niente (tutte uguali, tutte perse) decide
 *    solo il nome.
 * 3. A pari nome (non succede su SMB) per il nome com'e' scritto: l'ordine e' totale e uguale a ogni giro.
 *
 * Il cursore della ripresa (`NasScanCursor.resumeAfter`) e' un percorso: l'ordine in cui riprendere lo ricostruisce lo
 * scanner (con la fotografia che tiene per tutta la durata del giro, vedi `DefaultNasScanner`).
 */
internal object FolderOrder {

  /** 2000-01-01: una data di modifica anteriore e' uno zero, un valore perso o una data copiata, non la data del contenuto. */
  const val MIN_PLAUSIBLE_MS: Long = 946_684_800_000L

  private val YEAR_OR_MONTH = Regex("^(\\d{4}|\\d{2})$")

  fun order(folders: List<ScanFolder>, ceiling: Long): List<ScanFolder> {
    if (folders.size < 2) return folders
    val byDate = datesTellSomething(folders, ceiling)
    return folders.sortedWith(
      Comparator { a, b ->
        if (byDate) {
          val da = dated(a)
          val db = dated(b)
          if (da != db) return@Comparator if (da) -1 else 1
          if (da) {
            val dates = minOf(b.modifiedAt, ceiling).compareTo(minOf(a.modifiedAt, ceiling))
            if (dates != 0) return@Comparator dates
          }
        }
        compareNames(a.name, b.name)
      },
    )
  }

  private fun dated(f: ScanFolder): Boolean = f.modifiedAt >= MIN_PLAUSIBLE_MS

  /** Le date servono solo se ci dicono qualcosa: almeno due date diverse fra le cartelle che ne hanno una vera. */
  fun datesTellSomething(folders: List<ScanFolder>, ceiling: Long): Boolean =
    folders.filter(::dated).map { minOf(it.modifiedAt, ceiling) }.distinct().size >= 2

  /** Negativo se [a] viene prima di [b]: anni e mesi in ordine numerico decrescente, poi gli altri nomi decrescenti. */
  fun compareNames(a: String, b: String): Int {
    val na = number(a)
    val nb = number(b)
    if (na != null && nb != null) {
      val byValue = nb.compareTo(na)
      if (byValue != 0) return byValue
    } else if (na != null) {
      return -1
    } else if (nb != null) {
      return 1
    }
    val byName = foldName(b).compareTo(foldName(a))
    return if (byName != 0) byName else b.compareTo(a)
  }

  private fun number(name: String): Int? = if (YEAR_OR_MONTH.matches(name)) name.toInt() else null
}
