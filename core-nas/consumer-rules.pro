# Regole R8 di :core-nas, applicate dall'app che lo usa (android-conventions.gradle le aggancia da sole).
# docs/fase2-progetto.md §3.1, riga R8; rischio "smbj giovane; R8 toglie i gestori di mbassador" (§7).

# --- mbassador (bus di eventi interno di smbj) -----------------------------------------------------------
# smbj notifica la chiusura delle sessioni e delle connessioni con mbassador, che trova i gestori per
# RIFLESSIONE (annotazione @Handler) e crea i suoi oggetti per nome. R8 non vede quelle chiamate: senza
# queste righe toglie i gestori e in release le chiusure non arrivano a nessuno (nessun errore, solo
# connessioni "zombie"). Si verifica sul campo con l'APK di release (F11).
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler *;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# --- smbj ---------------------------------------------------------------------------------------------
# Lo stesso vale per i gestori dentro smbj (pacchetti event/connection/session) e per le fabbriche di
# messaggi e di autenticatori create per nome. Si tiene smbj intero: e' la parte da cui dipende la
# sicurezza della sessione e ottimizzarla non vale il rischio.
-keep class com.hierynomus.** { *; }

# --- Riferimenti opzionali che su Android non esistono ---------------------------------------------------
# Sono i soli riferimenti mancanti che R8 segnala (missing_rules.txt della release, Fase 2 P0):
#  - mbassador ha un filtro facoltativo basato su Java EL (javax.el, dei server Java EE): smbj non lo usa;
#  - smbj ha un autenticatore Kerberos (GSS-API, org.ietf.jgss): usiamo solo NTLM.
# Android non ha nessuno dei due. Le classi restano nell'APK per le -keep qui sopra ma non si caricano mai.
# Nota: slf4j-api 2.x cerca i provider con ServiceLoader; nessun provider (scelta voluta) = nessun log.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
