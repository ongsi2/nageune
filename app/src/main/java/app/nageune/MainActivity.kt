package app.nageune

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import app.nageune.trip.TripEvent
import app.nageune.ui.DayScreen

/**
 * 나그네 메인 화면. 시험·시연용으로 adb에서도 동작을 걸 수 있다:
 *   adb shell am start -n app.nageune/.MainActivity --es action start|arrive|work|finish|skip|close|log|day|newday|yesterday|reset
 * 기록은 logcat 태그 "Nageune".
 */
class MainActivity : ComponentActivity() {

    private val vm: DayViewModel by viewModels()

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onLocationPermission(granted)
        if (granted) reopenInFreshTask()
    }

    /**
     * Pleos 홈 화면은 권한 창이 한 번 올라왔던 작업(task)을 주행 중에 계속 가린다(Pleos Connect Emulator 2.1에서 확인,
     * 권한을 허용한 뒤에도 그 작업이 살아 있는 동안 "주행 중에는 사용할 수 없습니다"가 뜬다).
     * 그래서 권한을 받은 직후 지금 작업을 닫고 새 작업으로 다시 연다. 처음 한 번만 일어난다.
     */
    private fun reopenInFreshTask() {
        vm.debug("권한 허용 → 새 작업으로 다시 열기")
        val again = Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        finishAndRemoveTask()
        applicationContext.startActivity(again)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DayScreen(vm) }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.onLocationPermission(true) else askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)

        handleAction(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.debug("화면 시작(onStart)")
        vm.checkRollover()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.onLocationPermission(true)
        }
    }

    override fun onDestroy() {
        vm.debug("화면 종료(onDestroy, finishing=$isFinishing)")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    private fun handleAction(intent: Intent?) {
        val action = intent?.getStringExtra("action") ?: return
        intent.removeExtra("action")
        val cur = vm.state.value.current
        when (action) {
            "start" -> vm.post(TripEvent.StartDay)
            "reset" -> vm.resetDay()
            "arrive" -> cur?.let { vm.post(TripEvent.ManualArrive(it.id)) }
            "work" -> cur?.let { vm.post(TripEvent.ManualStart(it.id)) }
            "finish" -> cur?.let { vm.post(TripEvent.ManualFinish(it.id)) }
            "skip" -> cur?.let { vm.post(TripEvent.Skip(it.id)) }
            "close" -> vm.post(TripEvent.CloseDay)
            "log" -> vm.openLog()
            "day" -> vm.closeLog()
            "newday" -> vm.startNewDay()
            "yesterday" -> vm.simulateYesterday()
        }
    }
}
