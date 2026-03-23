package com.aegisnav.app.data.repository

import com.aegisnav.app.data.dao.IgnoreListDao
import com.aegisnav.app.data.model.IgnoreListEntry
import javax.inject.Inject

class IgnoreListRepository @Inject constructor(private val dao: IgnoreListDao) {
    fun getAll() = dao.getAll()
    fun getAllEntries() = dao.getAll()
    suspend fun insert(entry: IgnoreListEntry) = dao.insert(entry)
    suspend fun addAddress(entry: IgnoreListEntry) = dao.insert(entry)
    suspend fun delete(entry: IgnoreListEntry) = dao.delete(entry)
    suspend fun getAllAddresses() = dao.getAllAddresses()
    suspend fun deleteExpired() = dao.deleteExpired(System.currentTimeMillis())
    suspend fun removeAddress(address: String) = dao.deleteByAddress(address)
    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Phase 3 — Feature 3.9.
     * Add a permanent (non-expiring) ignore list entry.
     * Sets [IgnoreListEntry.permanent] = true so [deleteExpired] never removes it.
     */
    suspend fun addPermanent(entry: IgnoreListEntry) =
        dao.insert(entry.copy(permanent = true, expiresAt = Long.MAX_VALUE))

    suspend fun getPermanentAddresses() = dao.getPermanentAddresses()
}
