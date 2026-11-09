package app.nageune.data

import android.content.Context
import app.nageune.trip.ArrivalSource
import app.nageune.trip.TripLog
import app.nageune.trip.TripState
import app.nageune.trip.Visit
import app.nageune.trip.VisitStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 오늘 방문 상태를 앱 내부 파일(JSON)에 저장한다. 앱이 다시 켜져도 이어서 진행하기 위함.
 * 기어·문·위치·내비 남은 시간처럼 실시간 값은 저장하지 않는다.
 */
class DayStore(context: Context) {
    private val file = File(context.filesDir, "day.json")

    fun load(): TripState? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        TripState(
            visits = o.getJSONArray("visits").objects().map { it.toVisit() },
            date = o.optString("date").ifBlank { null },
            started = o.optBoolean("started"),
            routeChunk = o.optJSONArray("routeChunk")?.strings() ?: emptyList(),
            dayStartOdo = o.optFloatOrNull("dayStartOdo"),
            dayEndOdo = o.optFloatOrNull("dayEndOdo"),
            closeOdo = o.optFloatOrNull("closeOdo"),
            closedAt = o.optLongOrNull("closedAt"),
            log = o.optJSONArray("log")?.objects()?.map { TripLog(it.getLong("at"), it.getString("text")) } ?: emptyList(),
        )
    }.getOrNull()

    fun save(s: TripState) {
        val o = JSONObject()
            .put("visits", JSONArray(s.visits.map { it.toJson() }))
            .put("started", s.started)
            .putOpt("date", s.date)
            .put("routeChunk", JSONArray(s.routeChunk))
            .putOpt("dayStartOdo", s.dayStartOdo)
            .putOpt("dayEndOdo", s.dayEndOdo)
            .putOpt("closeOdo", s.closeOdo)
            .putOpt("closedAt", s.closedAt)
            .put("log", JSONArray(s.log.take(80).map { JSONObject().put("at", it.at).put("text", it.text) }))
        val tmp = File(file.parentFile, "day.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    fun clear() {
        file.delete()
    }

    private fun Visit.toJson() = JSONObject()
        .put("id", id).put("order", order).put("customer", customer).put("service", service)
        .put("address", address).put("lat", lat).put("lng", lng).put("window", window).put("note", note)
        .put("status", status.name)
        .putOpt("arrivedAt", arrivedAt).putOpt("arrivalSource", arrivalSource?.name)
        .putOpt("startedAt", startedAt).putOpt("finishedAt", finishedAt)
        .putOpt("odoAtArrival", odoAtArrival)
        .putOpt("notifiedAt", notifiedAt).putOpt("notifiedEtaSec", notifiedEtaSec)
        .putOpt("lateNotifiedAt", lateNotifiedAt)

    private fun JSONObject.toVisit() = Visit(
        id = getString("id"), order = getInt("order"), customer = getString("customer"),
        service = getString("service"), address = getString("address"),
        lat = getDouble("lat"), lng = getDouble("lng"), window = getString("window"),
        note = optString("note"),
        status = VisitStatus.valueOf(getString("status")),
        arrivedAt = optLongOrNull("arrivedAt"),
        arrivalSource = optStringOrNull("arrivalSource")?.let { ArrivalSource.valueOf(it) },
        startedAt = optLongOrNull("startedAt"), finishedAt = optLongOrNull("finishedAt"),
        odoAtArrival = optFloatOrNull("odoAtArrival"),
        notifiedAt = optLongOrNull("notifiedAt"),
        notifiedEtaSec = if (has("notifiedEtaSec")) getInt("notifiedEtaSec") else null,
        lateNotifiedAt = optLongOrNull("lateNotifiedAt"),
    )

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONObject.optLongOrNull(k: String) = if (has(k)) getLong(k) else null
    private fun JSONObject.optFloatOrNull(k: String) = if (has(k)) getDouble(k).toFloat() else null
    private fun JSONObject.optStringOrNull(k: String) = if (has(k)) getString(k) else null
}
