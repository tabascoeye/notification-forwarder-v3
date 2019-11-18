/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.groebl.sms.interactor

import org.groebl.sms.manager.NotificationManager
import org.groebl.sms.repository.ConversationRepository
import org.groebl.sms.repository.MessageRepository
import io.reactivex.Flowable
import javax.inject.Inject

class DeleteMessages @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val notificationManager: NotificationManager,
    private val updateBadge: UpdateBadge
) : Interactor<DeleteMessages.Params>() {

    data class Params(val messageIds: List<Long>, val threadId: Long? = null)

    override fun buildObservable(params: Params): Flowable<*> {
        return Flowable.just(params.messageIds.toLongArray())
                .doOnNext { messageIds -> messageRepo.deleteMessages(*messageIds) } // Delete the messages
                .doOnNext {
                    params.threadId?.let { conversationRepo.updateConversations(it) } // Update the conversation
                }
                .doOnNext { params.threadId?.let { notificationManager.update(it) } }
                .flatMap { updateBadge.buildObservable(Unit) } // Update the badge
    }

}