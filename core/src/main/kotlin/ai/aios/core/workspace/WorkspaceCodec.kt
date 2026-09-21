package ai.aios.core.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Small, versioned snapshot format; no Java object deserialization or Android dependency.
 * A schema change requires an explicit migration, not silently resetting user data.
 */
object WorkspaceCodec {
    private const val MAGIC = 0x41495331
    private const val VERSION = 1
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun encode(s: Workspace): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.rows(s.contacts) { text(it.id); text(it.displayName); fields(it.channels); rows(it.tags) { tag -> text(tag) } }
            out.rows(s.conversations) { text(it.id); text(it.contactId); text(it.source); text(it.externalId); writeLong(it.updatedAt) }
            out.rows(s.workItems) { text(it.id); text(it.title); optional(it.contactId); optionalLong(it.dueAt); writeBoolean(it.done); text(it.kind); optional(it.sourceRef) }
            out.rows(s.deals) { text(it.id); text(it.title); optional(it.contactId); text(it.stage); optionalLong(it.amountMinor); optional(it.currency); optional(it.nextAction) }
            out.rows(s.proposals) { text(it.id); text(it.call.tool); fields(it.call.arguments); text(it.risk.name); optional(it.route?.name); text(it.status.name); writeLong(it.createdAt); text(it.reason); text(it.evidence) }
            out.rows(s.activities) { text(it.id); text(it.proposalId); text(it.source); text(it.type); text(it.summary); writeLong(it.occurredAt); optional(it.sourceRef) }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) { "Workspace too large" } }
    }

    fun decode(bytes: ByteArray): Workspace {
        require(bytes.size <= MAX_BYTES) { "Workspace too large" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid workspace format" }
            require(input.readInt() == VERSION) { "Unsupported workspace version; migration required" }
            val workspace = Workspace(
                contacts = input.rows { Contact(text(), text(), fields(), rows { text() }) },
                conversations = input.rows { Conversation(text(), text(), text(), text(), readLong()) },
                workItems = input.rows { WorkItem(text(), text(), optional(), optionalLong(), readBoolean(), text(), optional()) },
                deals = input.rows { Deal(text(), text(), optional(), text(), optionalLong(), optional(), optional()) },
                proposals = input.rows { Proposal(text(), ToolCall(text(), fields()), Risk.valueOf(text()), optional()?.let(Route::valueOf), ProposalStatus.valueOf(text()), readLong(), text(), text()) },
                activities = input.rows { Activity(text(), text(), text(), text(), text(), readLong(), optional()) },
            )
            require(input.available() == 0) { "Unexpected trailing data" }
            require(workspace.workItems.map { it.id }.distinct().size == workspace.workItems.size)
            require(workspace.proposals.map { it.id }.distinct().size == workspace.proposals.size)
            workspace
        }
    }

    private fun DataOutputStream.text(value: String) { require(value.length <= 1024); writeUTF(value) }
    private fun DataInputStream.text() = readUTF().also { require(it.length <= 1024) }
    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) text(value) }
    private fun DataInputStream.optional(): String? = if (readBoolean()) text() else null
    private fun DataOutputStream.optionalLong(value: Long?) { writeBoolean(value != null); if (value != null) writeLong(value) }
    private fun DataInputStream.optionalLong(): Long? = if (readBoolean()) readLong() else null
    private fun <T> DataOutputStream.rows(values: List<T>, writer: DataOutputStream.(T) -> Unit) { require(values.size <= 1000); writeInt(values.size); values.forEach { writer(this, it) } }
    private fun <T> DataInputStream.rows(reader: DataInputStream.() -> T): List<T> { val count = readInt(); require(count in 0..1000); return List(count) { reader(this) } }
    private fun DataOutputStream.fields(values: Map<String, String>) = rows(values.toSortedMap().entries.toList()) { text(it.key); text(it.value) }
    private fun DataInputStream.fields(): Map<String, String> { val pairs = rows { text() to text() }; require(pairs.map { it.first }.distinct().size == pairs.size); return pairs.toMap() }
}
