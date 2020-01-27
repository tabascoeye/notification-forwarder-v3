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
package org.groebl.sms.feature.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsMessage
import android.view.inputmethod.EditorInfo

import org.groebl.sms.R
import org.groebl.sms.common.Navigator
import org.groebl.sms.common.base.QkViewModel
import org.groebl.sms.common.util.ClipboardUtils
import org.groebl.sms.common.util.MessageDetailsFormatter
import org.groebl.sms.common.util.extensions.makeToast
import org.groebl.sms.compat.SubscriptionManagerCompat
import org.groebl.sms.compat.TelephonyCompat
import org.groebl.sms.extensions.*
import org.groebl.sms.feature.compose.editing.ComposeItem
import org.groebl.sms.feature.compose.editing.ComposeItem.*
import org.groebl.sms.filter.ContactFilter
import org.groebl.sms.filter.ContactGroupFilter
import org.groebl.sms.interactor.*
import org.groebl.sms.manager.ActiveConversationManager
import org.groebl.sms.manager.PermissionManager
import org.groebl.sms.model.*
import org.groebl.sms.repository.ContactRepository
import org.groebl.sms.repository.ConversationRepository
import org.groebl.sms.repository.MessageRepository
import org.groebl.sms.util.ActiveSubscriptionObservable
import org.groebl.sms.util.PhoneNumberUtils
import org.groebl.sms.util.Preferences
import org.groebl.sms.util.tryOrNull
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.withLatestFrom
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import io.realm.RealmList
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Named

class ComposeViewModel @Inject constructor(
        @Named("query") private val query: String,
        @Named("threadId") private val threadId: Long,
        @Named("address") private val address: String,
        @Named("text") private val sharedText: String,
        @Named("attachments") private val sharedAttachments: Attachments,
        private val context: Context,
        private val activeConversationManager: ActiveConversationManager,
        private val addScheduledMessage: AddScheduledMessage,
        private val cancelMessage: CancelDelayedMessage,
        private val contactFilter: ContactFilter,
        private val contactGroupFilter: ContactGroupFilter,
        private val contactsRepo: ContactRepository,
        private val conversationRepo: ConversationRepository,
        private val deleteMessages: DeleteMessages,
        private val markRead: MarkRead,
        private val messageDetailsFormatter: MessageDetailsFormatter,
        private val messageRepo: MessageRepository,
        private val navigator: Navigator,
        private val permissionManager: PermissionManager,
        private val phoneNumberUtils: PhoneNumberUtils,
        private val prefs: Preferences,
        private val retrySending: RetrySending,
        private val sendMessage: SendMessage,
        private val subscriptionManager: SubscriptionManagerCompat,
        private val syncContacts: ContactSync
) : QkViewModel<ComposeView, ComposeState>(ComposeState(
        editingMode = threadId == 0L && address.isBlank(),
        selectedConversation = threadId,
        query = query)
) {

    private val attachments: Subject<List<Attachment>> = BehaviorSubject.createDefault(sharedAttachments)
    private val contactGroups: Observable<List<ContactGroup>> by lazy { contactsRepo.getUnmanagedContactGroups() }
    private val contacts: Observable<List<Contact>> by lazy { contactsRepo.getUnmanagedContacts() }
    private val contactsReducer: Subject<(List<Contact>) -> List<Contact>> = PublishSubject.create()
    private val conversation: Subject<Conversation> = BehaviorSubject.create()
    private val messages: Subject<List<Message>> = BehaviorSubject.create()
    private val recents: Observable<List<Conversation>> by lazy { conversationRepo.getUnmanagedConversations() }
    private val selectedContacts: Subject<List<Contact>> = BehaviorSubject.createDefault(listOf())
    private val searchResults: Subject<List<Message>> = BehaviorSubject.create()
    private val searchSelection: Subject<Long> = BehaviorSubject.createDefault(-1)
    private val starredContacts: Observable<List<Contact>> by lazy { contactsRepo.getUnmanagedContacts(true) }

    init {
        val initialConversation = threadId.takeIf { it != 0L }
                ?.let(conversationRepo::getConversationAsync)
                ?.asObservable()
                ?: Observable.empty()

        val selectedConversation = selectedContacts
                .skipWhile { it.isEmpty() }
                .map { contacts -> contacts.map { it.numbers.firstOrNull()?.address ?: "" } }
                .distinctUntilChanged()
                .doOnNext { newState { copy(loading = true) } }
                .observeOn(Schedulers.io())
                .map { addresses -> Pair(conversationRepo.getOrCreateConversation(addresses)?.id ?: 0, addresses) }
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext { newState { copy(loading = false) } }
                .switchMap { (threadId, addresses) ->
                    // If we already have this thread in realm, or we're able to obtain it from the
                    // system, just return that.
                    threadId.takeIf { it > 0 }?.let {
                        return@switchMap conversationRepo.getConversationAsync(threadId).asObservable()
                    }

                    // Otherwise, we'll monitor the conversations until our expected conversation is created
                    conversationRepo.getConversations().asObservable()
                            .filter { it.isLoaded }
                            .observeOn(Schedulers.io())
                            .map { conversationRepo.getOrCreateConversation(addresses)?.id ?: 0 }
                            .observeOn(AndroidSchedulers.mainThread())
                            .switchMap { actualThreadId ->
                                when (actualThreadId) {
                                    0L -> Observable.just(Conversation(0))
                                    else -> conversationRepo.getConversationAsync(actualThreadId).asObservable()
                                }
                            }
                }

        // Merges two potential conversation sources (threadId from constructor and contact selection) into a single
        // stream of conversations. If the conversation was deleted, notify the activity to shut down
        disposables += selectedConversation
                .mergeWith(initialConversation)
                .filter { conversation -> conversation.isLoaded }
                .doOnNext { conversation ->
                    if (!conversation.isValid) {
                        newState { copy(hasError = true) }
                    }
                }
                .filter { conversation -> conversation.isValid }
                .subscribe(conversation::onNext)

        if (address.isNotBlank()) {
            selectedContacts.onNext(listOf(Contact(numbers = RealmList(PhoneNumber(address)))))
        }

        disposables += contactsReducer
                .scan(listOf<Contact>()) { previousState, reducer -> reducer(previousState) }
                .doOnNext { contacts -> newState { copy(selectedContacts = contacts) } }
                .skipUntil(state.filter { state -> state.editingMode })
                .takeUntil(state.filter { state -> !state.editingMode })
                .subscribe(selectedContacts::onNext)

        // When the conversation changes, mark read, and update the threadId and the messages for the adapter
        disposables += conversation
                .distinctUntilChanged { conversation -> conversation.id }
                .observeOn(AndroidSchedulers.mainThread())
                .map { conversation ->
                    val messages = messageRepo.getMessages(conversation.id)
                    newState { copy(selectedConversation = conversation.id, messages = Pair(conversation, messages)) }
                    messages
                }
                .switchMap { messages -> messages.asObservable() }
                .subscribe(messages::onNext)

        disposables += conversation
                .map { conversation -> conversation.getTitle() }
                .distinctUntilChanged()
                .subscribe { title -> newState { copy(conversationtitle = title) } }

        disposables += attachments
                .subscribe { attachments -> newState { copy(attachments = attachments) } }

        disposables += conversation
                .map { conversation -> conversation.id }
                .distinctUntilChanged()
                .withLatestFrom(state) { id, state -> messageRepo.getMessages(id, state.query) }
                .switchMap { messages -> messages.asObservable() }
                .takeUntil(state.map { it.query }.filter { it.isEmpty() })
                .filter { messages -> messages.isLoaded }
                .filter { messages -> messages.isValid }
                .subscribe(searchResults::onNext)

        disposables += Observables.combineLatest(searchSelection, searchResults) { selected, messages ->
            if (selected == -1L) {
                messages.lastOrNull()?.let { message -> searchSelection.onNext(message.id) }
            } else {
                val position = messages.indexOfFirst { it.id == selected } + 1
                newState { copy(searchSelectionPosition = position, searchResults = messages.size) }
            }
        }.subscribe()

        val latestSubId = messages
                .map { messages -> messages.lastOrNull()?.subId ?: -1 }
                .distinctUntilChanged()

        val subscriptions = ActiveSubscriptionObservable(subscriptionManager)
        disposables += Observables.combineLatest(latestSubId, subscriptions) { subId, subs ->
            val sub = if (subs.size > 1) subs.firstOrNull { it.subscriptionId == subId } ?: subs[0] else null
            newState { copy(subscription = sub) }
        }.subscribe()

        if (threadId == 0L) {
            syncContacts.execute(Unit)
        }
    }

    override fun bindView(view: ComposeView) {
        super.bindView(view)

        // Set the contact suggestions list to visible at all times when in editing mode and there are no contacts
        // selected yet, and also visible while in editing mode and there is text entered in the query field
        Observables
                .combineLatest(view.queryChangedIntent, selectedContacts) { query, selectedContacts ->
                    selectedContacts.isEmpty() || query.isNotEmpty()
                }
                .skipUntil(state.filter { state -> state.editingMode })
                .takeUntil(state.filter { state -> !state.editingMode })
                .distinctUntilChanged()
                .autoDisposable(view.scope())
                .subscribe { contactsVisible -> newState { copy(contactsVisible = contactsVisible && editingMode) } }

        // Update the list of contact suggestions based on the query input, while also filtering out any contacts
        // that have already been selected
        Observables
                .combineLatest(
                        view.queryChangedIntent, recents, starredContacts, contactGroups, contacts, selectedContacts
                ) { query, recents, starredContacts, contactGroups, contacts, selectedContacts ->
                    val composeItems = mutableListOf<ComposeItem>()
                    if (query.isBlank()) {
                        composeItems += recents.map(::Recent)
                        composeItems += starredContacts.map(::Starred)
                        composeItems += contactGroups.map(::Group)
                        composeItems += contacts.map(::Person)
                    } else {
                        // If the entry is a valid destination, allow it as a recipient
                        if (phoneNumberUtils.isPossibleNumber(query.toString())) {
                            val newAddress = phoneNumberUtils.formatNumber(query)
                            val newContact = Contact(numbers = RealmList(PhoneNumber(address = newAddress)))
                            composeItems += New(newContact)
                        }

                        // Strip the accents from the query. This can be an expensive operation, so
                        // cache the result instead of doing it for each contact
                        val normalizedQuery = query.removeAccents()
                        composeItems += starredContacts
                                .filterNot { contact -> selectedContacts.contains(contact) }
                                .filter { contact -> contactFilter.filter(contact, normalizedQuery) }
                                .map(::Starred)

                        composeItems += contactGroups
                                .filter { group -> contactGroupFilter.filter(group, normalizedQuery) }
                                .map(::Group)

                        composeItems += contacts
                                .filterNot { contact -> selectedContacts.contains(contact) }
                                .filter { contact -> contactFilter.filter(contact, normalizedQuery) }
                                .map(::Person)
                    }

                    composeItems
                }
                .skipUntil(state.filter { state -> state.editingMode })
                .takeUntil(state.filter { state -> !state.editingMode })
                .subscribeOn(Schedulers.computation())
                .autoDisposable(view.scope())
                .subscribe { items -> newState { copy(composeItems = items) } }

        // Backspaces should delete the most recent contact if there's no text input
        // Close the activity if user presses back
        view.queryBackspaceIntent
                .withLatestFrom(selectedContacts, view.queryChangedIntent) { event, contacts, query ->
                    if (contacts.isNotEmpty() && query.isEmpty()) {
                        contactsReducer.onNext { it.dropLast(1) }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        // Enter the first contact suggestion if the enter button is pressed
        view.queryEditorActionIntent
                .filter { actionId -> actionId == EditorInfo.IME_ACTION_DONE }
                .withLatestFrom(state) { _, state -> state }
                .autoDisposable(view.scope())
                .subscribe { state ->
                    state.composeItems.firstOrNull()?.let { composeItem ->
                        contactsReducer.onNext { contacts -> contacts + composeItem.getContacts() }
                    }
                }

        // Update the list of selected contacts when a new contact is selected or an existing one is deselected
        Observable.merge(
                view.chipDeletedIntent.doOnNext { contact ->
                    contactsReducer.onNext { contacts -> contacts.filterNot { it == contact } }
                },
                view.chipSelectedIntent.doOnNext { composeItem ->
                    contactsReducer.onNext { contacts ->
                        contacts.toMutableList().apply { addAll(composeItem.getContacts()) }
                    }
                })
                .skipUntil(state.filter { state -> state.editingMode })
                .takeUntil(state.filter { state -> !state.editingMode })
                .autoDisposable(view.scope())
                .subscribe()

        // When the menu is loaded, trigger a new state so that the menu options can be rendered correctly
        view.menuReadyIntent
                .autoDisposable(view.scope())
                .subscribe { newState { copy() } }

        // Open the phone dialer if the call button is clicked
        view.optionsItemIntent
                .filter { it == R.id.call }
                .withLatestFrom(conversation) { _, conversation -> conversation }
                .mapNotNull { conversation -> conversation.recipients.firstOrNull() }
                .map { recipient -> recipient.address }
                .autoDisposable(view.scope())
                .subscribe { address -> navigator.makePhoneCall(address) }

        // Open the conversation settings if info button is clicked
        view.optionsItemIntent
                .filter { it == R.id.info }
                .withLatestFrom(conversation) { _, conversation -> conversation }
                .autoDisposable(view.scope())
                .subscribe { conversation -> navigator.showConversationInfo(conversation.id) }

        // Copy the message contents
        view.optionsItemIntent
                .filter { it == R.id.copy }
                .withLatestFrom(view.messagesSelectedIntent) { _, messages ->
                    messages?.firstOrNull()?.let { messageRepo.getMessage(it) }?.let { message ->
                        ClipboardUtils.copy(context, message.getText())
                        context.makeToast(R.string.toast_copied)
                    }
                }
                .autoDisposable(view.scope())
                .subscribe { view.clearSelection() }

        // Show the message details
        view.optionsItemIntent
                .filter { it == R.id.details }
                .withLatestFrom(view.messagesSelectedIntent) { _, messages -> messages }
                .mapNotNull { messages -> messages.firstOrNull().also { view.clearSelection() } }
                .mapNotNull(messageRepo::getMessage)
                .map(messageDetailsFormatter::format)
                .autoDisposable(view.scope())
                .subscribe { view.showDetails(it) }

        // Delete the messages
        view.optionsItemIntent
                .filter { it == R.id.delete }
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .withLatestFrom(view.messagesSelectedIntent, conversation) { _, messages, conversation ->
                    deleteMessages.execute(DeleteMessages.Params(messages, conversation.id))
                }
                .autoDisposable(view.scope())
                .subscribe { view.clearSelection() }

        // Forward the message
        view.optionsItemIntent
                .filter { it == R.id.forward }
                .withLatestFrom(view.messagesSelectedIntent) { _, messages ->
                    messages?.firstOrNull()?.let { messageRepo.getMessage(it) }?.let { message ->
                        val images = message.parts.filter { it.isImage() }.mapNotNull { it.getUri() }
                        navigator.showCompose(message.getText(), images)
                    }
                }
                .autoDisposable(view.scope())
                .subscribe { view.clearSelection() }

        // Share the message contents
        view.optionsItemIntent
                .filter { it == R.id.share }
                .withLatestFrom(view.messagesSelectedIntent) { _, messages ->
                    messages?.firstOrNull()?.let { messageRepo.getMessage(it) }?.let { message ->

                        val intent = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, message.getText())
                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.compose_menu_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                .autoDisposable(view.scope())
                .subscribe { view.clearSelection() }

        // Show the previous search result
        view.optionsItemIntent
                .filter { it == R.id.previous }
                .withLatestFrom(searchSelection, searchResults) { _, selection, messages ->
                    val currentPosition = messages.indexOfFirst { it.id == selection }
                    if (currentPosition <= 0L) messages.lastOrNull()?.id ?: -1
                    else messages.getOrNull(currentPosition - 1)?.id ?: -1
                }
                .filter { id -> id != -1L }
                .autoDisposable(view.scope())
                .subscribe(searchSelection)

        // Show the next search result
        view.optionsItemIntent
                .filter { it == R.id.next }
                .withLatestFrom(searchSelection, searchResults) { _, selection, messages ->
                    val currentPosition = messages.indexOfFirst { it.id == selection }
                    if (currentPosition >= messages.size - 1) messages.firstOrNull()?.id ?: -1
                    else messages.getOrNull(currentPosition + 1)?.id ?: -1
                }
                .filter { id -> id != -1L }
                .autoDisposable(view.scope())
                .subscribe(searchSelection)

        // Clear the search
        view.optionsItemIntent
                .filter { it == R.id.clear }
                .autoDisposable(view.scope())
                .subscribe { newState { copy(query = "", searchSelectionId = -1) } }

        // Toggle the group sending mode
        view.sendAsGroupIntent
                .autoDisposable(view.scope())
                .subscribe { newState { copy(sendAsGroup = !sendAsGroup) } }

        // Scroll to search position
        searchSelection
                .filter { id -> id != -1L }
                .doOnNext { id -> newState { copy(searchSelectionId = id) } }
                .autoDisposable(view.scope())
                .subscribe(view::scrollToMessage)

        // Retry sending
        view.messageClickIntent
                .mapNotNull(messageRepo::getMessage)
                .filter { message -> message.isFailedMessage() }
                .doOnNext { message -> retrySending.execute(message.id) }
                .autoDisposable(view.scope())
                .subscribe()

        // Media attachment clicks
        view.messagePartClickIntent
                .mapNotNull(messageRepo::getPart)
                .filter { part -> part.isImage() || part.isVideo() }
                .autoDisposable(view.scope())
                .subscribe { part -> navigator.showMedia(part.id) }

        // Non-media attachment clicks
        view.messagePartClickIntent
                .mapNotNull(messageRepo::getPart)
                .filter { part -> !part.isImage() && !part.isVideo() }
                .autoDisposable(view.scope())
                .subscribe { part ->
                    if (permissionManager.hasStorage()) {
                        messageRepo.savePart(part.id)?.let(navigator::viewFile)
                    } else {
                        view.requestStoragePermission()
                    }
                }

        // Update the State when the message selected count changes
        view.messagesSelectedIntent
                .map { selection -> selection.size }
                .autoDisposable(view.scope())
                .subscribe { messages -> newState { copy(selectedMessages = messages, editingMode = false) } }

        // Cancel sending a message
        view.cancelSendingIntent
                .mapNotNull(messageRepo::getMessage)
                .doOnNext { message -> view.setDraft(message.getText()) }
                .autoDisposable(view.scope())
                .subscribe { message -> cancelMessage.execute(message.id) }

        // Set the current conversation
        Observables.combineLatest(
                view.activityVisibleIntent.distinctUntilChanged(),
                conversation.mapNotNull { conversation ->
                    conversation.takeIf { it.isValid }?.id
                }.distinctUntilChanged())
        { visible, threadId ->
            when (visible) {
                true -> {
                    activeConversationManager.setActiveConversation(threadId)
                    markRead.execute(listOf(threadId))
                }

                false -> activeConversationManager.setActiveConversation(null)
            }
        }
                .autoDisposable(view.scope())
                .subscribe()

        // Save draft when the activity goes into the background
        view.activityVisibleIntent
                .filter { visible -> !visible }
                .withLatestFrom(conversation) { _, conversation -> conversation }
                .mapNotNull { conversation -> conversation.takeIf { it.isValid }?.id }
                .observeOn(Schedulers.io())
                .withLatestFrom(view.textChangedIntent) { threadId, draft ->
                    conversationRepo.saveDraft(threadId, draft.toString())
                }
                .autoDisposable(view.scope())
                .subscribe()

        // Open the attachment options
        view.attachIntent
                .autoDisposable(view.scope())
                .subscribe { newState { copy(attaching = !attaching) } }

        // Attach a photo from camera
        view.cameraIntent
                .autoDisposable(view.scope())
                .subscribe {
                    if (permissionManager.hasStorage()) {
                        newState { copy(attaching = false) }
                        view.requestCamera()
                    } else {
                        view.requestStoragePermission()
                    }
                }

        // Attach a photo from gallery
        view.galleryIntent
                .doOnNext { newState { copy(attaching = false) } }
                .autoDisposable(view.scope())
                .subscribe { view.requestGallery() }

        // Choose a time to schedule the message
        view.scheduleIntent
                .doOnNext { newState { copy(attaching = false) } }
                .autoDisposable(view.scope())
                .subscribe { view.requestDatePicker() }

        // A photo was selected
        Observable.merge(
                view.attachmentSelectedIntent.map { uri -> Attachment.Image(uri) },
                view.inputContentIntent.map { inputContent -> Attachment.Image(inputContent = inputContent) })
                .withLatestFrom(attachments) { attachment, attachments -> attachments + attachment }
                .doOnNext { attachments.onNext(it) }
                .autoDisposable(view.scope())
                .subscribe { newState { copy(attaching = false) } }

        // Set the scheduled time
        view.scheduleSelectedIntent
                .filter { scheduled ->
                    (scheduled > System.currentTimeMillis()).also { future ->
                        if (!future) context.makeToast(R.string.compose_scheduled_future)
                    }
                }
                .autoDisposable(view.scope())
                .subscribe { scheduled -> newState { copy(scheduled = scheduled) } }

        // Attach a contact
        view.attachContactIntent
                .doOnNext { newState { copy(attaching = false) } }
                .autoDisposable(view.scope())
                .subscribe { view.requestContact() }

        // Contact was selected for attachment
        view.contactSelectedIntent
                .map { uri -> Attachment.Contact(getVCard(uri)!!) }
                .withLatestFrom(attachments) { attachment, attachments -> attachments + attachment }
                .subscribeOn(Schedulers.io())
                .autoDisposable(view.scope())
                .subscribe(attachments::onNext) { error ->
                    context.makeToast(R.string.compose_contact_error)
                    Timber.w(error)
                }

        // Detach a photo
        view.attachmentDeletedIntent
                .withLatestFrom(attachments) { bitmap, attachments -> attachments.filter { it !== bitmap } }
                .autoDisposable(view.scope())
                .subscribe { attachments.onNext(it) }

        conversation
                .map { conversation -> conversation.draft }
                .distinctUntilChanged()
                .autoDisposable(view.scope())
                .subscribe { draft ->

                    // If text was shared into the conversation, it should take priority over the
                    // existing draft
                    //
                    // TODO: Show dialog warning user about overwriting draft
                    if (sharedText.isNotBlank()) {
                        view.setDraft(sharedText)
                    } else {
                        view.setDraft(draft)
                    }
                }

        // Enable the send button when there is text input into the new message body or there's
        // an attachment, disable otherwise
        Observables
                .combineLatest(view.textChangedIntent, attachments) { text, attachments ->
                    text.isNotBlank() || attachments.isNotEmpty()
                }
                .autoDisposable(view.scope())
                .subscribe { canSend -> newState { copy(canSend = canSend) } }

        // Show the remaining character counter when necessary
        view.textChangedIntent
                .observeOn(Schedulers.computation())
                .mapNotNull { draft -> tryOrNull { SmsMessage.calculateLength(draft, prefs.unicode.get()) } }
                .map { array ->
                    val messages = array[0]
                    val remaining = array[2]

                    when {
                        messages <= 1 && remaining > 10 -> ""
                        messages <= 1 && remaining <= 10 -> "$remaining"
                        else -> "$remaining / $messages"
                    }
                }
                .distinctUntilChanged()
                .autoDisposable(view.scope())
                .subscribe { remaining -> newState { copy(remaining = remaining) } }

        // Cancel the scheduled time
        view.scheduleCancelIntent
                .autoDisposable(view.scope())
                .subscribe { newState { copy(scheduled = 0) } }

        // Toggle to the next sim slot
        view.changeSimIntent
                .withLatestFrom(state) { _, state ->
                    val subs = subscriptionManager.activeSubscriptionInfoList
                    val subIndex = subs.indexOfFirst { it.subscriptionId == state.subscription?.subscriptionId }
                    val subscription = when {
                        subIndex == -1 -> null
                        subIndex < subs.size - 1 -> subs[subIndex + 1]
                        else -> subs[0]
                    }
                    newState { copy(subscription = subscription) }
                }
                .autoDisposable(view.scope())
                .subscribe()

        // Send a message when the send button is clicked, and disable editing mode if it's enabled
        view.sendIntent
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .filter { permissionManager.hasSendSms().also { if (!it) view.requestSmsPermission() } }
                .withLatestFrom(view.textChangedIntent) { _, body -> body }
                .map { body -> body.toString() }
                .withLatestFrom(state, attachments, conversation, selectedContacts) { body, state, attachments,
                                                                                      conversation, contacts ->
                    val subId = state.subscription?.subscriptionId ?: -1
                    val addresses = when (conversation.recipients.isNotEmpty()) {
                        true -> conversation.recipients.map { it.address }
                        false -> contacts.mapNotNull { it.numbers.firstOrNull()?.address }
                    }
                    val delay = when (prefs.sendDelay.get()) {
                        Preferences.SEND_DELAY_SHORT -> 3000
                        Preferences.SEND_DELAY_MEDIUM -> 5000
                        Preferences.SEND_DELAY_LONG -> 10000
                        else -> 0
                    }

                    when {
                        // Scheduling a message
                        state.scheduled != 0L -> {
                            newState { copy(scheduled = 0) }
                            val uris = attachments
                                    .mapNotNull { it as? Attachment.Image }
                                    .map { it.getUri() }
                                    .map { it.toString() }
                            val params = AddScheduledMessage
                                    .Params(state.scheduled, subId, addresses, state.sendAsGroup, body, uris)
                            addScheduledMessage.execute(params)
                            context.makeToast(R.string.compose_scheduled_toast)
                        }

                        // Sending a group message
                        state.sendAsGroup -> {
                            sendMessage.execute(SendMessage
                                    .Params(subId, conversation.id, addresses, body, attachments, delay))
                        }

                        // Sending a message to an existing conversation with one recipient
                        conversation.recipients.size == 1 -> {
                            val address = conversation.recipients.map { it.address }
                            sendMessage.execute(SendMessage.Params(subId, threadId, address, body, attachments, delay))
                        }

                        // Create a new conversation with one address
                        addresses.size == 1 -> {
                            sendMessage.execute(SendMessage
                                    .Params(subId, threadId, addresses, body, attachments, delay))
                        }

                        // Send a message to multiple addresses
                        else -> {
                            addresses.forEach { addr ->
                                val threadId = tryOrNull(false) {
                                    TelephonyCompat.getOrCreateThreadId(context, addr)
                                } ?: 0
                                val address = listOf(conversationRepo
                                        .getConversation(threadId)?.recipients?.firstOrNull()?.address ?: addr)
                                sendMessage.execute(SendMessage
                                        .Params(subId, threadId, address, body, attachments, delay))
                            }
                        }
                    }

                    view.setDraft("")
                    this.attachments.onNext(ArrayList())

                    if (state.editingMode) {
                        newState { copy(editingMode = false, sendAsGroup = true, hasError = !state.sendAsGroup) }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        // Navigate back
        view.optionsItemIntent
                .filter { it == android.R.id.home }
                .map { Unit }
                .mergeWith(view.backPressedIntent)
                .withLatestFrom(state) { _, state ->
                    when {
                        state.selectedMessages > 0 -> view.clearSelection()
                        else -> newState { copy(hasError = true) }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

    }

    private fun getVCard(contactData: Uri): String? {
        val lookupKey = context.contentResolver.query(contactData, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY))
        }

        val vCardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
        return context.contentResolver.openAssetFileDescriptor(vCardUri, "r")
                ?.createInputStream()
                ?.readBytes()
                ?.let { bytes -> String(bytes) }
    }

}