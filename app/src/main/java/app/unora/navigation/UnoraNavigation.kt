package app.unora.navigation

/** Tiny navigation model kept independent of a navigation library. */
sealed interface UnoraDestination {
    data object Home : UnoraDestination
    data class Party(val partyId: String) : UnoraDestination
}

fun partyIdFromInvite(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    val direct = trimmed.uppercase()
    if (direct.matches(Regex("[A-Z0-9]{4,12}"))) return direct

    val custom = Regex("^unora://party/([A-Za-z0-9]{4,12})/?(?:[?#].*)?$", RegexOption.IGNORE_CASE)
        .matchEntire(trimmed)?.groupValues?.getOrNull(1)
    if (custom != null) return custom.uppercase()

    val web = Regex("^https?://(?:www\\.)?unora\\.app/p/([A-Za-z0-9]{4,12})/?(?:[?#].*)?$", RegexOption.IGNORE_CASE)
        .matchEntire(trimmed)?.groupValues?.getOrNull(1)
    return web?.uppercase()
}
