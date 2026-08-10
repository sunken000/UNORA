package app.unora.util

import java.net.URI

object InviteParser {
    private val partyId = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$")

    fun parse(value: String): Result<String> = runCatching {
        val trimmed = value.trim()
        val candidate = when {
            partyId.matches(trimmed.uppercase()) -> trimmed
            else -> extractFromUri(trimmed)
        }.uppercase()
        require(partyId.matches(candidate)) { "Código de party inválido." }
        candidate
    }

    private fun extractFromUri(value: String): String {
        val uri = URI(value)
        val isUnoraUri = uri.scheme.equals("unora", ignoreCase = true) && uri.host.equals("party", ignoreCase = true)
        val isUnoraLink = uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("unora.app", ignoreCase = true)
        require(isUnoraUri || isUnoraLink) { "Link de convite inválido." }
        val pieces = uri.path.trim('/').split('/').filter(String::isNotBlank)
        return when {
            isUnoraUri && pieces.size == 1 -> pieces.single()
            isUnoraLink && pieces.size == 2 && pieces.first().equals("p", ignoreCase = true) -> pieces.last()
            else -> throw IllegalArgumentException("Link de convite inválido.")
        }
    }
}
