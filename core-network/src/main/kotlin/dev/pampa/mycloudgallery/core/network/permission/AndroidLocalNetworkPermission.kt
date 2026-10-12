package dev.pampa.mycloudgallery.core.network.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkPermission

/**
 * Il permesso di rete locale di Android 17 (`ACCESS_LOCAL_NETWORK`, docs/android.md 7.3 e "Novita' di Android 17").
 * Il nome e' qui come testo perche' `Manifest.permission.ACCESS_LOCAL_NETWORK` esiste solo dall'SDK 37 e l'app si
 * compila con l'SDK 36.
 */
object LocalNetworkPermissions {
  const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

  /** Android 17: da qui il permesso esiste. */
  const val FIRST_SDK_WITH_PERMISSION = 37

  /**
   * Le regole, senza toccare Android:
   *  - prima di Android 17 il permesso non esiste ([LocalNetworkAccess.GRANTED_IMPLICIT]). Su Android 16 c'e'
   *    una restrizione opt-in (`RESTRICT_LOCAL_NETWORK`, solo da `adb`) che da qui non si legge;
   *  - su Android 17 con **target 36** la concessione e' implicita ("temporanea" per Android): se risulta
   *    concessa e' [LocalNetworkAccess.GRANTED_IMPLICIT], se no [LocalNetworkAccess.DENIED];
   *  - con **target 37** serve la richiesta a runtime: concessa = [LocalNetworkAccess.GRANTED].
   */
  fun access(sdkInt: Int, targetSdk: Int, granted: Boolean): LocalNetworkAccess =
    when {
      sdkInt < FIRST_SDK_WITH_PERMISSION -> LocalNetworkAccess.GRANTED_IMPLICIT
      !granted -> LocalNetworkAccess.DENIED
      targetSdk >= FIRST_SDK_WITH_PERMISSION -> LocalNetworkAccess.GRANTED
      else -> LocalNetworkAccess.GRANTED_IMPLICIT
    }
}

/**
 * Legge il permesso di rete locale. Il ramo per il target 37 e' pronto: oggi (target 36) il risultato e'
 * `GRANTED_IMPLICIT`; quando `:app` passera' a 37, basta dichiarare il permesso nel manifest e chiederlo a runtime
 * (nel passo "Permessi" dell'onboarding), e questa classe comincia a dire `GRANTED` o `DENIED`.
 *
 * Limite noto (T-19): con la restrizione opt-in attiva su un'app con target 36
 * (`am compat enable RESTRICT_LOCAL_NETWORK`) non e' detto che lo stato del permesso cambi; l'SDK 37 non espone un
 * motivo di blocco leggibile da Java. In quel caso il segnale arriva da fuori (TCP in timeout con rete valida).
 */
class AndroidLocalNetworkPermission(
  context: Context,
  private val sdkInt: Int = Build.VERSION.SDK_INT,
) : LocalNetworkPermission {

  private val context: Context = context.applicationContext ?: context

  override fun status(): LocalNetworkAccess {
    val granted =
      sdkInt >= LocalNetworkPermissions.FIRST_SDK_WITH_PERMISSION &&
        ContextCompat.checkSelfPermission(context, LocalNetworkPermissions.PERMISSION) == PackageManager.PERMISSION_GRANTED
    return LocalNetworkPermissions.access(sdkInt, context.applicationInfo.targetSdkVersion, granted)
  }
}
