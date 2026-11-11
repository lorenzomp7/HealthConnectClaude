package com.healthconnectclaude.app

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

/** Legge i dati da Health Connect e li converte nel formato JSON atteso dal server. */
class HealthReader(private val client: HealthConnectClient) {

    companion object {
        const val PERMISSION_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        const val PERMISSION_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

        /** Tipi letti come record singoli (gli altri sono coperti dai totali giornalieri). */
        val RAW_TYPES: List<Pair<String, KClass<out Record>>> = listOf(
            "ExerciseSession" to ExerciseSessionRecord::class,
            "SleepSession" to SleepSessionRecord::class,
            "HeartRate" to HeartRateRecord::class,
            "RestingHeartRate" to RestingHeartRateRecord::class,
            "HeartRateVariabilityRmssd" to HeartRateVariabilityRmssdRecord::class,
            "Weight" to WeightRecord::class,
            "Height" to HeightRecord::class,
            "BodyFat" to BodyFatRecord::class,
            "LeanBodyMass" to LeanBodyMassRecord::class,
            "BasalMetabolicRate" to BasalMetabolicRateRecord::class,
            "OxygenSaturation" to OxygenSaturationRecord::class,
            "RespiratoryRate" to RespiratoryRateRecord::class,
            "BloodPressure" to BloodPressureRecord::class,
            "BloodGlucose" to BloodGlucoseRecord::class,
            "BodyTemperature" to BodyTemperatureRecord::class,
            "Hydration" to HydrationRecord::class,
            "Nutrition" to NutritionRecord::class,
            "Vo2Max" to Vo2MaxRecord::class,
        )

        private val AGGREGATE_ONLY_TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class,
            DistanceRecord::class,
            ActiveCaloriesBurnedRecord::class,
            TotalCaloriesBurnedRecord::class,
            FloorsClimbedRecord::class,
            ElevationGainedRecord::class,
        )

        val ALL_PERMISSIONS: Set<String> =
            (RAW_TYPES.map { it.second } + AGGREGATE_ONLY_TYPES)
                .map { HealthPermission.getReadPermission(it) }
                .toSet() + PERMISSION_BACKGROUND + PERMISSION_HISTORY

        private val EXERCISE_NAMES: Map<Int, String> by lazy {
            ExerciseSessionRecord::class.java.fields
                .filter {
                    it.name.startsWith("EXERCISE_TYPE_") &&
                        it.type == Int::class.javaPrimitiveType &&
                        Modifier.isStatic(it.modifiers)
                }
                .associate { it.getInt(null) to it.name.removePrefix("EXERCISE_TYPE_").lowercase() }
        }

        private val SLEEP_STAGES = mapOf(
            SleepSessionRecord.STAGE_TYPE_UNKNOWN to "unknown",
            SleepSessionRecord.STAGE_TYPE_AWAKE to "awake",
            SleepSessionRecord.STAGE_TYPE_SLEEPING to "sleeping",
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to "out_of_bed",
            SleepSessionRecord.STAGE_TYPE_LIGHT to "light",
            SleepSessionRecord.STAGE_TYPE_DEEP to "deep",
            SleepSessionRecord.STAGE_TYPE_REM to "rem",
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to "awake_in_bed",
        )
    }

    private fun perm(type: KClass<out Record>) = HealthPermission.getReadPermission(type)

    private fun r1(d: Double): Double = Math.round(d * 10.0) / 10.0
    private fun r2(d: Double): Double = Math.round(d * 100.0) / 100.0

    private fun localDate(t: Instant, offset: ZoneOffset?): LocalDate =
        t.atZone(offset ?: ZoneId.systemDefault()).toLocalDate()

    private fun envelope(
        type: String,
        r: Record,
        start: Instant,
        end: Instant?,
        date: LocalDate,
        data: JSONObject,
    ): JSONObject = JSONObject()
        .put("id", r.metadata.id)
        .put("type", type)
        .put("start", start.toString())
        .putOpt("end", end?.toString())
        .put("date", date.toString())
        .put("source", r.metadata.dataOrigin.packageName)
        .put("data", data)

    private suspend fun readAll(type: KClass<out Record>, from: Instant, to: Instant): List<Record> {
        @Suppress("UNCHECKED_CAST")
        val recordType = type as KClass<Record>
        val out = mutableListOf<Record>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = 1000,
                    pageToken = token,
                )
            )
            out += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    /** Legge tutti i record grezzi per cui c'è permesso. */
    suspend fun readRawRecords(granted: Set<String>, from: Instant, to: Instant, log: (String) -> Unit): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for ((name, type) in RAW_TYPES) {
            if (perm(type) !in granted) continue
            try {
                val records = readAll(type, from, to)
                for (r in records) toJson(r, granted)?.let { out += it }
                if (records.isNotEmpty()) log("$name: ${records.size}")
            } catch (e: Exception) {
                log("$name: errore ${e.message}")
            }
        }
        return out
    }

    private suspend fun toJson(r: Record, granted: Set<String>): JSONObject? = when (r) {
        is ExerciseSessionRecord -> {
            val minutes = java.time.Duration.between(r.startTime, r.endTime).toMinutes()
            val data = JSONObject()
                .put("exercise", EXERCISE_NAMES[r.exerciseType] ?: "type_${r.exerciseType}")
                .putOpt("title", r.title)
                .putOpt("notes", r.notes)
                .put("duration_minutes", minutes)
            sessionStats(r.startTime, r.endTime, granted, data)
            envelope("ExerciseSession", r, r.startTime, r.endTime, localDate(r.startTime, r.startZoneOffset), data)
        }

        is SleepSessionRecord -> {
            val stageMinutes = mutableMapOf<String, Long>()
            val stages = JSONArray()
            for (s in r.stages) {
                val name = SLEEP_STAGES[s.stage] ?: "unknown"
                stageMinutes[name] = (stageMinutes[name] ?: 0L) +
                    java.time.Duration.between(s.startTime, s.endTime).toMinutes()
                stages.put(
                    JSONObject().put("stage", name).put("start", s.startTime.toString()).put("end", s.endTime.toString())
                )
            }
            val data = JSONObject()
                .put("duration_minutes", java.time.Duration.between(r.startTime, r.endTime).toMinutes())
                .putOpt("title", r.title)
                .putOpt("notes", r.notes)
            if (stageMinutes.isNotEmpty()) data.put("stage_minutes", JSONObject(stageMinutes as Map<*, *>))
            if (stages.length() > 0) data.put("stages", stages)
            // La notte viene attribuita al giorno del risveglio.
            envelope("SleepSession", r, r.startTime, r.endTime, localDate(r.endTime, r.endZoneOffset), data)
        }

        is HeartRateRecord -> {
            if (r.samples.isEmpty()) null else {
                val bpm = r.samples.map { it.beatsPerMinute }
                val data = JSONObject()
                    .put("bpm_avg", r1(bpm.average()))
                    .put("bpm_min", bpm.min())
                    .put("bpm_max", bpm.max())
                    .put("samples", bpm.size)
                envelope("HeartRate", r, r.startTime, r.endTime, localDate(r.startTime, r.startZoneOffset), data)
            }
        }

        is RestingHeartRateRecord -> instant("RestingHeartRate", r, r.time, r.zoneOffset, JSONObject().put("bpm", r.beatsPerMinute))
        is HeartRateVariabilityRmssdRecord -> instant("HeartRateVariabilityRmssd", r, r.time, r.zoneOffset, JSONObject().put("rmssd_ms", r1(r.heartRateVariabilityMillis)))
        is WeightRecord -> instant("Weight", r, r.time, r.zoneOffset, JSONObject().put("kg", r2(r.weight.inKilograms)))
        is HeightRecord -> instant("Height", r, r.time, r.zoneOffset, JSONObject().put("m", r2(r.height.inMeters)))
        is BodyFatRecord -> instant("BodyFat", r, r.time, r.zoneOffset, JSONObject().put("percent", r1(r.percentage.value)))
        is LeanBodyMassRecord -> instant("LeanBodyMass", r, r.time, r.zoneOffset, JSONObject().put("kg", r2(r.mass.inKilograms)))
        is BasalMetabolicRateRecord -> instant("BasalMetabolicRate", r, r.time, r.zoneOffset, JSONObject().put("kcal_per_day", r1(r.basalMetabolicRate.inKilocaloriesPerDay)))
        is OxygenSaturationRecord -> instant("OxygenSaturation", r, r.time, r.zoneOffset, JSONObject().put("percent", r1(r.percentage.value)))
        is RespiratoryRateRecord -> instant("RespiratoryRate", r, r.time, r.zoneOffset, JSONObject().put("breaths_per_min", r1(r.rate)))
        is BloodPressureRecord -> instant(
            "BloodPressure", r, r.time, r.zoneOffset,
            JSONObject()
                .put("systolic_mmhg", r1(r.systolic.inMillimetersOfMercury))
                .put("diastolic_mmhg", r1(r.diastolic.inMillimetersOfMercury)),
        )
        is BloodGlucoseRecord -> instant(
            "BloodGlucose", r, r.time, r.zoneOffset,
            JSONObject()
                .put("mmol_per_l", r2(r.level.inMillimolesPerLiter))
                .put("mg_per_dl", r1(r.level.inMilligramsPerDeciliter))
                .put("relation_to_meal", r.relationToMeal),
        )
        is BodyTemperatureRecord -> instant("BodyTemperature", r, r.time, r.zoneOffset, JSONObject().put("celsius", r2(r.temperature.inCelsius)))
        is Vo2MaxRecord -> instant("Vo2Max", r, r.time, r.zoneOffset, JSONObject().put("ml_per_kg_min", r1(r.vo2MillilitersPerMinuteKilogram)))
        is HydrationRecord -> envelope(
            "Hydration", r, r.startTime, r.endTime, localDate(r.startTime, r.startZoneOffset),
            JSONObject().put("liters", r2(r.volume.inLiters)),
        )
        is NutritionRecord -> envelope(
            "Nutrition", r, r.startTime, r.endTime, localDate(r.startTime, r.startZoneOffset),
            JSONObject()
                .putOpt("name", r.name)
                .put("meal_type", r.mealType)
                .putOpt("kcal", r.energy?.inKilocalories?.let(::r1))
                .putOpt("protein_g", r.protein?.inGrams?.let(::r1))
                .putOpt("carbs_g", r.totalCarbohydrate?.inGrams?.let(::r1))
                .putOpt("fat_g", r.totalFat?.inGrams?.let(::r1))
                .putOpt("sugar_g", r.sugar?.inGrams?.let(::r1))
                .putOpt("fiber_g", r.dietaryFiber?.inGrams?.let(::r1)),
        )
        else -> null
    }

    private fun instant(type: String, r: Record, time: Instant, offset: ZoneOffset?, data: JSONObject) =
        envelope(type, r, time, null, localDate(time, offset), data)

    /** Aggiunge distanza, calorie, passi e FC misurati durante un allenamento. */
    private suspend fun sessionStats(start: Instant, end: Instant, granted: Set<String>, data: JSONObject) {
        val metrics = mutableSetOf<AggregateMetric<*>>()
        if (perm(DistanceRecord::class) in granted) metrics += DistanceRecord.DISTANCE_TOTAL
        if (perm(ActiveCaloriesBurnedRecord::class) in granted) metrics += ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        if (perm(StepsRecord::class) in granted) metrics += StepsRecord.COUNT_TOTAL
        if (perm(HeartRateRecord::class) in granted) {
            metrics += HeartRateRecord.BPM_AVG
            metrics += HeartRateRecord.BPM_MAX
        }
        if (metrics.isEmpty()) return
        try {
            val res = client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(start, end)))
            data.putOpt("distance_m", res[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.let(::r1))
            data.putOpt("active_kcal", res[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.let(::r1))
            data.putOpt("steps", res[StepsRecord.COUNT_TOTAL])
            data.putOpt("hr_avg", res[HeartRateRecord.BPM_AVG])
            data.putOpt("hr_max", res[HeartRateRecord.BPM_MAX])
        } catch (_: Exception) {
            // Statistiche opzionali: se Health Connect rifiuta (es. limite di richieste), si prosegue.
        }
    }

    private class DailyMetric(
        val key: String,
        val type: KClass<out Record>,
        val metric: AggregateMetric<*>,
        val extract: (AggregationResult) -> Number?,
    )

    private val dailyMetrics: List<DailyMetric> = listOf(
        DailyMetric("steps", StepsRecord::class, StepsRecord.COUNT_TOTAL) { it[StepsRecord.COUNT_TOTAL] },
        DailyMetric("distance_m", DistanceRecord::class, DistanceRecord.DISTANCE_TOTAL) { it[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.let(::r1) },
        DailyMetric("active_kcal", ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) { it[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.let(::r1) },
        DailyMetric("total_kcal", TotalCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord.ENERGY_TOTAL) { it[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.let(::r1) },
        DailyMetric("floors", FloorsClimbedRecord::class, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) { it[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.let(::r1) },
        DailyMetric("elevation_gained_m", ElevationGainedRecord::class, ElevationGainedRecord.ELEVATION_GAINED_TOTAL) { it[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters?.let(::r1) },
        DailyMetric("exercise_minutes", ExerciseSessionRecord::class, ExerciseSessionRecord.EXERCISE_DURATION_TOTAL) { it[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes() },
        DailyMetric("sleep_minutes", SleepSessionRecord::class, SleepSessionRecord.SLEEP_DURATION_TOTAL) { it[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes() },
        DailyMetric("hr_avg", HeartRateRecord::class, HeartRateRecord.BPM_AVG) { it[HeartRateRecord.BPM_AVG] },
        DailyMetric("hr_min", HeartRateRecord::class, HeartRateRecord.BPM_MIN) { it[HeartRateRecord.BPM_MIN] },
        DailyMetric("hr_max", HeartRateRecord::class, HeartRateRecord.BPM_MAX) { it[HeartRateRecord.BPM_MAX] },
        DailyMetric("resting_hr", RestingHeartRateRecord::class, RestingHeartRateRecord.BPM_AVG) { it[RestingHeartRateRecord.BPM_AVG] },
        DailyMetric("weight_kg", WeightRecord::class, WeightRecord.WEIGHT_AVG) { it[WeightRecord.WEIGHT_AVG]?.inKilograms?.let(::r2) },
        DailyMetric("hydration_l", HydrationRecord::class, HydrationRecord.VOLUME_TOTAL) { it[HydrationRecord.VOLUME_TOTAL]?.inLiters?.let(::r2) },
        DailyMetric("intake_kcal", NutritionRecord::class, NutritionRecord.ENERGY_TOTAL) { it[NutritionRecord.ENERGY_TOTAL]?.inKilocalories?.let(::r1) },
    )

    /**
     * Totali giornalieri calcolati da Health Connect (deduplicati tra le app secondo
     * le priorità impostate dall'utente), dal giorno [fromDate] a oggi incluso.
     */
    suspend fun readDailySummaries(granted: Set<String>, fromDate: LocalDate, log: (String) -> Unit): List<JSONObject> {
        val metrics = dailyMetrics.filter { perm(it.type) in granted }
        if (metrics.isEmpty()) return emptyList()
        val out = mutableListOf<JSONObject>()
        val endExclusive = LocalDate.now().plusDays(1)
        var chunkStart = fromDate
        while (chunkStart < endExclusive) {
            val chunkEnd = minOf(chunkStart.plusDays(31), endExclusive)
            try {
                val groups = client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = metrics.map { it.metric }.toSet(),
                        timeRangeFilter = TimeRangeFilter.between(chunkStart.atStartOfDay(), chunkEnd.atStartOfDay()),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                )
                for (g in groups) {
                    val data = JSONObject()
                    for (m in metrics) m.extract(g.result)?.let { data.put(m.key, it) }
                    if (data.length() == 0) continue
                    val date = g.startTime.toLocalDate()
                    out += JSONObject()
                        .put("id", "daily:$date")
                        .put("type", "DailySummary")
                        .put("start", "${date}T00:00:00")
                        .put("date", date.toString())
                        .put("data", data)
                }
            } catch (e: Exception) {
                log("Totali giornalieri $chunkStart: errore ${e.message}")
            }
            chunkStart = chunkEnd
        }
        log("Giorni con totali: ${out.size}")
        return out
    }
}
