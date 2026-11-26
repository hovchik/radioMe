package com.radiome.data

import androidx.test.core.app.ApplicationProvider
import com.radiome.core.dsp.framing.CodewordStatus
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.payload.Position
import com.radiome.core.protocol.session.ChunkTracker
import com.radiome.core.protocol.session.Packetizer
import com.radiome.data.db.RadioDatabase
import com.radiome.data.store.RoomSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomStoreTest {
    private lateinit var db: RadioDatabase
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        db = RadioDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private val image = Payload(ContentType.IMAGE_WEBP, Random(3).nextBytes(600), "map.webp")

    @Test
    fun partialSessionSurvivesAndResumes() {
        val store = RoomSessionStore(db) { clock }
        val transfer = Packetizer.packetize(image, 0x1234)
        val tracker = ChunkTracker(0x1234)
        tracker.accept(transfer.headerFrame, CodewordStatus.OK, 0)
        for (f in transfer.dataFrames) if (f.chunkIndex % 4 != 1) {
            tracker.accept(f, if (f.chunkIndex == 2) CodewordStatus.FEC_REPAIRED else CodewordStatus.OK, if (f.chunkIndex == 2) 3 else 0)
        }
        store.save(tracker.snapshot(repairRounds = 1))
        clock += 1000
        store.save(tracker.snapshot(repairRounds = 1)) // saving again must not duplicate rows

        // "App restart": a fresh store on the same database.
        val restored = RoomSessionStore(db) { clock }.load(0x1234)!!
        val again = ChunkTracker.restore(restored)
        assertEquals(tracker.missingForNack, again.missingForNack)
        assertEquals(setOf(2), restored.repairedChunks)
        assertEquals(1, restored.repairRounds)
        assertEquals("map.webp", restored.header!!.name)
        assertEquals(1, RoomSessionStore(db) { clock }.all().size)

        // Repair burst merges into the restored session.
        for (i in again.missingData) again.accept(transfer.dataFrames[i], CodewordStatus.OK, 0)
        assertTrue(again.isComplete)
        val ok = Packetizer.reassemble(again.header!!, again.chunks(), again.totalChunks!!) as Packetizer.Reassembly.Ok
        assertEquals(image, ok.payload)
    }

    @Test
    fun oldSessionIdsAreNotReused() {
        val store = RoomSessionStore(db) { clock }
        val tracker = ChunkTracker(7)
        tracker.accept(Packetizer.packetize(image, 7).dataFrames[0], CodewordStatus.OK, 0)
        store.save(tracker.snapshot(0))
        clock += TransferRepository.RECENT_WINDOW_MS + 1
        assertNull(store.load(7))
    }

    @Test
    fun historyRecordsOutgoingAndIncoming() = runBlocking {
        val repo = TransferRepository(db) { clock }
        val store = RoomSessionStore(db) { clock }
        repo.recordOutgoing(11, Payload.text("hello radio"), 1, "STANDARD", peer = "EK2B")
        repo.setOutgoingStatus(11, TransferStatus.DELIVERED)

        val transfer = Packetizer.packetize(image, 22)
        val tracker = ChunkTracker(22)
        transfer.allFrames().forEach { tracker.accept(it, CodewordStatus.OK, 0) }
        store.save(tracker.snapshot(0))
        clock += 10
        repo.markReceived(22, image, from = "EK1A")

        val history = repo.observeHistory().first()
        assertEquals(2, history.size)
        val incoming = history.first()
        assertEquals(Direction.INCOMING, incoming.direction)
        assertEquals(TransferStatus.COMPLETE, incoming.status)
        assertArrayEquals(image.bytes, incoming.payload)
        assertEquals("EK1A", incoming.peer)
        val outgoing = history.last()
        assertEquals("EK2B", outgoing.peer)
        assertEquals(TransferStatus.DELIVERED, outgoing.status)
        assertEquals("hello radio", outgoing.text)
    }

    @Test
    fun reusedSessionIdForDifferentContentStartsANewRow() {
        val store = RoomSessionStore(db) { clock }
        val repo = TransferRepository(db) { clock }
        val first = Packetizer.packetize(image, 99)
        val t1 = ChunkTracker(99)
        first.allFrames().forEach { t1.accept(it, CodewordStatus.OK, 0) }
        store.save(t1.snapshot(0))
        repo.markReceived(99, image)
        clock += 60_000

        val other = Payload(ContentType.IMAGE_WEBP, Random(8).nextBytes(600), "other.webp")
        val second = Packetizer.packetize(other, 99)
        val t2 = ChunkTracker(99)
        t2.accept(second.headerFrame, CodewordStatus.OK, 0)
        t2.accept(second.dataFrames[0], CodewordStatus.OK, 0)
        store.save(t2.snapshot(0))

        val history = runBlocking { repo.observeHistory().first() }
        assertEquals(2, history.size)
        assertEquals(TransferStatus.COMPLETE, history.last().status)
        assertArrayEquals(image.bytes, history.last().payload)
        val resumed = store.load(99)!!
        assertEquals("other.webp", resumed.header!!.name)
        assertArrayEquals(second.dataFrames[0].payload, resumed.chunks.getValue(0))
    }

    @Test
    fun positionsKeepTheLatestFixPerStationAndRefuseReplays() = runBlocking {
        val repo = PositionRepository(db) { clock }
        clock = 1_795_000_000_000L
        val t = clock / 1000
        assertTrue(repo.addReceived("EK1A", Position(40.18, 44.51, timeSec = t - 60), 9.5))
        assertTrue(repo.addReceived("EK1A", Position(40.19, 44.52, timeSec = t - 30), 9.0))
        // A recording of the first beacon played back later.
        assertFalse(repo.addReceived("EK1A", Position(40.18, 44.51, timeSec = t - 60), 9.5))
        assertFalse("fix from the future", repo.addReceived("EK2B", Position(1.0, 2.0, timeSec = t + 3600), null))
        repo.addOwn("EK9Z", Position(40.0, 44.0, timeSec = t))

        val latest = repo.observeLatest().first()
        assertEquals(2, latest.size)
        val ek1a = latest.single { it.callsign == "EK1A" }
        assertEquals(40.19, ek1a.position.latitude, 1e-9)
        assertTrue(latest.single { it.own }.callsign == "EK9Z")
    }

    @Test
    fun encryptedIncomingIsMarkedAndUnreadableOnesSaySo() = runBlocking {
        val repo = TransferRepository(db) { clock }
        val store = RoomSessionStore(db) { clock }
        val transfer = Packetizer.packetize(image, 33)
        val tracker = ChunkTracker(33)
        transfer.allFrames().forEach { tracker.accept(it, CodewordStatus.OK, 0) }
        store.save(tracker.snapshot(0))
        repo.markReceived(33, Payload(ContentType.ENCRYPTED, ByteArray(40)), "EK1A", encrypted = true, unreadable = "no group key set")
        val row = repo.observeHistory().first().single()
        assertTrue(row.encrypted)
        assertTrue(row.text!!.contains("no group key set"))
    }

    @Test
    fun openedMessageKeepsItsTypeWhenTheHeaderIsHeardAgain() {
        val store = RoomSessionStore(db) { clock }
        val repo = TransferRepository(db) { clock }
        val sealed = Payload(ContentType.ENCRYPTED, Random(5).nextBytes(120))
        val transfer = Packetizer.packetize(sealed, 0x0E0E)
        val tracker = ChunkTracker(0x0E0E)
        tracker.accept(transfer.headerFrame, CodewordStatus.OK, 0)
        for (f in transfer.dataFrames) tracker.accept(f, CodewordStatus.OK, 0)
        store.save(tracker.snapshot(0))
        repo.markReceived(0x0E0E, image, from = "EK1A", encrypted = true)
        // The ACK was lost, so the sender polls with its header and the receiver saves the session again.
        clock += 1000
        store.save(tracker.snapshot(0))

        val r = runBlocking { repo.observeHistory().first() }.single()
        assertEquals(ContentType.IMAGE_WEBP, r.contentType)
        assertEquals("map.webp", r.name)
        assertTrue(r.encrypted)
    }
}
