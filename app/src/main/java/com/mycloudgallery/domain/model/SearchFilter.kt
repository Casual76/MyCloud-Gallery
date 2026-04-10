package com.mycloudgallery.domain.model

/**
 * Filtri opzionali applicabili a una ricerca.
 * Tutti i campi null = nessun filtro attivo.
 */
data class SearchFilter(
    val mediaType: MediaType? = null,
    val fromTimestamp: Long? = null,
    val toTimestamp: Long? = null,
    val hasGps: Boolean? = null,
    val hasOcr: Boolean? = null,
    val favoritesOnly: Boolean = false,
    val duplicatesOnly: Boolean = false,
)
