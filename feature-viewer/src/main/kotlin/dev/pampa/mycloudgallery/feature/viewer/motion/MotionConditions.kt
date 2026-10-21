package dev.pampa.mycloudgallery.feature.viewer.motion

import android.content.Context
import android.os.PowerManager
import kotlin.math.abs

/**
 * L'interruttore "Animazione delle foto in movimento" delle Impostazioni della Libreria (D50, risposta Q16 (b)): impostazione
 * personale sincronizzata fra i dispositivi dell'utente, **accesa di default** (voce assente = accesa). `:app` lo lega a
 * `MotionPersonalSettings.motionAnimation` di core-personal (P11): il viewer non conosce quel modulo, legge solo questo.
 *
 * Spento vale "mai l'animazione d'apertura"; il badge "Movimento" funziona sempre.
 */
fun interface MotionAnimationSetting {
  fun isOn(): Boolean

  companion object {
    /** Acceso: il valore di serie. */
    val On: MotionAnimationSetting = MotionAnimationSetting { true }
  }
}

/** Il risparmio energetico del telefono e' acceso (D50: con il risparmio energetico niente animazione d'apertura). */
fun interface PowerSaving {
  fun isOn(): Boolean

  companion object {
    /** Quello di sistema: `PowerManager.isPowerSaveMode`. */
    fun system(context: Context): PowerSaving {
      val power = context.applicationContext.getSystemService(PowerManager::class.java)
      return PowerSaving { power?.isPowerSaveMode == true }
    }
  }
}

/** Perche' l'animazione d'apertura non parte (D50). Il badge "Movimento" non ne risente. */
internal enum class MotionSkip {
  /** L'interruttore "Animazione delle foto in movimento" e' spento. */
  SETTING_OFF,

  /** "Rimuovi animazioni" di sistema (`LocalFluidMotionPolicy.reducedMotion`). */
  REDUCED_MOTION,

  /** Il risparmio energetico e' acceso. */
  POWER_SAVING,
}

/** Le condizioni dell'animazione d'apertura (D50, §2.11): funzioni pure. */
internal object MotionConditions {

  /** Quanto puo' differire la proporzione del video da quella della foto: oltre il 3% niente animazione (E-V17). */
  const val ASPECT_TOLERANCE: Float = 0.03f

  /** Il primo motivo per cui l'animazione non parte, o null se puo'. */
  fun skip(settingOn: Boolean, reducedMotion: Boolean, powerSaving: Boolean): MotionSkip? = when {
    !settingOn -> MotionSkip.SETTING_OFF
    reducedMotion -> MotionSkip.REDUCED_MOTION
    powerSaving -> MotionSkip.POWER_SAVING
    else -> null
  }

  /**
   * Le proporzioni del video (gia' con la rotazione della traccia, E-V17) e della foto differiscono al piu' del 3%?
   * Una proporzione non nota ([photoAspect] <= 0) non blocca: la foto la conosce il catalogo, il video il lettore.
   */
  fun aspectCompatible(videoAspect: Float, photoAspect: Float): Boolean {
    if (photoAspect <= 0f || videoAspect <= 0f) return true
    return abs(videoAspect / photoAspect - 1f) <= ASPECT_TOLERANCE
  }
}
