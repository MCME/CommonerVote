package com.mcmiddleearth.commonervote.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mcmiddleearth.commonervote.network.Messenger;

import net.kyori.adventure.text.Component;

/**
 * A {@link Messenger} that sends nothing and remembers what it was asked to send, in order. A test reads
 * {@link #notifications()} and {@link #announcements()} back.
 */
public final class RecordingMessenger implements Messenger {

    /** One call of {@link #notifyRecipient}. */
    public record Notification(String recipientName, Update kind) {
    }

    private final List<Notification> notifications = new CopyOnWriteArrayList<>();
    private final List<Component> announcements = new CopyOnWriteArrayList<>();

    @Override
    public void notifyRecipient(String recipientName, Update kind) {
        notifications.add(new Notification(recipientName, kind));
    }

    @Override
    public void announce(Component message) {
        announcements.add(message);
    }

    /** The recipients notified so far, oldest first. */
    public List<Notification> notifications() {
        return List.copyOf(notifications);
    }

    /** The messages announced so far, oldest first. */
    public List<Component> announcements() {
        return List.copyOf(announcements);
    }

    /** Forgets everything recorded so far. */
    public void clear() {
        notifications.clear();
        announcements.clear();
    }
}
