package com.evcockpit.mediahub

/** Builds only documented TMDB/JustWatch paths and the official watch page. */
object TmdbWatchPolicy {
    fun apiPath(kind: TmdbKind, id: Long): String {
        require(id in 1..2_147_483_647L) { "Geçersiz TMDB içerik kimliği" }
        return kind.route + "/" + id + "/watch/providers"
    }

    fun watchPage(kind: TmdbKind, id: Long): String {
        apiPath(kind,id)
        return "https://www.themoviedb.org/" + kind.route +
            "/" + id + "/watch?locale=TR"
    }
}
