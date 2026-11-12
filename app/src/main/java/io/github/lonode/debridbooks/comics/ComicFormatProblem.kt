package io.github.lonode.debridbooks.comics

/** Only fixed explanations, never native error text or provider paths. */
internal class ComicFormatProblem(message: String) : IllegalStateException(message)
