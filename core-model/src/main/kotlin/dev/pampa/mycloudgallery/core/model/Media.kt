package dev.pampa.mycloudgallery.core.model

/**
 * Un media: identita' stabile ([id]), una catena di [versions] e le [locations] in cui i byte di
 * quelle versioni si trovano. E' l'aggregato su cui lavorano le regole di `domain`: tutto cio' che
 * puo' cambiare (posti, byte, MediaStore) cambia **dentro** il media, mai l'identita'.
 *
 * Invarianti (verificati alla costruzione, quindi anche quando si rilegge dal database):
 * - almeno una versione, identificativi unici, **un solo** [VersionKind.ORIGINAL];
 * - la versione corrente e i genitori di ogni versione appartengono al media;
 * - ogni posizione contiene una versione del media;
 * - nessuna chiave di posizione compare due volte (un posto contiene un solo file).
 *
 * [owner] e' l'account che ha l'obbligo di tenere il media "al sicuro": chi l'ha scattato o ricevuto
 * sul telefono, o chi lo ha nella propria area privata. `null` = di famiglia o sconosciuto (un file
 * dell'area comune caricato da altri): non si fanno copie private dei media altrui.
 */
data class Media(
  val id: MediaId,
  val kind: MediaKind,
  val source: MediaSource,
  val owner: AccountId?,
  val createdAtMillis: Long,
  val currentVersionId: VersionId,
  val versions: List<MediaVersion>,
  val locations: List<MediaLocation>,
  val groupId: MediaGroupId? = null,
) {

  init {
    require(versions.isNotEmpty()) { "Un media ha almeno una versione." }
    val ids = versions.map { it.id }.toSet()
    require(ids.size == versions.size) { "Versioni duplicate." }
    require(versions.count { it.kind == VersionKind.ORIGINAL } == 1) { "Un media ha un solo originale." }
    require(currentVersionId in ids) { "La versione corrente non appartiene al media." }
    require(versions.all { it.parentId == null || it.parentId in ids }) { "Una versione ha un genitore estraneo." }
    require(locations.all { it.versionId in ids }) { "Una posizione contiene una versione estranea." }
    require(locations.map { it.location.key }.toSet().size == locations.size) { "Due posizioni nello stesso posto." }
    require(locations.map { it.id }.toSet().size == locations.size) { "Posizioni duplicate." }
  }

  val currentVersion: MediaVersion get() = version(currentVersionId)

  val originalVersion: MediaVersion get() = versions.single { it.kind == VersionKind.ORIGINAL }

  fun version(id: VersionId): MediaVersion =
    versions.firstOrNull { it.id == id } ?: throw NoSuchElementException("Versione sconosciuta.")

  fun location(id: LocationId): MediaLocation =
    locations.firstOrNull { it.id == id } ?: throw NoSuchElementException("Posizione sconosciuta.")

  fun locationsOf(versionId: VersionId): List<MediaLocation> = locations.filter { it.versionId == versionId }
}

/**
 * Dove si trova un media adesso, per i badge "solo telefono / solo NAS / entrambi" (piano 4.4).
 * Contano solo le posizioni [LocationState.PRESENT]: un file nel cestino di Galleria o su una microSD
 * tolta non si puo' aprire.
 */
enum class Presence(override val code: String) : StableCode {
  PHONE_ONLY("phone_only"),
  NAS_ONLY("nas_only"),
  PHONE_AND_NAS("phone_and_nas"),

  /** In nessun posto raggiungibile: non compare in Libreria, ma l'identita' resta (E-P11). */
  NONE("none"),
}

/**
 * Lo stato "al sicuro" (piano §2 e 5.3): un media e' al sicuro **solo** con una copia privata
 * verificata. I valori sono in ordine di gravita' crescente per le versioni da proteggere; [NOT_MINE]
 * e [LOST] riguardano il media intero.
 */
enum class SafetyStatus(override val code: String, internal val severity: Int) : StableCode {
  /** Ogni versione da proteggere ha una copia nell'area privata del proprietario, riletta e verificata. */
  SAFE("safe", 0),

  /** C'e' una copia privata, ma nessuno ha ancora riletto i suoi byte (o sono cambiati da allora). */
  PRIVATE_COPY_UNVERIFIED("unverified", 1),

  /**
   * Sul NAS c'e' solo una copia nell'area comune (o in una share di area sconosciuta): chiunque in
   * famiglia puo' cancellarla. L'app deve ricreare la copia privata (E-B13, E-B14).
   */
  ONLY_COMMON_COPY("only_common_copy", 2),

  /** Nessuna copia sul NAS: serve il backup. */
  NO_NAS_COPY("no_nas_copy", 3),

  /** Non e' un media di questo account: la sua sicurezza e' affare del proprietario. */
  NOT_MINE("not_mine", -1),

  /** Nessuna copia esiste piu' (es. cancellata prima del backup, E-B4). */
  LOST("lost", -1),
}
