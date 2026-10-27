package dev.pampa.mycloudgallery.core.database.faces.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Database dei volti, schema v1 (Fase 8, P6; `docs/fase8-progetto.md` D14, D15; `docs/modello-dati.md` §15): i corpi `faces` per chiave di
// contenuto. Regole comuni a tutte le tabelle del database dei volti:
// - **un file a parte per generazione** (`faces-<G>.db`): non e' mai una tabella del database della galleria, che resta v6;
// - i codici (`space`, `detector`, `source`, `tier`, `origin`, `state`) sono `StableCode.code` di core-model, scritti come testo; una riga
//   con un codice che questa versione non conosce (scritta da un'app piu' nuova) e' "non per me" e chi legge la salta;
// - **vettori, landmark e nomi non stanno mai in chiaro**: le colonne `secret` e `sealed_value` sono blocchi AES-256-GCM sigillati dalla
//   chiave dati della generazione (core-security, `FacesDataKey`) con un `aad` legato alla riga; il database non li interpreta;
// - nessun valore esce da qui nei log (D37): chiavi di contenuto, riquadri, id di persone, autori e blocchi non si stampano; i `toString()`
//   delle entita' dicono solo di che tabella sono.

/**
 * I volti di un contenuto come li ha calcolati **un** dispositivo dell'account ([author]): un corpo `faces` (§2.4). Una riga per
 * (chiave di contenuto, autore). [isWinner] e' il vincitore fra gli autori della stessa chiave (la ricetta piu' alta, poi l'autore
 * minore: `FaceBody.winner`): lo rifa' [dev.pampa.mycloudgallery.core.database.faces.dao.FaceBodyDao.replaceBody] nella stessa
 * transazione che scrive il corpo, cosi' le letture del raggruppamento sono un solo scorrimento dell'indice.
 *
 * [secret] = i landmark e i vettori di **tutti** i volti del corpo in un solo blocco sigillato (un'operazione AES per contenuto, non per
 * volto); [digest] = le prime 8 byte dello SHA-256 del corpo codificato (entra nei riferimenti dell'istantanea).
 */
@Entity(
  tableName = "face_body",
  primaryKeys = ["content_key", "author"],
  indices = [Index(value = ["is_winner", "space", "content_key"])],
)
class FaceBodyEntity(
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "author") val author: String,
  @ColumnInfo(name = "is_winner", defaultValue = "0") val isWinner: Boolean = false,
  @ColumnInfo(name = "space") val space: String,
  @ColumnInfo(name = "detector") val detector: String,
  @ColumnInfo(name = "recipe") val recipe: Int,
  @ColumnInfo(name = "source") val source: String,
  @ColumnInfo(name = "digest") val digest: Long,
  @ColumnInfo(name = "face_count") val faceCount: Int,
  @ColumnInfo(name = "secret", typeAffinity = ColumnInfo.BLOB) val secret: ByteArray,
) {
  override fun toString(): String = "FaceBodyEntity($faceCount volti)"
}

/**
 * Un volto di un corpo: i campi **in chiaro** che servono alle query (livello, fotogramma, riquadro, punteggio...). Riquadro e livello non
 * sono segreti (D14 cifra vettori, centroidi e nomi) e le query del raggruppamento e dei "volti non riconoscibili" ne hanno bisogno senza
 * decifrare. Chiave esterna sul corpo, in cascata: sostituire o togliere un corpo toglie i suoi volti.
 */
@Entity(
  tableName = "face",
  primaryKeys = ["content_key", "author", "idx"],
  foreignKeys = [
    ForeignKey(
      entity = FaceBodyEntity::class,
      parentColumns = ["content_key", "author"],
      childColumns = ["content_key", "author"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
)
class FaceEntity(
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "author") val author: String,
  @ColumnInfo(name = "idx") val index: Int,
  @ColumnInfo(name = "frame") val frame: Int,
  @ColumnInfo(name = "x0") val x0: Int,
  @ColumnInfo(name = "y0") val y0: Int,
  @ColumnInfo(name = "x1") val x1: Int,
  @ColumnInfo(name = "y1") val y1: Int,
  @ColumnInfo(name = "score") val score: Int,
  @ColumnInfo(name = "tier") val tier: String,
  @ColumnInfo(name = "reasons") val reasons: Int,
  @ColumnInfo(name = "side_px") val sidePx: Int,
  @ColumnInfo(name = "yaw") val yaw: Int,
  @ColumnInfo(name = "sharpness") val sharpness: Int,
) {
  override fun toString(): String = "FaceEntity($tier)"
}

/** Un contenuto in un formato che non si decodifica: "non supportato" con la ricetta [recipe] (non si riprova finche' la ricetta non cambia). */
@Entity(tableName = "face_unsupported", primaryKeys = ["content_key"])
class FaceUnsupportedEntity(
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "recipe") val recipe: Int,
) {
  override fun toString(): String = "FaceUnsupportedEntity"
}
