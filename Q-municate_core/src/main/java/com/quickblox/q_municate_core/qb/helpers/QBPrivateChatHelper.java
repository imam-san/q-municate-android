package com.quickblox.q_municate_core.qb.helpers;

import android.content.Context;
import android.os.Bundle;

import com.quickblox.chat.QBPrivateChat;
import com.quickblox.chat.listeners.QBPrivateChatManagerListener;
import com.quickblox.chat.model.QBChatMessage;
import com.quickblox.chat.model.QBDialog;
import com.quickblox.chat.model.QBDialogType;
import com.quickblox.content.QBContent;
import com.quickblox.content.model.QBFile;
import com.quickblox.core.exception.QBResponseException;
import com.quickblox.q_municate_core.R;
import com.quickblox.q_municate_core.db.managers.ChatDatabaseManager;
import com.quickblox.q_municate_core.db.managers.UsersDatabaseManager;
import com.quickblox.q_municate_core.models.MessageCache;
import com.quickblox.q_municate_core.models.MessagesNotificationType;
import com.quickblox.q_municate_core.models.User;
import com.quickblox.q_municate_core.service.QBServiceConsts;
import com.quickblox.q_municate_core.utils.ChatNotificationUtils;
import com.quickblox.q_municate_core.utils.ChatUtils;
import com.quickblox.q_municate_core.utils.FindUnknownFriends;
import com.quickblox.users.model.QBUser;

import java.io.File;
import java.util.ArrayList;

public class QBPrivateChatHelper extends QBBaseChatHelper implements QBPrivateChatManagerListener {

    private static final String TAG = QBPrivateChatHelper.class.getSimpleName();

    private QBNotificationChatListener notificationChatListener;

    public QBPrivateChatHelper(Context context) {
        super(context);
        notificationChatListener = new PrivateChatNotificationListener();
        addNotificationChatListener(notificationChatListener);
    }

    public void sendPrivateMessage(String message, int userId) throws QBResponseException {
        sendPrivateMessage(null, message, userId);
    }

    public void sendPrivateMessageWithAttachImage(QBFile file, int userId) throws QBResponseException {
        sendPrivateMessage(file, context.getString(R.string.dlg_attached_last_message), userId);
    }

    private void sendPrivateMessage(QBFile file, String message, int userId) throws QBResponseException {
        QBChatMessage chatMessage;
        chatMessage = getQBChatMessage(message, file);
        String dialogId = null;
        if (currentDialog != null) {
            dialogId = currentDialog.getDialogId();
        }
        sendPrivateMessage(chatMessage, userId, dialogId);
    }

    @Override
    public QBPrivateChat createChatLocally(QBDialog dialog, Bundle additional) throws QBResponseException {
        int opponentId = additional.getInt(QBServiceConsts.EXTRA_OPPONENT_ID);
        QBPrivateChat privateChat = createPrivateChatIfNotExist(opponentId);
        currentDialog = dialog;
        return privateChat;
    }

    @Override
    public void closeChat(QBDialog dialogId, Bundle additional) {
        currentDialog = null;
    }

    public void init(QBUser user) {
        super.init(user);
    }

    @Override
    protected void onPrivateMessageReceived(QBPrivateChat privateChat, QBChatMessage chatMessage) {
        User user = UsersDatabaseManager.getUserById(context, chatMessage.getSenderId());

        if (user == null) {
            user = ChatUtils.getTempUserFromChatMessage(chatMessage);
        }

        MessageCache messageCache = parseReceivedMessage(chatMessage);

        saveMessageToCache(messageCache);

        notifyMessageReceived(chatMessage, user, messageCache.getDialogId(), true);
    }

    @Override
    public void chatCreated(QBPrivateChat privateChat, boolean createdLocally) {
        privateChat.addMessageListener(privateChatMessageListener);
        privateChat.addIsTypingListener(privateChatIsTypingListener);
    }

    public void updateDialog(QBDialog dialog) {
        int countUnreadDialog = ChatDatabaseManager.getCountUnreadMessagesByDialogIdLocal(context,
                dialog.getDialogId());
        ChatDatabaseManager.updateDialog(context, dialog.getDialogId(), dialog.getLastMessage(),
                dialog.getLastMessageDateSent(), dialog.getLastMessageUserId(), countUnreadDialog);
    }

    public QBFile loadAttachFile(File inputFile) throws Exception {
        QBFile file = null;

        try {
            file = QBContent.uploadFileTask(inputFile, true, (String) null);
        } catch (QBResponseException exc) {
            throw new Exception(context.getString(R.string.dlg_fail_upload_attach));
        }

        return file;
    }

    private void friendRequestMessageReceived(QBChatMessage chatMessage, MessagesNotificationType messagesNotificationType) {
        MessageCache messageCache = parseReceivedMessage(chatMessage);
        messageCache.setMessagesNotificationType(messagesNotificationType);

        QBDialog dialog = ChatDatabaseManager.getDialogByDialogId(context, messageCache.getDialogId());

        if (dialog == null) {
            dialog = ChatNotificationUtils.parseDialogFromQBMessage(context, chatMessage, QBDialogType.PRIVATE);
            ArrayList<Integer> occupantsIdsList = ChatUtils.createOccupantsIdsFromPrivateMessage(chatCreator.getId(),
                    chatMessage.getSenderId());
            dialog.setOccupantsIds(occupantsIdsList);
            saveDialogToCache(dialog);
        }

        saveMessageToCache(messageCache);
    }

    private void createDialogByNotification(QBChatMessage chatMessage, MessagesNotificationType messagesNotificationType) {
        MessageCache messageCache = parseReceivedMessage(chatMessage);
        messageCache.setMessagesNotificationType(messagesNotificationType);

        String roomJidId;

        QBDialog dialog = ChatNotificationUtils.parseDialogFromQBMessage(context, chatMessage,
                messageCache.getMessage(), QBDialogType.GROUP);
        dialog.setUnreadMessageCount(1);
        saveDialogToCache(dialog);

        roomJidId = dialog.getRoomJid();
        if (roomJidId != null) {
            tryJoinRoomChat(dialog);
            new FindUnknownFriends(context, chatCreator, dialog).find();
        }
    }

    private class PrivateChatNotificationListener implements QBNotificationChatListener {

        @Override
        public void onReceivedNotification(String notificationType, QBChatMessage chatMessage) {
            if (ChatNotificationUtils.PROPERTY_TYPE_TO_PRIVATE_CHAT__GROUP_CHAT_CREATE.equals(
                    notificationType)) {
                createDialogByNotification(chatMessage, MessagesNotificationType.CREATE_DIALOG);
            } else if (ChatNotificationUtils.PROPERTY_TYPE_TO_PRIVATE_CHAT__FRIENDS_REQUEST.equals(
                    notificationType)) {
                friendRequestMessageReceived(chatMessage, MessagesNotificationType.FRIENDS_REQUEST);
            } else if (ChatNotificationUtils.PROPERTY_TYPE_TO_PRIVATE_CHAT__FRIENDS_ACCEPT.equals(
                    notificationType)) {
                friendRequestMessageReceived(chatMessage, MessagesNotificationType.FRIENDS_ACCEPT);
            } else if (ChatNotificationUtils.PROPERTY_TYPE_TO_PRIVATE_CHAT__FRIENDS_REJECT.equals(
                    notificationType)) {
                friendRequestMessageReceived(chatMessage, MessagesNotificationType.FRIENDS_REJECT);
            } else if (ChatNotificationUtils.PROPERTY_TYPE_TO_PRIVATE_CHAT__FRIENDS_REMOVE.equals(
                    notificationType)) {
                friendRequestMessageReceived(chatMessage, MessagesNotificationType.FRIENDS_REMOVE);
            }
        }
    }
}