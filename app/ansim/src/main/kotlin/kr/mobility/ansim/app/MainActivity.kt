package kr.mobility.ansim.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: AnsimViewModel by viewModels()

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun reportPermissions() = vm.setPermissions(granted(CAR_SPEED), granted(Manifest.permission.RECORD_AUDIO))

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        reportPermissions()
        // 권한을 새로 받았으면 차량 속성 구독과 음성을 다시 건다.
        vm.startPlatform()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportPermissions()
        // 마이크는 빨강 안내 직후 "왜?"를 듣는 데만 쓴다. 거부해도 버튼으로 같은 답을 들을 수 있다.
        val missing = arrayOf(CAR_SPEED, Manifest.permission.RECORD_AUDIO).filter { !granted(it) }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        setContent { AnsimRoot(vm) }
    }

    override fun onResume() {
        super.onResume()
        reportPermissions()
    }

    private companion object {
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}
