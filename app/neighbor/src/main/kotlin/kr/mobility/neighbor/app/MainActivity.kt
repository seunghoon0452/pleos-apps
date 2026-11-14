package kr.mobility.neighbor.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: NeighborViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(CAR_SPEED) != PackageManager.PERMISSION_GRANTED) permissions.launch(arrayOf(CAR_SPEED))
        setContent { NeighborRoot(vm) }
    }

    private companion object {
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}
