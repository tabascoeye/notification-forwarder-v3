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
package org.groebl.sms.feature.main

import androidx.recyclerview.widget.ItemTouchHelper
import org.groebl.sms.R
import org.groebl.sms.common.Navigator
import org.groebl.sms.common.base.QkViewModel
import org.groebl.sms.extensions.mapNotNull
import org.groebl.sms.interactor.DeleteConversations
import org.groebl.sms.interactor.MarkAllSeen
import org.groebl.sms.interactor.MarkArchived
import org.groebl.sms.interactor.MarkPinned
import org.groebl.sms.interactor.MarkRead
import org.groebl.sms.interactor.MarkUnarchived
import org.groebl.sms.interactor.MarkUnpinned
import org.groebl.sms.interactor.MarkUnread
import org.groebl.sms.interactor.MigratePreferences
import org.groebl.sms.interactor.SyncMessages
import org.groebl.sms.listener.ContactAddedListener
import org.groebl.sms.manager.PermissionManager
import org.groebl.sms.manager.RatingManager
import org.groebl.sms.model.SyncLog
import org.groebl.sms.repository.ConversationRepository
import org.groebl.sms.repository.SyncRepository
import org.groebl.sms.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.withLatestFrom
import io.reactivex.schedulers.Schedulers
import io.realm.Realm
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MainViewModel @Inject constructor(
        markAllSeen: MarkAllSeen,
        migratePreferences: MigratePreferences,
        syncRepository: SyncRepository,
    	private val contactAddedListener: ContactAddedListener,
        private val conversationRepo: ConversationRepository,
        private val deleteConversations: DeleteConversations,
        private val markArchived: MarkArchived,
        private val markPinned: MarkPinned,
        private val markRead: MarkRead,
        private val markUnarchived: MarkUnarchived,
        private val markUnpinned: MarkUnpinned,
        private val markUnread: MarkUnread,
        private val navigator: Navigator,
        private val permissionManager: PermissionManager,
        private val prefs: Preferences,
        private val ratingManager: RatingManager,
        private val syncMessages: SyncMessages
) : QkViewModel<MainView, MainState>(MainState(page = Inbox(data = conversationRepo.getConversations()))) {

    init {
        disposables += deleteConversations
        disposables += markAllSeen
        disposables += markArchived
        disposables += markUnarchived
        disposables += migratePreferences
        disposables += syncMessages

        // Show the syncing UI
        disposables += syncRepository.syncProgress
                .sample(16, TimeUnit.MILLISECONDS)
                .distinctUntilChanged()
                .subscribe { syncing -> newState { copy(syncing = syncing) } }

        // Show the rating UI
        disposables += ratingManager.shouldShowRating
                .subscribe { show -> newState { copy(showRating = show) } }


        // Migrate the preferences from 2.7.3
        migratePreferences.execute(Unit)


        // If we have all permissions and we've never run a sync, run a sync. This will be the case
        // when upgrading from 2.7.3, or if the app's data was cleared
        val lastSync = Realm.getDefaultInstance().use { realm -> realm.where(SyncLog::class.java)?.max("date") ?: 0 }
        if (lastSync == 0 && permissionManager.isDefaultSms() && permissionManager.hasReadSms() && permissionManager.hasContacts()) {
            syncMessages.execute(Unit)
        }

        ratingManager.addSession()
        markAllSeen.execute(Unit)
    }

    override fun bindView(view: MainView) {
        super.bindView(view)

        when {
            !permissionManager.isDefaultSms() -> view.requestDefaultSms()
            !permissionManager.hasReadSms() || !permissionManager.hasContacts() -> view.requestPermissions()
        }

        val permissions = view.activityResumedIntent
                .observeOn(Schedulers.io())
                .map { Triple(permissionManager.isDefaultSms(), permissionManager.hasReadSms(), permissionManager.hasContacts()) }
                .distinctUntilChanged()
                .share()

        // If the default SMS state or permission states change, update the ViewState
        permissions
                .doOnNext { (defaultSms, smsPermission, contactPermission) ->
                    newState { copy(defaultSms = defaultSms, smsPermission = smsPermission, contactPermission = contactPermission) }
                }
                .autoDisposable(view.scope())
                .subscribe()

        // If we go from not having all permissions to having them, sync messages
        permissions
                .skip(1)
                .filter { it.first && it.second && it.third }
                .take(1)
                .autoDisposable(view.scope())
                .subscribe { syncMessages.execute(Unit) }

        // Launch screen from intent
        view.onNewIntentIntent
                .autoDisposable(view.scope())
                .subscribe { intent ->
                    when (intent.getStringExtra("screen")) {
                        "blocking" -> navigator.showBlockedConversations()
                    }
                }

        // Show changelog
        //if (changelogManager.didUpdate()) {
        //    view.showChangelog()
        //}

        view.queryChangedIntent
                .debounce(200, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .withLatestFrom(state) { query, state ->
                    if (query.isEmpty() && state.page is Searching) {
                        newState { copy(page = Inbox(data = conversationRepo.getConversations())) }
                    }
                    query
                }
                .filter { query -> query.length >= 2 }
                .doOnNext {
                    newState {
                        val page = (page as? Searching) ?: Searching()
                        copy(page = page.copy(loading = true))
                    }
                }
                .observeOn(Schedulers.io())
                .map(conversationRepo::searchConversations)
                .autoDisposable(view.scope())
                .subscribe { data -> newState { copy(page = Searching(loading = false, data = data)) } }

        view.composeIntent
                .autoDisposable(view.scope())
                .subscribe { navigator.showCompose() }

        view.homeIntent
                .withLatestFrom(state) { _, state ->
                    when {
                        state.page is Searching -> view.clearSearch()
                        state.page is Inbox && state.page.selected > 0 -> view.clearSelection()
                        state.page is Archived && state.page.selected > 0 -> view.clearSelection()

                        else -> newState { copy(drawerOpen = true) }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.drawerOpenIntent
                .autoDisposable(view.scope())
                .subscribe { open -> newState { copy(drawerOpen = open) } }

        view.navigationIntent
                .withLatestFrom(state) { drawerItem, state ->
                    newState { copy(drawerOpen = false) }
                    when (drawerItem) {
                        NavItem.BACK -> when {
                            state.drawerOpen -> Unit
                            state.page is Searching -> view.clearSearch()
                            state.page is Inbox && state.page.selected > 0 -> view.clearSelection()
                            state.page is Archived && state.page.selected > 0 -> view.clearSelection()
                            state.page !is Inbox -> {
                                newState { copy(page = Inbox(data = conversationRepo.getConversations())) }
                            }
                            else -> newState { copy(hasError = true) }
                        }
                        NavItem.BACKUP -> navigator.showBackup()
                        NavItem.SCHEDULED -> navigator.showScheduled()
                        NavItem.BLOCKING -> navigator.showBlockedConversations()
                        NavItem.SETTINGS -> navigator.showSettings()
                        NavItem.SETTINGS_BLUETOOTH -> navigator.showBluetoothSettings()
                        NavItem.HELP -> navigator.showSupport()
                        NavItem.INVITE -> navigator.showInvite()
                        else -> Unit
                    }
                    drawerItem
                }
                .distinctUntilChanged()
                .doOnNext { drawerItem ->
                    when (drawerItem) {
                        NavItem.INBOX -> newState { copy(page = Inbox(data = conversationRepo.getConversations())) }
                        NavItem.ARCHIVED -> newState { copy(page = Archived(data = conversationRepo.getConversations(true))) }
                        else -> Unit
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.archive }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markArchived.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.unarchive }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markUnarchived.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.delete }
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    view.showDeleteDialog(conversations)
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.add }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations -> conversations }
                .doOnNext { view.clearSelection() }
                .filter { conversations -> conversations.size == 1 }
                .map { conversations -> conversations.first() }
                .mapNotNull(conversationRepo::getConversation)
                .map { conversation -> conversation.recipients }
                .mapNotNull { recipients -> recipients[0]?.address?.takeIf { recipients.size == 1 } }
                .doOnNext(navigator::addContact)
                .flatMap(contactAddedListener::listen)
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.pin }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markPinned.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.unpin }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markUnpinned.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.read }
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markRead.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.unread }
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markUnread.execute(conversations)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.block }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    view.showBlockingDialog(conversations, true)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.rateIntent
                .autoDisposable(view.scope())
                .subscribe {
                    navigator.showRating()
                    //ratingManager.rate()
                }

        view.rateDonateIntent
                .autoDisposable(view.scope())
                .subscribe {
                    navigator.showBluetoothDonateScreen()
                }

        view.dismissRatingIntent
                .autoDisposable(view.scope())
                .subscribe { ratingManager.dismiss() }

        view.conversationsSelectedIntent
                .withLatestFrom(state) { selection, state ->
                    val conversations = selection.mapNotNull(conversationRepo::getConversation)
                    val add = conversations.firstOrNull()
                            ?.takeIf { conversations.size == 1 }
                            ?.takeIf { conversation -> conversation.recipients.size == 1 }
                            ?.recipients?.first()
                            ?.takeIf { recipient -> recipient.contact == null } != null
                    val pin = conversations.sumBy { if (it.pinned) -1 else 1 } >= 0
                    val read = conversations.sumBy { if (!it.unread) -1 else 1 } >= 0
                    val selected = selection.size

                    when (state.page) {
                        is Inbox -> {
                            val page = state.page.copy(addContact = add, markPinned = pin, markRead = read, selected = selected)
                            newState { copy(page = page) }
                        }

                        is Archived -> {
                            val page = state.page.copy(addContact = add, markPinned = pin, markRead = read, selected = selected)
                            newState { copy(page = page) }
                        }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        // Delete the conversation
        view.confirmDeleteIntent
                .autoDisposable(view.scope())
                .subscribe { conversations ->
                    deleteConversations.execute(conversations)
                    view.clearSelection()
                }

        view.swipeConversationIntent
                .autoDisposable(view.scope())
                .subscribe { (threadId, direction) ->
                    val action = if (direction == ItemTouchHelper.RIGHT) prefs.swipeRight.get() else prefs.swipeLeft.get()
                    when (action) {
                        Preferences.SWIPE_ACTION_ARCHIVE -> markArchived.execute(listOf(threadId)) { view.showArchivedSnackbar() }
                        Preferences.SWIPE_ACTION_DELETE -> view.showDeleteDialog(listOf(threadId))
                        Preferences.SWIPE_ACTION_CALL -> conversationRepo.getConversation(threadId)?.recipients?.firstOrNull()?.address?.let(navigator::makePhoneCall)
                        Preferences.SWIPE_ACTION_READ -> markRead.execute(listOf(threadId))
                        Preferences.SWIPE_ACTION_UNREAD -> markUnread.execute(listOf(threadId))
                    }
                }

        view.undoArchiveIntent
                .withLatestFrom(view.swipeConversationIntent) { _, pair -> pair.first }
                .autoDisposable(view.scope())
                .subscribe { threadId -> markUnarchived.execute(listOf(threadId)) }

        view.snackbarButtonIntent
                .withLatestFrom(state) { _, state ->
                    when {
                        !state.defaultSms -> view.requestDefaultSms()
                        !state.smsPermission -> view.requestPermissions()
                        !state.contactPermission -> view.requestPermissions()
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()
    }

}