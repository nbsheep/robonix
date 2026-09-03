package com.dji.wang.aircraft.models

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * GPS航点
 * @param latitude 纬度
 * @param longitude 经度
 * @param altitude 目标高度（米，相对起飞点）
 */
data class Waypoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("latitude", latitude)
        put("longitude", longitude)
        put("altitude", altitude)
    }

    companion object {
        fun fromJson(obj: JSONObject): Waypoint = Waypoint(
            latitude = obj.getDouble("latitude"),
            longitude = obj.getDouble("longitude"),
            altitude = obj.getDouble("altitude")
        )
    }
}

/**
 * 巡航路线
 * @param name 路线名称
 * @param waypoints 航点列表
 * @param createdAt 创建时间戳
 */
data class Route(
    val name: String,
    val waypoints: List<Waypoint>,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("createdAt", createdAt)
        put("waypoints", JSONArray().apply {
            waypoints.forEach { put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(obj: JSONObject): Route {
            val arr = obj.getJSONArray("waypoints")
            val wps = (0 until arr.length()).map { Waypoint.fromJson(arr.getJSONObject(it)) }
            return Route(
                name = obj.getString("name"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                waypoints = wps
            )
        }
    }
}

/**
 * 路线存储管理器
 * 保存到 app内部存储/routes/ 目录
 */
object RouteStorage {
    private const val TAG = "RouteStorage"
    private const val ROUTES_DIR = "routes"

    private fun getRoutesDir(context: Context): File {
        val dir = File(context.filesDir, ROUTES_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 列出所有已保存的路线名称 */
    fun listRoutes(context: Context): List<String> {
        val dir = getRoutesDir(context)
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    /** 保存路线 */
    fun save(context: Context, route: Route): Boolean {
        return try {
            val file = File(getRoutesDir(context), "${route.name}.json")
            file.writeText(route.toJson().toString(2))
            Log.i(TAG, "路线已保存: ${route.name} (${route.waypoints.size}个航点)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存路线失败: ${e.message}", e)
            false
        }
    }

    /** 加载路线 */
    fun load(context: Context, name: String): Route? {
        return try {
            val file = File(getRoutesDir(context), "$name.json")
            if (!file.exists()) return null
            val json = file.readText()
            Route.fromJson(JSONObject(json))
        } catch (e: Exception) {
            Log.e(TAG, "加载路线失败: ${e.message}", e)
            null
        }
    }

    /** 删除路线 */
    fun delete(context: Context, name: String): Boolean {
        return try {
            val file = File(getRoutesDir(context), "$name.json")
            file.delete()
        } catch (e: Exception) {
            false
        }
    }
}
