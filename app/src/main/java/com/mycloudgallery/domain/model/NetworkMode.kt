package com.mycloudgallery.domain.model

enum class NetworkMode {
    /** Connessione diretta al NAS via IP locale */
    LOCAL,
    /** Connessione tramite relay WD MyCloud */
    RELAY,
    /** Nessuna connessione — modalità cache */
    OFFLINE,
}
