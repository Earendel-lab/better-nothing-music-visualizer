package com.better.nothing.music.vizualizer

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class GlyphNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null || sbn.isOngoing) return

        // We only care if the visualizer is actually running
        val service = MainActivity.serviceStatic
        if (service != null && AudioCaptureService.isRunning()) {
            service.triggerNotificationFlash()
        }
    }
}
