/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.permissioncontroller.permission.ui.model

import android.Manifest.permission_group.CAMERA
import android.Manifest.permission_group.LOCATION
import android.Manifest.permission_group.MICROPHONE
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.SensorPrivacyManager
import android.hardware.SensorPrivacyManager.Sensors
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.permission.PermissionGroupUsage
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.android.permissioncontroller.permission.data.LightAppPermGroupLiveData
import com.android.permissioncontroller.permission.data.SmartUpdateMediatorLiveData
import com.android.permissioncontroller.permission.model.livedatatypes.LightAppPermGroup
import com.android.permissioncontroller.permission.utils.KotlinUtils
import com.android.permissioncontroller.permission.utils.LocationUtils
import kotlin.collections.set

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SafetyHubViewModel(
    private val app: Application,
    private val sessionId: Long,
    private val permGroupUsages: List<PermissionGroupUsage>
) : AndroidViewModel(app) {

    private val sensorPrivacyManager: SensorPrivacyManager =
        app.getSystemService(SensorPrivacyManager::class.java)!!
    private val locationManager: LocationManager =
        app.getSystemService(LocationManager::class.java)!!

    val lightAppPermMap = mutableMapOf<Triple<String, String, UserHandle>, LightAppPermGroup?>()

    val lightAppPermGroupLiveData = object
        : SmartUpdateMediatorLiveData<Boolean>() {

        init {
            for (permGroupUsage in permGroupUsages) {
                val pgTriple = Triple(permGroupUsage.packageName,
                    permGroupUsage.permissionGroupName,
                    UserHandle.getUserHandleForUid(permGroupUsage.uid))
                val appPermGroupLiveData = LightAppPermGroupLiveData[pgTriple]
                addSource(appPermGroupLiveData) { v ->
                    lightAppPermMap[pgTriple] = v
                }
            }
        }

        override fun onUpdate() {
            // Do nothing
        }
    }

    fun revokePermission(usage: PermissionGroupUsage) {
        val group = lightAppPermMap.get(Triple(usage.packageName, usage.permissionGroupName,
            UserHandle.getUserHandleForUid(usage.uid)))

        if (group != null) {
            KotlinUtils.revokeForegroundRuntimePermissions(app, group)
            KotlinUtils.revokeBackgroundRuntimePermissions(app, group)
        }
    }

    fun toggleSensor(groupName: String) {
        when (groupName) {
            MICROPHONE -> {
                val blocked = sensorPrivacyManager.isSensorPrivacyEnabled(Sensors.MICROPHONE)
                sensorPrivacyManager.setSensorPrivacy(Sensors.MICROPHONE, !blocked)
                sensorPrivacyLiveData.update()
            }
            CAMERA -> {
                val blocked = sensorPrivacyManager.isSensorPrivacyEnabled(Sensors.CAMERA)
                sensorPrivacyManager.setSensorPrivacy(Sensors.CAMERA, !blocked)
                sensorPrivacyLiveData.update()
            }
            LOCATION -> {
                val enabled = locationManager.isLocationEnabledForUser(Process.myUserHandle())
                locationManager.setLocationEnabledForUser(!enabled, Process.myUserHandle())
                sensorPrivacyLiveData.update()
            }
        }
    }

    fun navigateToSecuritySettings(fragment: Fragment) {
        fragment.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    val sensorPrivacyLiveData: SmartUpdateMediatorLiveData<Map<String, Boolean>> =
        object : SmartUpdateMediatorLiveData<Map<String, Boolean>>(),
            SensorPrivacyManager.OnSensorPrivacyChangedListener, LocationUtils.LocationListener {
            override fun onUpdate() {
                val cameraEnabled = !sensorPrivacyManager.isSensorPrivacyEnabled(Sensors.CAMERA)
                val micEnabled = !sensorPrivacyManager.isSensorPrivacyEnabled(Sensors.MICROPHONE)
                val locationEnabled =
                    locationManager.isLocationEnabledForUser(Process.myUserHandle())
                value = mapOf(CAMERA to cameraEnabled, MICROPHONE to micEnabled,
                    LOCATION to locationEnabled)
            }

            override fun onSensorPrivacyChanged(sensor: Int, enabled: Boolean) {
                update()
            }

            override fun onLocationStateChange(enabled: Boolean) {
                update()
            }

            override fun onActive() {
                super.onActive()
                sensorPrivacyManager.addSensorPrivacyListener(Sensors.CAMERA, this)
                sensorPrivacyManager.addSensorPrivacyListener(Sensors.MICROPHONE, this)
                LocationUtils.addLocationListener(this)
            }

            override fun onInactive() {
                super.onInactive()
                sensorPrivacyManager.removeSensorPrivacyListener(Sensors.CAMERA, this)
                sensorPrivacyManager.removeSensorPrivacyListener(Sensors.MICROPHONE, this)
                LocationUtils.removeLocationListener(this)
            }
        }

    fun navigateToManageService(fragment: Fragment, context: Context, usage: PermissionGroupUsage) {
        var intent: Intent = Intent(Intent.ACTION_MANAGE_PERMISSION_USAGE)
        intent.setPackage(usage.packageName)
        intent.putExtra(Intent.EXTRA_PERMISSION_GROUP_NAME, usage.permissionGroupName)
        intent.putExtra(Intent.EXTRA_ATTRIBUTION_TAGS, arrayOf(usage.attributionTag.toString()))
        intent.putExtra(Intent.EXTRA_SHOWING_ATTRIBUTION, true)
        val resolveInfo: ResolveInfo? = context.packageManager.resolveActivity(
            intent, PackageManager.ResolveInfoFlags.of(0))
        if (resolveInfo?.activityInfo == null) {
            intent = Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS)
            intent.putExtra(Intent.EXTRA_USER, UserHandle.getUserHandleForUid(usage.uid))
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, usage.packageName)
        } else {
            intent.component = ComponentName(usage.packageName, resolveInfo.activityInfo.name)
        }
        fragment.startActivity(intent)
    }

    fun navigateToSeeUsage(fragment: Fragment, permGroupName: String) {
        val seeUsageIntent = Intent(Intent.ACTION_REVIEW_PERMISSION_HISTORY)
        seeUsageIntent.putExtra(Intent.EXTRA_PERMISSION_GROUP_NAME, permGroupName)
        fragment.startActivity(seeUsageIntent)
    }
}

/**
 * Factory for a SafetyHubFragment
 *
 * @param app The current application
 * @param sessionId A session ID used in logs to identify this particular session
 */
@RequiresApi(Build.VERSION_CODES.S)
class SafetyHubViewModelFactory(
    private val app: Application,
    private val sessionId: Long,
    private val permGroupUsages: List<PermissionGroupUsage>
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SafetyHubViewModel(app, sessionId, permGroupUsages) as T
    }
}
