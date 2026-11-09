package app.nageune.data

import android.content.Context
import app.nageune.trip.DayRecord
import app.nageune.trip.LegKind
import app.nageune.trip.LogLeg
import app.nageune.trip.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 운행기록부(하루 한 줄씩)와 사용자·차량 정보를 앱 내부 파일에 저장한다. */
class LogStore(context: Context) {
    private val file = File(context.filesDir, "drivelog.json")
    private val profileFile = File(context.filesDir, "profile.json")

    fun load(): List<DayRecord> = runCatching {
        if (!file.exists()) return emptyList()
        JSONArray(file.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it).toRecord() } }
    }.getOrDefault(emptyList())

    fun save(records: List<DayRecord>) {
        val tmp = File(file.parentFile, "drivelog.json.tmp")
        tmp.writeText(JSONArray(records.map { it.toJson() }).toString())
        tmp.renameTo(file)
    }

    fun loadProfile(): Profile? = runCatching {
        if (!profileFile.exists()) return null
        val o = JSONObject(profileFile.readText())
        Profile(o.getString("user"), o.getString("dept"), o.getString("carModel"), o.getString("plate"))
    }.getOrNull()

    fun saveProfile(p: Profile) {
        profileFile.writeText(
            JSONObject().put("user", p.user).put("dept", p.dept).put("carModel", p.carModel).put("plate", p.plate).toString(),
        )
    }

    private fun DayRecord.toJson() = JSONObject()
        .put("date", date).put("weekday", weekday).put("user", user).put("dept", dept)
        .put("startOdo", startOdo.toDouble()).put("endOdo", endOdo.toDouble())
        .put("commuteKm", commuteKm.toDouble()).put("businessKm", businessKm.toDouble())
        .put("visitCount", visitCount).put("note", note).put("sample", sample)
        .put(
            "legs",
            JSONArray(
                legs.map {
                    JSONObject().put("from", it.from).put("to", it.to)
                        .put("startOdo", it.startOdo.toDouble()).put("endOdo", it.endOdo.toDouble())
                        .put("kind", it.kind.name).putOpt("at", it.at)
                },
            ),
        )

    private fun JSONObject.toRecord(): DayRecord {
        val legs = optJSONArray("legs")?.let { a ->
            (0 until a.length()).map { i ->
                val l = a.getJSONObject(i)
                LogLeg(
                    l.getString("from"), l.getString("to"),
                    l.getDouble("startOdo").toFloat(), l.getDouble("endOdo").toFloat(),
                    LegKind.valueOf(l.getString("kind")),
                    if (l.has("at")) l.getLong("at") else null,
                )
            }
        } ?: emptyList()
        return DayRecord(
            date = getString("date"), weekday = getString("weekday"),
            user = getString("user"), dept = getString("dept"),
            startOdo = getDouble("startOdo").toFloat(), endOdo = getDouble("endOdo").toFloat(),
            commuteKm = getDouble("commuteKm").toFloat(), businessKm = getDouble("businessKm").toFloat(),
            visitCount = getInt("visitCount"), note = getString("note"),
            legs = legs, sample = optBoolean("sample"),
        )
    }
}
