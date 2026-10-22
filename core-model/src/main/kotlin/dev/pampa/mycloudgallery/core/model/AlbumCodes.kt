package dev.pampa.mycloudgallery.core.model

// Fase 7 (`docs/fase7-progetto.md` §1.3 "core-model", D4, D7, D10, D19, D21, D34): i codici stabili degli album. Firme di P0; il
// database v6 di P1 li salva con [StableCode.code], i dati personali (P3) e il formato `album` di P2 li scrivono con lo stesso codice.
// Sono contratti fra dispositivi: si **aggiungono**, non si cambiano e non si riusano (`StableCodesTest`, `Phase7TypesTest`). Un codice
// sconosciuto letto da un'altra app non si interpreta: il codec lo rifiuta (P2), il database non lo scrive mai.

/**
 * Il livello di permesso di un album condiviso (D10, §2.4 del progetto). Lo sceglie il creatore (o l'erede) e lo fa rispettare l'app,
 * due volte: chi scrive non offre l'azione, chi legge scarta la voce (la tabella e' `AlbumPermissions` di core-albums).
 */
enum class ShareLevel(override val code: String) : StableCode {
  /** "Tutti possono tutto". */
  ALL("all"),

  /** "Gli altri aggiungono": tutti aggiungono, ognuno toglie solo le sue, il resto lo decide il creatore. */
  ADD("add"),

  /** "Gli altri guardano": solo il creatore cambia l'album. */
  VIEW("view"),
  ;

  companion object {
    /** Il livello proposto alla creazione: risposta Q2 (a) del 22/10, "Gli altri aggiungono". */
    val DEFAULT: ShareLevel = ADD

    fun fromCode(code: String?): ShareLevel? = enumByCodeOrNull<ShareLevel>(code)
  }
}

/**
 * Che album e' (D4, campo `kind` della voce `ALBUM`). Un album condiviso non e' un tipo a parte: e' un album manuale o smart del
 * creatore pubblicato nell'area comune (D7, D17). Per chi lo riceve e' sempre un elenco di elementi: le regole restano personali.
 */
enum class AlbumKind(override val code: String) : StableCode {
  /** Elementi scelti a mano. */
  MANUAL("manual"),

  /** Il nome diventa regole (D13-D16): sicuri dentro da soli, dubbi in "Suggerite". */
  SMART("smart"),
  ;

  companion object {
    fun fromCode(code: String?): AlbumKind? = enumByCodeOrNull<AlbumKind>(code)
  }
}

/**
 * L'ordine **dentro** un album (D21; campo `sort` della voce `ALBUM` e voce `sort` di un album condiviso). L'ordine dell'**elenco**
 * degli album e' un'altra cosa (`AlbumListSort` di core-albums, impostazione `albums_sort`).
 */
enum class AlbumSort(override val code: String) : StableCode {
  /** "Dal piu' vecchio": predefinito per gli album smart e quelli nati da un viaggio. */
  OLDEST_FIRST("oldest"),

  /** "Dal piu' recente": predefinito per gli album manuali. */
  NEWEST_FIRST("newest"),

  /** "Personalizzato": le posizioni degli elementi (a intervalli, D21). */
  CUSTOM("custom"),
  ;

  companion object {
    /** L'ordine predefinito per un album di [kind] (D21). */
    fun defaultFor(kind: AlbumKind): AlbumSort = when (kind) {
      AlbumKind.MANUAL -> NEWEST_FIRST
      AlbumKind.SMART -> OLDEST_FIRST
    }

    fun fromCode(code: String?): AlbumSort? = enumByCodeOrNull<AlbumSort>(code)
  }
}

/** Che proposta e' (D19, tabella `trip_proposal` di v6). */
enum class TripKind(override val code: String) : StableCode {
  /** Giorni lontani da casa (D19): serve la "casa" dedotta in locale (D18). */
  TRIP("trip"),

  /** Molte foto in poche ore, fuori da un viaggio (D19): non serve il GPS (E-AL16). */
  EVENT("event"),
  ;

  companion object {
    fun fromCode(code: String?): TripKind? = enumByCodeOrNull<TripKind>(code)
  }
}
