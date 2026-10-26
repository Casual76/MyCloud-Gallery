package dev.pampa.mycloudgallery.core.albums.contract

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

// LA CONFERMA DELLA CONDIVISIONE (docs/fase7-progetto.md D12, §2.5 passo 2, §4.3 "Conferme"; C10, C26). Come `UserConfirmation` di
// core-nasops (F4-D24), di cui e' la prima delle tre chiavi:
//  - `ShareRunner.start` e le porte che spostano o caricano (`AlbumMover`, `AlbumUploader`) vogliono una [ShareConfirmation];
//  - [ShareConfirmation] ha il costruttore `internal`: la costruisce solo questo file ([ShareConfirmationIssuer]);
//  - [ShareConfirmationIssuer] e [ShareConfirmationSheet] li usa solo la UI (feature-albums, il foglio di condivisione di P10, e :app),
//    **dopo un tocco** dell'utente; dentro core-albums solo questo file, i test e `testing/` (Phase7ArchitectureTest);
//  - :app trasforma la conferma della condivisione nella conferma di core-nasops (`ConfirmationIssuer`, stesso piano) quando lega
//    `AlbumMover`: la seconda chiave. La terza e' il diario scritto prima di ogni passo.
// Nessun dato letto dal NAS (una definizione d'album, vera o falsa) puo' produrre una conferma: non c'e' strada nel codice.

/** L'impronta di un piano (SHA-256 di una codifica canonica): la conferma vale per **questo** piano e non per un altro. */
@JvmInline
value class SharePlanHash(val hex: String) {
  init {
    require(HEX_64.matches(hex)) { "Un'impronta di piano sono 64 cifre esadecimali minuscole." }
  }

  override fun toString(): String = "SharePlanHash(${hex.take(8)}…)"

  companion object {
    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    /** Deterministica: stesso piano (tipo, album, titolo, livello, nascosti, media e azioni nell'ordine), stessa impronta. */
    fun of(plan: SharePlan): SharePlanHash {
      val bytes = ByteArrayOutputStream()
      DataOutputStream(bytes).use { out ->
        out.writeUTF("mcg-share-plan")
        out.writeUTF(plan.kind.code)
        out.writeUTF(plan.album.value)
        out.writeUTF(plan.title)
        out.writeUTF(plan.folderLabel)
        out.writeUTF(plan.level?.code.orEmpty())
        out.writeUTF(plan.nonceHex.orEmpty())
        out.writeUTF(plan.hidden.code)
        out.writeInt(plan.items.size)
        for (item in plan.items) {
          out.writeUTF(item.media.value)
          out.writeUTF(item.action.code)
          out.writeLong(item.size)
          out.writeBoolean(item.isVideo)
          out.writeBoolean(item.hidden)
        }
        val excluded = plan.excluded.entries.sortedBy { it.key.code }
        out.writeInt(excluded.size)
        for ((reason, count) in excluded) {
          out.writeUTF(reason.code)
          out.writeInt(count)
        }
      }
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
      return SharePlanHash(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
  }
}

/**
 * La conferma di un piano di condivisione da parte dell'utente: costruita dal foglio dopo un suo tocco, non da dati. Vale per **un**
 * piano ([planHash]) e per poco tempo ([ShareConfirmationRules.MAX_AGE_MS], orologio monotono): non sopravvive a un riavvio.
 */
class ShareConfirmation internal constructor(val planHash: SharePlanHash, val issuedAtMonotonicMs: Long) {
  override fun toString(): String = "ShareConfirmation"
}

/** Chi costruisce le [ShareConfirmation]: la UI, dal gestore del tocco di conferma del foglio. Mai altro codice. */
object ShareConfirmationIssuer {
  fun issue(plan: SharePlan, nowMonotonicMs: Long): ShareConfirmation = ShareConfirmation(plan.hash, nowMonotonicMs)
}

/**
 * Il foglio di conferma come macchina a tocchi, uguale per ogni UI che lo mostra (condivisione, "Aggiungi ad album", "Da condividere",
 * "Riporta", adozione): un tocco. Un tocco arrivato meno di [ShareConfirmationRules.MIN_TAP_GAP_MS] dopo la comparsa del foglio non conta
 * (il secondo colpo di un doppio tocco sulla voce del menu, R6 della 4B).
 */
class ShareConfirmationSheet(val plan: SharePlan, private val shownAtMonotonicMs: Long? = null) {

  /** Un tocco di conferma dell'utente: la conferma, o null se il tocco e' troppo vicino alla comparsa del foglio. */
  fun tap(nowMonotonicMs: Long): ShareConfirmation? {
    val shown = shownAtMonotonicMs
    if (shown != null && nowMonotonicMs - shown in 0 until ShareConfirmationRules.MIN_TAP_GAP_MS) return null
    return ShareConfirmationIssuer.issue(plan, nowMonotonicMs)
  }
}

/** L'esito del controllo di una conferma contro il suo piano. */
sealed interface ShareConfirmationCheck {
  data object Valid : ShareConfirmationCheck

  /** La conferma e' di un altro piano. */
  data object WrongPlan : ShareConfirmationCheck

  /** Conferma o piano troppo vecchi: si conferma di nuovo, su un piano nuovo. */
  data object Expired : ShareConfirmationCheck

  /** Un istante monotono nel futuro: non e' di questo avvio del telefono. */
  data object FromTheFuture : ShareConfirmationCheck
}

object ShareConfirmationRules {
  /** 10 minuti di orologio monotono, come le conferme della 4B e i piani (§2.5 passo 1). */
  const val MAX_AGE_MS: Long = 10L * 60 * 1000

  const val MIN_TAP_GAP_MS: Long = 600

  /** Lo spazio in piu' richiesto a destinazione (F4: dimensione + 1 GiB). */
  const val SPACE_MARGIN_BYTES: Long = 1L shl 30

  fun check(plan: SharePlan, confirmation: ShareConfirmation, nowMonotonicMs: Long): ShareConfirmationCheck = when {
    confirmation.planHash != plan.hash -> ShareConfirmationCheck.WrongPlan
    confirmation.issuedAtMonotonicMs > nowMonotonicMs || plan.createdAtMonotonicMs > nowMonotonicMs -> ShareConfirmationCheck.FromTheFuture
    nowMonotonicMs - confirmation.issuedAtMonotonicMs > MAX_AGE_MS -> ShareConfirmationCheck.Expired
    nowMonotonicMs - plan.createdAtMonotonicMs > MAX_AGE_MS -> ShareConfirmationCheck.Expired
    else -> ShareConfirmationCheck.Valid
  }
}
