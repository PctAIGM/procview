package io.github.PctAIGM.procview.data

import io.github.PctAIGM.procview.data.db.PinnedTargetDao
import io.github.PctAIGM.procview.data.db.PinnedTargetEntity
import io.github.PctAIGM.procview.model.PinnedTarget
import io.github.PctAIGM.procview.model.PinnedTargetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PinnedTargetStore(
    private val dao: PinnedTargetDao,
    private val wallTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationMutex = Mutex()

    val targets: StateFlow<Set<PinnedTarget>> = dao.observeTargets()
        .map { entities ->
            entities.mapNotNull { it.toDomain() }
                .distinctBy(PinnedTarget::stableKey)
                .toSet()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun toggle(target: PinnedTarget): Unit = mutationMutex.withLock {
        if (!target.isValid()) return@withLock
        if (dao.target(target.stableKey) == null) {
            dao.insert(target.toEntity(wallTimeMillis()))
        } else {
            dao.delete(target.stableKey)
        }
        Unit
    }

    suspend fun remove(target: PinnedTarget): Unit = mutationMutex.withLock {
        dao.delete(target.stableKey)
        Unit
    }

    private fun PinnedTarget.toEntity(createdAt: Long) = PinnedTargetEntity(
        stableKey = stableKey,
        kind = kind.name,
        packageName = packageName,
        processName = processName,
        uid = uid,
        createdAtWallTimeMs = createdAt,
    )

    private fun PinnedTargetEntity.toDomain(): PinnedTarget? {
        val target = PinnedTarget(
            kind = runCatching { PinnedTargetKind.valueOf(kind) }.getOrNull() ?: return null,
            packageName = packageName,
            processName = processName,
            uid = uid,
        )
        return target.takeIf(PinnedTarget::isValid)
    }
}
