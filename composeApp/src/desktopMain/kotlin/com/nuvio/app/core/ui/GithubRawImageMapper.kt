package com.nuvio.app.core.ui

import coil3.map.Mapper
import coil3.request.Options

/**
 * Points a GitHub web link to a file at the file itself before Coil fetches it.
 *
 * Community collection packs link artwork as `github.com/<owner>/<repo>/blob/<ref>/<path>?raw=true`
 * (or `/raw/`), which is what GitHub's "copy link" gives you. That URL is an HTML page that only
 * reaches the image through two redirects on github.com, and github.com throttles anonymous
 * traffic far harder than raw.githubusercontent.com does: with the artwork prewarm touching every
 * collection folder, those loads failed outright and the folder banner and hero stayed black (the
 * Netflix and Apple TV+ folders of the Kaptain mega pack, 2026-10-24), while the same pack's
 * `raw.githubusercontent.com` links loaded fine.
 */
internal object GithubRawImageMapper : Mapper<String, String> {
    override fun map(data: String, options: Options): String? = githubRawImageUrl(data)
}

/** The `raw.githubusercontent.com` URL for a github.com blob/raw file link, or null if [url] is not one. */
internal fun githubRawImageUrl(url: String): String? {
    val match = GithubFileLink.matchEntire(url) ?: return null
    val (owner, repo, kind, rest) = match.destructured
    // `blob` pages only serve the file with `?raw=true`; without it they are the HTML viewer.
    if (kind == "blob" && !match.groupValues[5].contains("raw=true")) return null
    return "https://raw.githubusercontent.com/$owner/$repo/$rest"
}

private val GithubFileLink =
    Regex("""https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/(blob|raw)/([^?#]+)(\?[^#]*)?(#.*)?""")
