package dev.pampa.mycloudgallery.core.rules

/**
 * Quanto "pesa" una attivita': decide se vale la regola "le pesanti solo sul Wi-Fi di casa"
 * (piano §3, proposta 1). Leggere = sync dell'indice e backup foto; pesanti = tutto il resto.
 */
enum class ActivityWeight { LIGHT, HEAVY }

/**
 * I due tipi di servizio in primo piano di Android che hanno una quota di 6 h su 24 h per app e per
 * tipo (docs/android.md, scoperta 3). Servono a sapere QUALE quota si e' esaurita.
 */
enum class ForegroundBudget { DATA_SYNC, MEDIA_PROCESSING }

/** I ruoli che un dispositivo puo' avere, attivabili separatamente (piano, glossario). */
enum class DeviceRole { INDEXER, PREPARER }

/**
 * Le sei attivita' in background del piano (glossario, "Attivita'"), ognuna con regole proprie.
 *
 * Si chiama `BackgroundActivity` e non `Activity` per non scontrarsi con `android.app.Activity` negli
 * import di chi la usa.
 *
 * Decisioni di classificazione (da confermare, vedi docs/regole-attivita.md):
 *  - il **Calcolo AI** e' PESANTE: scarica le anteprime di analisi (~150 KB l'una) e brucia CPU/GPU.
 *  - se il NAS non c'e', il Calcolo AI lavora comunque sul buffer locale ([canRunOnLocalBuffer],
 *    E-A6); tutte le altre attivita' hanno bisogno del NAS.
 *
 * @property requiredRole ruolo che il dispositivo deve avere, o null se non ne serve nessuno.
 * @property cpuIntensive con il telefono caldo (LIGHT/MODERATE) va al ritmo ridotto.
 * @property budget quota di Android che consuma quando gira come servizio in primo piano.
 */
enum class BackgroundActivity(
  val weight: ActivityWeight,
  val budget: ForegroundBudget,
  val isVideo: Boolean = false,
  val requiredRole: DeviceRole? = null,
  val cpuIntensive: Boolean = false,
  val canRunOnLocalBuffer: Boolean = false,
) {
  /** Backup delle foto verso il NAS. */
  PHOTO_BACKUP(ActivityWeight.LIGHT, ForegroundBudget.DATA_SYNC),

  /** Backup dei video: file enormi, regole proprie (piano §2). */
  VIDEO_BACKUP(ActivityWeight.HEAVY, ForegroundBudget.DATA_SYNC, isVideo = true),

  /** Allineamento della replica locale dell'indice condiviso. */
  INDEX_SYNC(ActivityWeight.LIGHT, ForegroundBudget.DATA_SYNC),

  /** Miniature, anteprime e impronte dei file arrivati sul NAS in altro modo (ruolo Preparatore). */
  PREPARATION(
    ActivityWeight.HEAVY,
    ForegroundBudget.MEDIA_PROCESSING,
    requiredRole = DeviceRole.PREPARER,
    cpuIntensive = true,
  ),

  /** Embedding e OCR sulle anteprime (ruolo Indicizzatore). */
  AI_COMPUTE(
    ActivityWeight.HEAVY,
    ForegroundBudget.MEDIA_PROCESSING,
    requiredRole = DeviceRole.INDEXER,
    cpuIntensive = true,
    canRunOnLocalBuffer = true,
  ),

  /** Scaricamento delle anteprime ad alta qualita' per l'offline (solo con qualita' "Alta"). */
  HQ_PREVIEWS(ActivityWeight.HEAVY, ForegroundBudget.DATA_SYNC),
}
