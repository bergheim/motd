package io.github.trevarj.motd.ui.components

/** Names a conversation outside its own screen: `#channel · Network`. */
internal fun conversationTag(
    bufferDisplayName: String,
    networkName: String,
    showNetwork: Boolean = true,
): String = if (showNetwork) "$bufferDisplayName · $networkName" else bufferDisplayName
