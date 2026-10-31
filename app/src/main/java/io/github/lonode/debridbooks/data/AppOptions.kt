package io.github.lonode.debridbooks.data

import kotlinx.serialization.Serializable

@Serializable data class AppOptions(
    val autoDeleteFinished: Boolean = false, val smartRewind: Boolean = true, val sleepFade: Boolean = true, val bedtimeTimer: Boolean = false,
    val bedtimeStart: Int = 22, val bedtimeMinutes: Int = 30, val dailyGoal: Int = 0,
    val accent: String = "violet", val librarySort: String = "recent", val libraryGrid: Boolean = false,
    val collapsed: Set<String> = emptySet(), val instantOnly: Boolean = false, val groupUploads: Boolean = false,
    val remainingTime: Boolean = false, val chapterScrub: Boolean = true, val autoResume: Boolean = false,
    val notifyReady: Boolean = false, val notifyReleases: Boolean = false, val whatsNewSeen: String = "",
) {
    fun validate() {
        require(bedtimeStart in 0..23 && bedtimeMinutes in 5..120 && dailyGoal in 0..240)
        require(accent in setOf("violet", "blue", "teal", "orange", "pink") && librarySort in setOf("recent", "title", "author"))
        require(collapsed.all { it in setOf("continue", "saved", "downloaded", "finished", "other") })
    }
}
