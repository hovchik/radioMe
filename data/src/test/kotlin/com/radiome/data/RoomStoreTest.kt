package com.radiome.data

import androidx.test.core.app.ApplicationProvider
import com.radiome.core.dsp.framing.CodewordStatus
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.ChunkTracker
import com.radiome.core.protocol.session.Packetizer
import com.radiome.data.db.RadioDatabase
import com.radiome.data.store.RoomSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        repo.recordOutgoing(11, Payload.text("hello radio"), 1, "STANDARD")
        repo.setOutgoingStatus(11, TransferStatus.DELIVERED)

        val transfer = Packetizer.packetize(image, 22)
        val tracker = ChunkTracker(22)
        transfer.allFrames().forEach { tracker.accept(it, CodewordStatus.OK, 0) }
        store.save(tracker.snapshot(0))
        clock += 10
        repo.markReceived(22, image)

        val history = repo.observeHistory().first()
        assertEquals(2, history.size)
        val incoming = history.first()
        assertEquals(Direction.INCOMING, incoming.direction)
        assertEquals(TransferStatus.COMPLETE, incoming.status)
        assertArrayEquals(image.bytes, incoming.payload)
        val outgoing = history.last()
        assertEquals(TransferStatus.DELIVERED, outgoing.status)
        assertEquals("hello radio", outgoing.text)
    }
}
