package org.paramanuseniorshealth.notices.activation

import java.net.URI

/**
 * The code carried by an `https://paramanuseniorshealth.org/activation?code=...` link.
 *
 * Deliberately free of Android imports, like [org.paramanuseniorshealth.notices.fcm.FetchPolicy]:
 * the whole of the judgement about what counts as an activation link lives here and is testable on
 * the JVM, and the caller does nothing but hand over `intent.data` as a string.
 *
 * **The host and path checks are not redundant with the manifest.** MainActivity is exported, so
 * any installed app can send it an `ACTION_VIEW` intent carrying a URI the intent filter never
 * vetted. The filter decides what the *launcher and browser* may open; this decides what the app
 * is willing to read a code out of.
 *
 * Nothing here normalises or validates the code. Cleaning belongs to `CodeDashTransformation.clean`
 * and validity to [ActivationCode], and a link is deliberately put through exactly the same path as
 * a paste or a typed code -- so a bad code off a link fails where the user can see it, in the field
 * and on the button, rather than being silently discarded on the way in.
 */
object ActivationLink {

    private const val HOST = "paramanuseniorshealth.org"
    private const val PATH = "/activation"
    private const val SCHEME = "https"
    private const val PARAMETER = "code"

    /** The `code` parameter of [uri], or null when [uri] is not an activation link that carries one. */
    fun codeFrom(uri: String?): String? {
        if (uri.isNullOrBlank()) return null

        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (!SCHEME.equals(parsed.scheme, ignoreCase = true)) return null
        if (!HOST.equals(parsed.host, ignoreCase = true)) return null
        if (parsed.path?.trimEnd('/') != PATH) return null

        // getQuery, not getRawQuery: percent-escapes are already decoded for us.
        return parsed.query
            ?.split('&')
            ?.firstNotNullOfOrNull { pair ->
                pair.substringBefore('=').takeIf { it == PARAMETER }
                    ?.let { pair.substringAfter('=', "") }
            }
            ?.takeIf { it.isNotBlank() }
    }
}
