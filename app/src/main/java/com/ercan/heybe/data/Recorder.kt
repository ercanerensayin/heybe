package com.ercan.heybe.data

import android.content.Context

/** Araçların ortak geçmişe kayıt düşmesi için tek giriş noktası. */
object Recorder {
    suspend fun done(ctx: Context, toolId: String, saved: FileStore.Saved, mime: String): Long =
        HeybeDb.get(ctx).history().insert(
            HistoryItem(toolId = toolId, title = saved.name, uri = saved.uri.toString(), path = saved.path,
                mime = mime, size = saved.size, status = Status.DONE, progress = 100)
        )

    suspend fun start(ctx: Context, toolId: String, title: String, mime: String): Long =
        HeybeDb.get(ctx).history().insert(HistoryItem(toolId = toolId, title = title, mime = mime, status = Status.RUNNING))

    suspend fun progress(ctx: Context, id: Long, percent: Int) {
        val dao = HeybeDb.get(ctx).history()
        dao.get(id)?.let { dao.update(it.copy(progress = percent.coerceIn(0, 100))) }
    }

    suspend fun finish(ctx: Context, id: Long, saved: FileStore.Saved) {
        val dao = HeybeDb.get(ctx).history()
        dao.get(id)?.let {
            dao.update(it.copy(title = saved.name, uri = saved.uri.toString(), path = saved.path, size = saved.size,
                status = Status.DONE, progress = 100))
        }
    }

    suspend fun fail(ctx: Context, id: Long, message: String) {
        val dao = HeybeDb.get(ctx).history()
        dao.get(id)?.let { dao.update(it.copy(status = Status.FAILED, error = message)) }
    }
}
