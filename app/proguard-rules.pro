# Regole R8 dell'app. Quelle dei moduli e delle librerie (Hilt, Room, Compose, engine) viaggiano con i
# moduli: qui va solo cio' che riguarda l'app.

# --- Nomi e tracce ------------------------------------------------------------------------------------
# R8 e' attivo (taglia il codice morto, ottimizza, restringe le risorse) ma NON rinomina: come in
# FluidWeather, perche' l'app si prova su un telefono senza computer attaccato e la diagnostica in app
# (piano 2.7, esportazione manuale) deve poter mostrare una traccia leggibile. Offuscare non protegge
# nulla che qui valga la pena proteggere: la sicurezza sta nelle firme e nel Keystore, non nei nomi.
# Per riattivare l'offuscamento basta togliere queste due righe (e conservare mapping.txt di ogni release).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# --- Log delle librerie silenziati in release ----------------------------------------------------------
# Le librerie (SMB, Room, WorkManager...) possono scrivere su logcat con android.util.Log; in release
# quelle chiamate spariscono dal bytecode (docs/sicurezza.md, C22). Si tengono solo gli errori (e): un
# crash deve restare diagnosticabile. Il logger della galleria (GalleryLog) non passa da qui: in release
# non ha alcun sink.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static boolean isLoggable(...) return false;
}

# --- Fase 5A (P7, D12): il cancello del backup ----------------------------------------------------------------------
# "Backup solo sulle share di prova" e' la regola piu' importante del backup: il cancello (`AppBackupGate.stateOf`) chiama sempre la
# decisione per share (`FeatureFlags.backupMayWriteTo`) e risponde `TEST_ONLY` se la share non e' di prova. R8 non deve ne' togliere il
# cancello ne' fonderlo in un'altra classe (la decisione la tiene core-common/consumer-rules.pro: un membro tenuto non si inlinea): tools/check.ps1 (passo "D12 nella release") legge il
# bytecode della release (dexdump) e prova che `stateOf` chiama `backupMayWriteTo` e risponde TEST_ONLY. Il campo e la decisione li tiene
# core-common/consumer-rules.pro.
-keep class dev.pampa.mycloudgallery.backup.AppBackupGate {
    public *** stateOf(java.lang.String);
}
