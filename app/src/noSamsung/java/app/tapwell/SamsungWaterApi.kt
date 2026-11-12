package app.tapwell

import android.app.Activity
import android.content.Context

internal class SamsungWaterApi(context: Context) : SamsungWater {
    override val available = false
    override suspend fun permitted() = false
    override suspend fun request(activity: Activity) = false
    override suspend fun insert(event: WaterEvent): Unit = error("Samsung connection is not included in this build")
}
