package app.tapwell

import android.app.Activity

/** Implemented by the licensed SDK source set or by the OSS-only unavailable adapter. */
internal interface SamsungWater {
    val available: Boolean
    suspend fun permitted(): Boolean
    suspend fun request(activity: Activity): Boolean
    suspend fun insert(event: WaterEvent)
}
