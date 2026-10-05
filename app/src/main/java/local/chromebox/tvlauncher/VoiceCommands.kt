package local.chromebox.tvlauncher

import java.text.Normalizer

/** What a recognized sentence asks the launcher to do. */
sealed class VoiceAction {
    data class LaunchApp(val pkg: String, val label: String) : VoiceAction()
    data class OpenWeb(val shortcut: WebShortcut) : VoiceAction()
    /** [shortcut] null means a Google search. */
    data class Search(val shortcut: WebShortcut?, val query: String) : VoiceAction()
    /** No clear command: offer search choices for [query]. */
    data class Choose(val query: String) : VoiceAction()
}

/**
 * Turns Vietnamese voice commands into launcher actions. Matching ignores case and
 * diacritics, so "mở youtube" and "mo youtube" behave the same.
 *
 * - "mở <app or page>" launches an app or opens a pinned page
 * - "tìm trên <page> <query>", "<page> <query>" or "<query> trên <page>" searches that page
 * - "tìm trên google <query>" or "google <query>" searches Google
 * - anything else returns [VoiceAction.Choose]
 */
object VoiceCommands {

    private val OPEN_PREFIXES = listOf(
        listOf("mo", "ung", "dung"), listOf("mo", "trang"), listOf("mo"), listOf("chay"), listOf("vao")
    )
    private val SEARCH_PREFIXES = listOf(
        listOf("tim", "kiem", "tren"), listOf("tim", "tren"), listOf("tim", "kiem"), listOf("tim")
    )

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd')
            .replace(Regex("\\s+"), " ")
            .trim()

    /** [apps] holds (label, package) pairs. */
    fun parse(text: String, apps: List<Pair<String, String>>, web: List<WebShortcut>): VoiceAction {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val norm = words.map { normalize(it) }
        if (words.isEmpty()) return VoiceAction.Choose("")

        fun startsWith(prefix: List<String>) = norm.size >= prefix.size && norm.subList(0, prefix.size) == prefix
        fun endsWith(suffix: List<String>) =
            norm.size > suffix.size && norm.subList(norm.size - suffix.size, norm.size) == suffix
        fun rest(from: Int) = words.drop(from).joinToString(" ")

        // "mở ..." launches the best matching app or page
        for (prefix in OPEN_PREFIXES) {
            if (startsWith(prefix) && norm.size > prefix.size) {
                val target = norm.drop(prefix.size).joinToString(" ")
                bestMatch(target, apps, web)?.let { return it }
            }
        }

        // Searches on a pinned page that has a search address
        val searchable = web.filter { it.search.isNotEmpty() }
        for (shortcut in web) {
            val title = normalize(shortcut.title).split(" ")
            val leads = SEARCH_PREFIXES.map { it + title } + listOf(title)
            for (lead in leads) {
                if (startsWith(lead)) {
                    val query = rest(lead.size)
                    return when {
                        query.isEmpty() -> VoiceAction.OpenWeb(shortcut)
                        shortcut in searchable -> VoiceAction.Search(shortcut, query)
                        else -> VoiceAction.Choose(query)
                    }
                }
            }
            if (shortcut in searchable && endsWith(listOf("tren") + title)) {
                return VoiceAction.Search(shortcut, words.dropLast(title.size + 1).joinToString(" "))
            }
        }

        // Google
        val google = listOf("google")
        for (lead in SEARCH_PREFIXES.map { it + google } + listOf(google)) {
            if (startsWith(lead) && norm.size > lead.size) return VoiceAction.Search(null, rest(lead.size))
        }
        if (endsWith(listOf("tren") + google)) return VoiceAction.Search(null, words.dropLast(2).joinToString(" "))

        // "tìm ..." without a site: let the user pick where
        for (prefix in SEARCH_PREFIXES) {
            if (startsWith(prefix) && norm.size > prefix.size) return VoiceAction.Choose(rest(prefix.size))
        }
        return VoiceAction.Choose(text.trim())
    }

    private fun bestMatch(target: String, apps: List<Pair<String, String>>, web: List<WebShortcut>): VoiceAction? {
        var best: VoiceAction? = null
        var bestScore = 0
        fun consider(name: String, action: VoiceAction) {
            val n = normalize(name)
            val compactName = n.replace(" ", "")
            val compactTarget = target.replace(" ", "")
            val score = when {
                n == target || compactName == compactTarget -> 4
                n.startsWith(target) || compactName.startsWith(compactTarget) -> 3
                target.startsWith(n) -> 2
                n.contains(target) || target.contains(n) -> 1
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = action
            }
        }
        web.forEach { consider(it.title, VoiceAction.OpenWeb(it)) }
        apps.forEach { (label, pkg) -> consider(label, VoiceAction.LaunchApp(pkg, label)) }
        return best
    }
}
