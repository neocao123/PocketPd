package com.pocketpd.app.editor

import com.pocketpd.app.patch.PatchConnection
import com.pocketpd.app.patch.PatchDocument
import com.pocketpd.app.patch.PatchObject
import com.pocketpd.app.patch.PdObjectMeta

/** A reversible edit; the first application goes through [redo]. */
interface Command {
    fun redo()
    fun undo()
}

class CommandStack(private val onChange: (canUndo: Boolean, canRedo: Boolean) -> Unit) {

    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun execute(cmd: Command) {
        cmd.redo()
        push(cmd)
        notifyChanged()
    }

    /**
     * Pushes a command whose change has *already* been applied to the document
     * by a gesture (e.g. finger-dragging objects); undo still restores the
     * original state because the command captured the pre-gesture snapshot.
     */
    fun executeAlreadyApplied(cmd: Command) {
        push(cmd)
        notifyChanged()
    }

    fun undo() {
        val cmd = undoStack.removeLastOrNull() ?: return
        cmd.undo()
        redoStack.addLast(cmd)
        notifyChanged()
    }

    fun redo() {
        val cmd = redoStack.removeLastOrNull() ?: return
        cmd.redo()
        undoStack.addLast(cmd)
        notifyChanged()
    }

    /** Drops all history: the document was replaced wholesale (text editor
     *  round-trip) and old commands hold the previous document instance. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        notifyChanged()
    }

    private fun push(cmd: Command) {
        undoStack.addLast(cmd)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun notifyChanged() = onChange(canUndo, canRedo)
}

class AddObjectCommand(
    private val doc: PatchDocument,
    private val obj: PatchObject,
    private val onChange: () -> Unit,
) : Command {
    override fun redo() { doc.add(obj); onChange() }
    override fun undo() { doc.remove(obj); onChange() }
}

class DeleteObjectsCommand(
    private val doc: PatchDocument,
    objects: Collection<PatchObject>,
    private val onChange: () -> Unit,
) : Command {
    private val snapshot = objects.toList()
    private val connections = doc.connections.filter { c ->
        snapshot.any { it.id == c.fromId || it.id == c.toId }
    }.map { PatchConnection(it.id, it.fromId, it.outlet, it.toId, it.inlet) }

    override fun redo() {
        snapshot.forEach { doc.remove(it) }
        onChange()
    }

    override fun undo() {
        snapshot.forEach { doc.add(it) }
        connections.forEach { c ->
            if (doc.connections.none { it.id == c.id }) doc.connections.add(c)
        }
        onChange()
    }
}

/**
 * Moves objects by (dx, dy). [origins] must be captured BEFORE the move so
 * undo restores the true starting positions even when the gesture applied
 * the change directly.
 */
class MoveCommand(
    private val doc: PatchDocument,
    origins: Map<Long, Pair<Int, Int>>,
    private val dx: Int,
    private val dy: Int,
    private val onChange: () -> Unit,
) : Command {
    private val origins = origins.toMap()

    private fun apply(forward: Boolean) {
        for (o in doc.objects()) {
            val origin = origins[o.id] ?: continue
            if (forward) { o.x = origin.first + dx; o.y = origin.second + dy }
            else { o.x = origin.first; o.y = origin.second }
            // GOP display widgets ride with their box (their origins are in
            // the map too; they are not in doc.objects() themselves)
            o.gop?.widgets?.forEach { w ->
                val wo = origins[w.id] ?: return@forEach
                if (forward) { w.x = wo.first + dx; w.y = wo.second + dy }
                else { w.x = wo.first; w.y = wo.second }
            }
        }
        onChange()
    }

    override fun redo() = apply(true)
    override fun undo() = apply(false)
}

class ConnectCommand(
    private val doc: PatchDocument,
    private val connection: PatchConnection,
    private val onChange: () -> Unit,
) : Command {
    override fun redo() {
        if (doc.connections.none { it.id == connection.id }) doc.connections.add(connection)
        onChange()
    }

    override fun undo() {
        doc.connections.removeAll { it.id == connection.id }
        onChange()
    }
}

class DisconnectCommand(
    private val doc: PatchDocument,
    private val connectionId: Long,
    private val onChange: () -> Unit,
) : Command {
    private var removed: PatchConnection? = null

    override fun redo() {
        removed = doc.connections.firstOrNull { it.id == connectionId }
        doc.connections.removeAll { it.id == connectionId }
        onChange()
    }

    override fun undo() {
        removed?.let { c -> if (doc.connections.none { it.id == c.id }) doc.connections.add(c) }
        onChange()
    }
}

/** Replaces an object's tokens (text edit) and refreshes its metadata. */
class EditObjectCommand(
    private val doc: PatchDocument,
    private val obj: PatchObject,
    private val oldArgs: List<String>,
    private val newArgs: List<String>,
    private val onChange: () -> Unit,
) : Command {

    private fun set(args: List<String>) {
        obj.args.clear()
        obj.args.addAll(args)
        obj.meta = PdObjectMeta.resolve(obj)
        onChange()
    }

    override fun redo() = set(newArgs)
    override fun undo() = set(oldArgs)
}

/** Paste: re-inserts a copied set of objects plus their internal connections. */
class PasteCommand(
    private val doc: PatchDocument,
    private val objects: List<PatchObject>,
    private val connections: List<PatchConnection>,
    private val onChange: () -> Unit,
) : Command {
    override fun redo() {
        objects.forEach { doc.add(it) }
        connections.forEach { c ->
            if (doc.connections.none { it.id == c.id }) doc.connections.add(c)
        }
        onChange()
    }

    override fun undo() {
        objects.forEach { doc.remove(it) }
        onChange()
    }
}
