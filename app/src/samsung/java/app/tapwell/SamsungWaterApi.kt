package app.tapwell

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.IdFilter
import com.samsung.android.sdk.health.data.request.ReadSourceFilter
import java.time.Instant
import java.time.ZoneOffset

internal class SamsungWaterApi(private val context: Context) : SamsungWater {
    override val available get() = BuildConfig.SAMSUNG_HEALTH_APPROVED
    private val health by lazy { HealthDataService.getStore(context.applicationContext) }
    private val permissions = setOf(
        Permission.of(DataTypes.WATER_INTAKE, AccessType.WRITE),
        Permission.of(DataTypes.WATER_INTAKE, AccessType.READ)
    )

    override suspend fun permitted(): Boolean = available && translated {
        health.getGrantedPermissions(permissions).containsAll(permissions)
    }

    override suspend fun request(activity: Activity): Boolean = available && translated {
        health.requestPermissions(permissions, activity).containsAll(permissions)
    }

    override suspend fun insert(event: WaterEvent) = translated {
        check(available)
        require(event.destination == WaterDestination.SAMSUNG)
        val id = "tapwell-water-${event.id}"
        // Both filters are mandatory: never query unrelated records or another app's water.
        val existing = health.readData(DataTypes.WATER_INTAKE.readDataRequestBuilder
            .setIdFilter(IdFilter.fromClientDataId(id))
            .setSourceFilter(ReadSourceFilter.fromApplicationId(context.packageName))
            .setLimit(1).build())
        if (existing.dataList.isEmpty()) {
            val device = health.getDeviceManager().getLocalDevice()
            val point = HealthDataPoint.builder()
                .setStartTime(Instant.ofEpochMilli(event.time), ZoneOffset.ofTotalSeconds(event.offsetSeconds))
                .setDeviceId(device.id).setClientDataId(id).setClientVersion(1)
                .addFieldData(DataType.WaterIntakeType.AMOUNT, event.millilitres.toFloat()).build()
            health.insertData(DataTypes.WATER_INTAKE.insertDataRequestBuilder.addData(point).build())
        }
        Unit
    }

    private suspend fun <T> translated(action: suspend () -> T): T = try { action() }
    catch (error: HealthDataException) {
        if (error.errorCode == ErrorCode.ERR_NO_USER_PERMISSION) throw SecurityException("Water permission removed", error)
        throw error
    }
}
