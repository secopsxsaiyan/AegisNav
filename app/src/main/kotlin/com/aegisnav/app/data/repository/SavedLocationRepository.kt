package com.aegisnav.app.data.repository

import com.aegisnav.app.data.dao.SavedLocationDao
import com.aegisnav.app.data.model.SavedLocation
import javax.inject.Inject

class SavedLocationRepository @Inject constructor(private val dao: SavedLocationDao) {
    fun getAllNewestFirst() = dao.getAllNewestFirst()
    suspend fun insert(location: SavedLocation) = dao.insert(location)
    suspend fun deleteById(id: Int) = dao.deleteById(id)
    suspend fun deleteByType(type: String) = dao.deleteByType(type)
    suspend fun deleteAll() = dao.deleteAll()
}
