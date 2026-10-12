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
