package kr.mobility.mirichaeum.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: MiriViewModel by viewModels()

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun reportPermissions() = vm.setPermissions(granted(CAR_SPEED), granted(Manifest.permission.RECORD_AUDIO))

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        reportPermissions()
        // 속도·마이크 권한을 새로 받았으면 다시 연결한다.
        vm.start()
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        reportPermissions()
        if (ok) {
            vm.start()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // 다시 묻지 못하는 상태면 시스템 설정으로 안내한다.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportPermissions()
        // 이미 허용된 권한은 다시 묻지 않는다(다시 연결하면 음성 엔진 초기화가 두 번 일어남).
        val missing = arrayOf(CAR_SPEED, Manifest.permission.RECORD_AUDIO).filter { !granted(it) }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        setContent { MiriRoot(vm, onRequestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) }
    }

    override fun onResume() {
        super.onResume()
        reportPermissions()
    }

    private companion object {
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}
