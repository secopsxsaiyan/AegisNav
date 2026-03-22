package com.aegisnav.app.data.repository

import com.aegisnav.app.data.dao.ReportsDao
import com.aegisnav.app.data.model.Report
import javax.inject.Inject

class ReportsRepository @Inject constructor(private val dao: ReportsDao) {
    suspend fun insert(report: Report): Long = dao.insert(report)
    fun getAllReports() = dao.getAllReports()
    fun getByType(type: String) = dao.getByType(type)
    suspend fun getNearby(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) =
        dao.getNearby(minLat, maxLat, minLon, maxLon)
    suspend fun upvote(id: Int) = dao.upvote(id)
    suspend fun markCleared(id: Int) = dao.markCleared(id)
    suspend fun setUserVerdict(id: Int, verdict: String) = dao.setUserVerdict(id, verdict)
    suspend fun deleteAll() = dao.deleteAll()
}
