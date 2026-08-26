package com.lagradost.quicknovel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.session.MediaButtonReceiver
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.ui.UiText

object TTSNotifications {
    private const val TTS_CHANNEL_ID = "QuickNovelTTS"
    const val TTS_NOTIFICATION_ID = 133742

    private var hasCreateedNotificationChannel = false

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.text_to_speech)
            val descriptionText = context.getString(R.string.text_to_speech_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(
                TTS_CHANNEL_ID,
                name,
                importance
            ).apply {
                description = descriptionText
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    var mediaSession: MediaSessionCompat? = null

    fun setMediaSession(viewModel: ReadActivityViewModel, book: AbstractBook, context: Context) {
        val mbrIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            context,
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        )

        mediaSession = MediaSessionCompat(
            context,
            "TTS",
            ComponentName(context, MediaButtonReceiver::class.java),
            mbrIntent
        ).apply {
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        viewModel.playTTS()
                    }

                    override fun onPause() {
                        viewModel.pauseTTS()
                    }

                    override fun onStop() {
                        viewModel.stopTTS()
                    }

                    override fun onFastForward() {
                        viewModel.forwardsTTS()
                    }

                    override fun onRewind() {
                        viewModel.backwardsTTS()
                    }

                    override fun onSkipToNext() {
                        viewModel.forwardsTTS()
                    }

                    override fun onSkipToPrevious() {
                        viewModel.backwardsTTS()
                    }

                    override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                        viewModel.playTTS()
                    }

                    override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                        val keyEvent =
                            mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as KeyEvent?
                        if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                    viewModel.pausePlayTTS()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                    viewModel.pauseTTS()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                    viewModel.playTTS()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    viewModel.stopTTS()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                    viewModel.forwardsTTS()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                    viewModel.backwardsTTS()
                                    return true
                                }
                            }
                        }
                        return super.onMediaButtonEvent(mediaButtonEvent)
                    }
                }
            )

            val mediaMetadata = MediaMetadataCompat.Builder()
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L).apply {
                    book.poster()?.let { icon ->
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, icon)
                    }
                    book.author()?.let { author ->
                        putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, author)
                    }
                    putString(MediaMetadataCompat.METADATA_KEY_TITLE, book.title())
                }
                .build()
            setMetadata(mediaMetadata)
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
    }

    fun releaseMediaSession() {
        mediaSession?.release()
        mediaSession = null
    }

    private fun updatePlaybackState(status: TTSHelper.TTSStatus) {
        val session = mediaSession ?: return
        val state = when (status) {
            TTSHelper.TTSStatus.IsRunning -> PlaybackStateCompat.STATE_PLAYING
            TTSHelper.TTSStatus.IsPaused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        val builder = PlaybackStateCompat.Builder()
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND
            )
        session.setPlaybackState(builder.build())
    }

    fun createNotification(
        title: String,
        chapter: UiText,
        icon: Bitmap?,
        status: TTSHelper.TTSStatus,
        context: Context?
    ): Notification? {
        if (context == null) return null

        if (status == TTSHelper.TTSStatus.IsStopped) {
            NotificationManagerCompat.from(context).cancel(TTS_NOTIFICATION_ID)
            return null
        }

        updatePlaybackState(status)

        if (!hasCreateedNotificationChannel) {
            hasCreateedNotificationChannel = true
            createNotificationChannel(context)
        }
        val builder = NotificationCompat.Builder(context, TTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_volume_up_24)
            .setContentTitle(title)
            .setContentText(chapter.asString(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (icon != null) builder.setLargeIcon(icon)

        val cancelButton = MediaButtonReceiver.buildMediaButtonPendingIntent(
            context,
            PlaybackStateCompat.ACTION_STOP
        )
        val style = androidx.media.app.NotificationCompat.MediaStyle()
        mediaSession?.sessionToken?.let { token ->
            style.setShowCancelButton(true).setShowActionsInCompactView(1, 2)
                .setCancelButtonIntent(cancelButton)
                .setMediaSession(token)
        }

        builder.setStyle(style)

        val actionPlay = NotificationCompat.Action(
            R.drawable.ic_baseline_play_arrow_24,
            "Resume",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_PLAY
            )
        )

        val actionStop = NotificationCompat.Action(
            R.drawable.ic_baseline_stop_24,
            "Stop",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_STOP
            )
        )

        val actionPause = NotificationCompat.Action(
            R.drawable.ic_baseline_pause_24,
            "Pause",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_PAUSE
            )
        )

        val actionRewind = NotificationCompat.Action(
            R.drawable.ic_baseline_fast_rewind_24,
            "Rewind",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_REWIND
            )
        )

        val actionFastForward = NotificationCompat.Action(
            R.drawable.ic_baseline_fast_forward_24,
            "Fast Forward",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_FAST_FORWARD
            )
        )

        when (status) {
            TTSHelper.TTSStatus.IsRunning -> {
                builder.addAction(actionRewind)
                builder.addAction(actionStop)
                builder.addAction(actionPause)
                builder.addAction(actionFastForward)
            }

            TTSHelper.TTSStatus.IsPaused -> {
                builder.addAction(actionRewind)
                builder.addAction(actionStop)
                builder.addAction(actionPlay)
                builder.addAction(actionFastForward)
            }

            else -> {}
        }

        return builder.build()
    }

    fun notify(
        title: String,
        chapter: UiText,
        icon: Bitmap?,
        status: TTSHelper.TTSStatus,
        context: Context?
    ) {
        if (context == null) return
        val notification = createNotification(title, chapter, icon, status, context) ?: return

        with(NotificationManagerCompat.from(context)) {
            try {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }

                notify(TTS_NOTIFICATION_ID, notification)
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }
}
