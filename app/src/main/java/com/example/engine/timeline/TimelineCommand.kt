package com.example.engine.timeline

import com.example.engine.project.ProjectDocument

data class TimelineRevision(
    val label: String,
    val before: ProjectDocument,
    val after: ProjectDocument,
)

class TimelineCommandHistory(
    private val maxEntries: Int = 60,
) {
    private val undoStack = ArrayDeque<TimelineRevision>()
    private val redoStack = ArrayDeque<TimelineRevision>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    fun record(label: String, before: ProjectDocument, after: ProjectDocument) {
        if (before == after) return
        if (undoStack.size >= maxEntries) {
            undoStack.removeFirst()
        }
        undoStack.addLast(TimelineRevision(label = label, before = before, after = after))
        redoStack.clear()
    }

    fun undo(): TimelineRevision? {
        val rev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(rev)
        return rev
    }

    fun redo(): TimelineRevision? {
        val rev = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(rev)
        return rev
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
