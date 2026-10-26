package app.waveroom.ui

import androidx.compose.runtime.mutableStateListOf

sealed class Route {
    object Home : Route()
    object Room : Route()
    object Create : Route()
    data class Join(val code: String = "") : Route()
    data class Invite(val purpose: String) : Route()
    data class Chat(val uid: String) : Route()
    data class Profile(val uid: String) : Route()
    object People : Route()
    object EditProfile : Route()
    object Settings : Route()
    object Moderation : Route()
    object Help : Route()
    object Legal : Route()
    object About : Route()
    object NewGroup : Route()
    data class GroupInfo(val gid: String) : Route()
}

class Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    val top: Route get() = stack.last()
    var homeTab = 0
    var prevSize = 1
    /** Set when a swipe already animated the screen away, so the normal transition is skipped once. */
    var skipAnim = false

    fun push(r: Route) {
        if (stack.last() == r) return
        stack.add(r)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    fun replaceTop(r: Route) {
        stack[stack.size - 1] = r
    }

    fun popTo(r: Route) {
        while (stack.size > 1 && stack.last() != r) stack.removeAt(stack.size - 1)
    }

    fun resetTo(r: Route) {
        stack.clear()
        stack.add(r)
    }
}
