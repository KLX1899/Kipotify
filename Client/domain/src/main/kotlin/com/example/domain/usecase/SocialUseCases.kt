package com.example.domain.usecase

import com.example.domain.model.Friend
import com.example.domain.model.Track
import com.example.domain.repository.SocialRepository

class ObserveFriends(private val repository: SocialRepository) {
    operator fun invoke() = repository.friends
}

class ObserveTypingFriend(private val repository: SocialRepository) {
    operator fun invoke() = repository.typingFriendId
}

class ObserveMessages(private val repository: SocialRepository) {
    operator fun invoke() = repository.observeMessages()
}

class RefreshFriends(private val repository: SocialRepository) {
    suspend operator fun invoke() = repository.refreshFriends()
}

class OpenChat(private val repository: SocialRepository) {
    suspend operator fun invoke(friend: Friend?) = repository.openChat(friend)
}

class ToggleFollow(private val repository: SocialRepository) {
    suspend operator fun invoke(friendId: String) = repository.toggleFollowFriend(friendId)
}

class SendMessage(private val repository: SocialRepository) {
    suspend operator fun invoke(content: String, sharedTrack: Track? = null) =
        repository.sendMessage(content, sharedTrack)
}

data class SocialUseCases(
    val observeFriends: ObserveFriends,
    val observeTypingFriend: ObserveTypingFriend,
    val observeMessages: ObserveMessages,
    val refreshFriends: RefreshFriends,
    val openChat: OpenChat,
    val toggleFollow: ToggleFollow,
    val sendMessage: SendMessage,
) {
    constructor(repository: SocialRepository) : this(
        ObserveFriends(repository),
        ObserveTypingFriend(repository),
        ObserveMessages(repository),
        RefreshFriends(repository),
        OpenChat(repository),
        ToggleFollow(repository),
        SendMessage(repository),
    )
}
