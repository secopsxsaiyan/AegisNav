package com.aegisnav.app.data.repository

import com.aegisnav.app.data.dao.ThreatEventDao
import com.aegisnav.app.data.model.ThreatEvent
import javax.inject.Inject

class ThreatEventRepository @Inject constructor(private val dao: ThreatEventDao) {
    fun getAllNewestFirst() = dao.getAllNewestFirst()
    suspend fun insert(event: ThreatEvent) = dao.insert(event)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun deleteOlderThan(cutoff: Long) = dao.deleteOlderThan(cutoff)
}
