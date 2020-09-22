/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.permissioncontroller.permission.data

import android.app.AppOpsManager
import android.app.AppOpsManager.OP_FLAGS_ALL_TRUSTED
import android.app.Application
import android.os.UserHandle
import android.util.Log
import com.android.permissioncontroller.PermissionControllerApplication
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Serializable

/**
 * LiveData that loads the last usage of each of a list of app ops for every package.
 *
 * <p>For app-ops with duration the end of the access is considered.
 *
 * <p>Returns map op-name -> {@link OpAccess}
 *
 * @param app The current application
 * @param opNames The names of the app ops we wish to search for
 * @param usageDurationMs how much ago can an access have happened to be considered
 */
class OpUsageLiveData(
    private val app: Application,
    private val opNames: List<String>,
    private val usageDurationMs: Long
) : SmartAsyncMediatorLiveData<@JvmSuppressWildcards Map<String, List<OpAccess>>>(),
        AppOpsManager.OnOpActiveChangedListener {
    private val appOpsManager = app.getSystemService(AppOpsManager::class.java)!!

    override suspend fun loadDataAndPostValue(job: Job) {
        val now = System.currentTimeMillis()
        val opMap = mutableMapOf<String, MutableList<OpAccess>>()

        val packageOps = try {
            appOpsManager.getPackagesForOps(opNames.toTypedArray())
        } catch (e: NullPointerException) {
            // older builds might not support all the app-ops requested
            emptyList<AppOpsManager.PackageOps>()
        }
        for (packageOp in packageOps) {
            for (opEntry in packageOp.ops) {
                for ((attributionTag, attributedOpEntry) in opEntry.attributedOpEntries) {
                    val user = UserHandle.getUserHandleForUid(packageOp.uid)
                    val lastAccessTime: Long = attributedOpEntry.getLastAccessTime(
                            OP_FLAGS_ALL_TRUSTED)

                    if (lastAccessTime == -1L) {
                        // There was no access, so skip
                        continue
                    }

                    var lastAccessDuration = attributedOpEntry.getLastDuration(OP_FLAGS_ALL_TRUSTED)

                    // Some accesses have no duration
                    if (lastAccessDuration == -1L) {
                        lastAccessDuration = 0
                    }

                    if (attributedOpEntry.isRunning ||
                            lastAccessTime + lastAccessDuration > (now - usageDurationMs)) {
                        val accessList = opMap.getOrPut(opEntry.opStr) { mutableListOf() }
                        val accessTime = if (attributedOpEntry.isRunning) {
                            OpAccess.IS_RUNNING
                        } else {
                            lastAccessTime
                        }
                        accessList.add(OpAccess(packageOp.packageName, attributionTag, user,
                                accessTime))

                        // TODO ntmyren: remove logs once b/160724034 is fixed
                        Log.i("OpUsageLiveData", "adding ${opEntry.opStr} for " +
                                "${packageOp.packageName}/$attributionTag, access time of " +
                                "$lastAccessTime, isRunning: ${attributedOpEntry.isRunning} " +
                                "current time $now, duration $lastAccessDuration")
                    } else {
                        Log.i("OpUsageLiveData", "NOT adding ${opEntry.opStr} for " +
                                "${packageOp.packageName}/$attributionTag, access time of " +
                                "$lastAccessTime, isRunning: ${attributedOpEntry.isRunning} " +
                                "current time $now, duration $lastAccessDuration")
                    }
                }
            }
        }

        postValue(opMap)
    }

    override fun onActive() {
        super.onActive()

        // appOpsManager.startWatchingNoted() is not exposed, hence force update regularly :-(
        GlobalScope.launch {
            while (hasActiveObservers()) {
                delay(1000)
                update()
            }
        }

        appOpsManager.startWatchingActive(opNames.toTypedArray(), { it.run() }, this)
    }

    override fun onInactive() {
        super.onInactive()

        appOpsManager.stopWatchingActive(this)
    }

    override fun onOpActiveChanged(op: String, uid: Int, packageName: String, active: Boolean) {
        update()
    }

    companion object : DataRepository<Pair<List<String>, Long>, OpUsageLiveData>() {
        override fun newValue(key: Pair<List<String>, Long>): OpUsageLiveData {
            return OpUsageLiveData(PermissionControllerApplication.get(), key.first, key.second)
        }

        operator fun get(ops: List<String>, usageDurationMs: Long): OpUsageLiveData {
            return get(ops to usageDurationMs)
        }
    }
}

data class OpAccess(
    val packageName: String,
    val attributionTag: String?,
    val user: UserHandle,
    val lastAccessTime: Long
) : Serializable {
    companion object {
        const val IS_RUNNING = -1L
    }

    fun isRunning() = lastAccessTime == IS_RUNNING
}
