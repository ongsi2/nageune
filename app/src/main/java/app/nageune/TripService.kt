package app.nageune

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 하루 방문이 진행되는 동안 띄워 두는 위치 포그라운드 서비스.
 *
 * 경로를 넣으면 내비가 화면을 덮어 나그네가 뒤로 간다. 안드로이드는 화면에 안 보이는 앱에 위치를 주지 않아서
 * 이 서비스가 없으면 도착·출발을 놓친다(11/9 에뮬레이터에서 4~7번 방문을 놓친 원인).
 * 서비스 자체는 아무것도 하지 않고, 앱 프로세스가 위치를 계속 받을 수 있게 해 준다.
 */
class TripService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTI_ID, n)
        }
        foreground = true
        // startForeground 전에 멈추라는 요청이 왔으면 이제 멈춘다.
        if (wantStop) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        foreground = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val NOTI_ID = 7

        @Volatile private var foreground = false
        @Volatile private var wantStop = false

        fun start(context: Context) {
            wantStop = false
            runCatching { context.startForegroundService(Intent(context, TripService::class.java)) }
        }

        /**
         * startForegroundService 뒤 startForeground 전에 stopService하면 시스템이 앱을 종료시킨다
         * (ForegroundServiceDidNotStartInTimeException, 11/9 확인). 그래서 포그라운드가 된 뒤에만 멈춘다.
         */
        fun stop(context: Context) {
            if (foreground) context.stopService(Intent(context, TripService::class.java))
            else wantStop = true
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "방문 진행", NotificationManager.IMPORTANCE_LOW),
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("나그네 · 방문 진행 중")
                .setContentText("도착과 출발을 기록하고 있어요")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
