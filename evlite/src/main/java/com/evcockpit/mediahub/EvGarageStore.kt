package com.evcockpit.mediahub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** All records are local, opt-in and editable; there is no remote vehicle API. */
object EvGarageStore {
    private const val PREF = "evcockpit_ev_private_garage"
    private const val PROFILE = "profile_v1"
    private const val SESSIONS = "sessions_v1"
    private const val SNAPSHOTS = "snapshots_v1"

    fun profile(context: Context): EvPlanner.Profile {
        val raw=context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(PROFILE,null) ?: return EvPlanner.Profile()
        return try {
            val j=JSONObject(raw)
            EvPlanner.Profile(
                usableKwh=j.optDouble("usableKwh",52.0),
                socPercent=j.optDouble("socPercent",60.0),
                targetPercent=j.optDouble("targetPercent",80.0),
                reservePercent=j.optDouble("reservePercent",10.0),
                consumptionKwhPer100Km=j.optDouble("consumption",18.0),
                distanceKm=j.optDouble("distanceKm",100.0),
                averageChargingKw=j.optDouble("averageKw",11.0),
                gridEfficiency=j.optDouble("efficiency",0.90),
                priceTlPerKwh=j.optDouble("priceTl",9.0)
            ).let { if(EvPlanner.valid(it)) it else EvPlanner.Profile() }
        } catch (_: Exception) { EvPlanner.Profile() }
    }

    fun saveProfile(context: Context,p: EvPlanner.Profile): Boolean {
        if(!EvPlanner.valid(p)) return false
        val j=JSONObject().put("usableKwh",p.usableKwh)
            .put("socPercent",p.socPercent).put("targetPercent",p.targetPercent)
            .put("reservePercent",p.reservePercent)
            .put("consumption",p.consumptionKwhPer100Km)
            .put("distanceKm",p.distanceKm)
            .put("averageKw",p.averageChargingKw)
            .put("efficiency",p.gridEfficiency)
            .put("priceTl",p.priceTlPerKwh)
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().putString(PROFILE,j.toString()).commit()
    }

    fun sessions(context: Context): List<ChargeSession> {
        val raw=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getString(SESSIONS,"[]") ?: return emptyList()
        return try {
            val arr=JSONArray(raw)
            buildList {
                for(i in 0 until minOf(arr.length(),1000)) {
                    val j=arr.getJSONObject(i)
                    val s=ChargeSession(j.optLong("createdAt"),j.optString("name"),
                        j.optDouble("kwh"),j.optDouble("tl"))
                    if(s.gridKwh in 0.0..2000.0 && s.costTl in 0.0..1_000_000.0) add(s)
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun addSession(context: Context, s: ChargeSession): Boolean {
        if(s.gridKwh !in 0.01..1000.0 || s.costTl !in 0.0..1_000_000.0) return false
        val arr=JSONArray()
        (listOf(s)+sessions(context)).take(1000).forEach {
            arr.put(JSONObject().put("createdAt",it.createdAtUtcMs)
                .put("name",it.description.take(80)).put("kwh",it.gridKwh)
                .put("tl",it.costTl))
        }
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().putString(SESSIONS,arr.toString()).commit()
    }

    fun clearSessions(context: Context) =
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().remove(SESSIONS).commit()

    data class BatteryNote(val timeMs: Long, val socPercent: Double,
                           val odometerKm: Double, val note: String)
    fun notes(context: Context): List<BatteryNote> {
        val raw=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getString(SNAPSHOTS,"[]") ?: return emptyList()
        return try {
            val arr=JSONArray(raw)
            buildList {
                for(i in 0 until minOf(arr.length(),400)) {
                    val j=arr.getJSONObject(i)
                    val n=BatteryNote(j.optLong("time"),j.optDouble("soc"),
                        j.optDouble("odo"),j.optString("note").take(140))
                    if(n.socPercent in 0.0..100.0 && n.odometerKm in 0.0..2_000_000.0) add(n)
                }
            }
        } catch (_:Exception) { emptyList() }
    }

    fun addNote(context: Context, n: BatteryNote): Boolean {
        if(n.socPercent !in 0.0..100.0 || n.odometerKm !in 0.0..2_000_000.0) return false
        val j=JSONArray()
        (listOf(n)+notes(context)).take(400).forEach {
            j.put(JSONObject().put("time",it.timeMs).put("soc",it.socPercent)
                .put("odo",it.odometerKm).put("note",it.note.take(140)))
        }
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().putString(SNAPSHOTS,j.toString()).commit()
    }
}
