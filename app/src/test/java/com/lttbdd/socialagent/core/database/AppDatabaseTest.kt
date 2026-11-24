package com.lttbdd.socialagent.core.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.dao.DraftDeletion
import com.lttbdd.socialagent.core.database.entity.CommentEntity
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.database.entity.MetricsSnapshotEntity
import com.lttbdd.socialagent.core.database.entity.PostEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.CommentStatus
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.data.repository.LocalChannelDataCleanup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Test DAO trên DB in-memory: chạy trên JVM nhờ Robolectric, không cần máy ảo.
 * Dùng Application thường thay cho SocialAgentApp để không phải dựng Hilt trong test DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun draft(
        id: String,
        updatedAt: Long,
        text: String = "Nội dung $id",
        channel: ChannelType = ChannelType.FACEBOOK,
    ) = DraftEntity(
        id = id,
        topicId = "t1",
        channel = channel,
        text = text,
        imageUri = null,
        imageAltText = null,
        approved = false,
        updatedAtMillis = updatedAt,
    )

    private fun post(id: String, key: String) = PostEntity(
        id = id,
        draftId = "d1",
        channel = ChannelType.FACEBOOK,
        status = PostStatus.QUEUED,
        idempotencyKey = key,
        remotePostId = null,
        url = null,
        errorMessage = null,
        attemptCount = 0,
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )

    private fun comment(id: String, channel: ChannelType, postId: String, status: CommentStatus, at: Long) =
        CommentEntity(
            id = id,
            channel = channel,
            remotePostId = postId,
            authorName = "Lan",
            text = "Hay quá",
            createdAtMillis = at,
            category = null,
            status = status,
        )

    @Test
    fun retryOfTheSameChannelCleanupPreservesDraftsCreatedAfterTheFirstCleanup() = runTest {
        val type = ChannelType.FACEBOOK
        db.draftDao().upsert(draft("original", 0))
        LocalChannelDataCleanup(db).clear(type, "first-disconnect")

        db.draftDao().upsert(draft("new", 1))
        val cleanupRetry = LocalChannelDataCleanup(db)
        cleanupRetry.clear(type, "first-disconnect")
        assertEquals(listOf("new"), db.draftDao().observeAll().first().map { it.id })

        cleanupRetry.clear(type, "second-disconnect")
        assertTrue(db.draftDao().observeAll().first().isEmpty())
    }

    @Test
    fun migrationFromVersionOnePreservesExistingDraftsAndAddsCleanupReceipts() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "channel-cleanup-migration.db"
        val oldSchema = JSONObject(File("schemas/${AppDatabase::class.java.name}/1.json").readText())
            .getJSONObject("database")
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { oldDb ->
                val entities = oldSchema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    oldDb.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (i in 0 until indices.length()) {
                        oldDb.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = oldSchema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) oldDb.execSQL(setup.getString(index))
                oldDb.execSQL(
                    "INSERT INTO drafts (id, topicId, channel, text, approved, updatedAtMillis) " +
                        "VALUES ('existing', 't1', 'FACEBOOK', 'Saved draft', 0, 1)",
                )
                oldDb.version = 1
            }

            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                assertEquals("Saved draft", migrated.draftDao().getById("existing")?.text)
                assertNull(migrated.channelCleanupDao().getCompletedOperation(ChannelType.FACEBOOK))
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun channelCleanupRemovesLocalRowsAndKeepsTheOtherNetwork() = runTest {
        val facebook = ChannelType.FACEBOOK
        val linkedin = ChannelType.LINKEDIN
        db.draftDao().upsertAll(listOf(draft("d1", 0), draft("d2", 0, channel = linkedin)))
        db.postDao().insertIfAbsent(post("p1", "key-a"))
        db.postDao().insertIfAbsent(post("p2", "key-b").copy(channel = linkedin, draftId = "d2"))
        db.commentDao().upsertAll(
            listOf(
                comment("c", facebook, "remote", CommentStatus.NEW, 0L),
                comment("c", linkedin, "remote", CommentStatus.NEW, 0L),
            ),
        )
        for (type in ChannelType.entries) {
            db.metricsDao().insert(
                MetricsSnapshotEntity(
                    channel = type, remotePostId = "remote", likes = 1, comments = 0,
                    shares = 0, impressions = null, capturedAtMillis = 0L,
                ),
            )
        }

        LocalChannelDataCleanup(db).clear(linkedin, "disconnect-linkedin")

        assertEquals(listOf("d1"), db.draftDao().observeAll().first().map { it.id })
        assertEquals(listOf("p1"), db.postDao().observeAll().first().map { it.id })
        assertEquals(listOf(facebook), db.commentDao().observe().first().map { it.channel })
        assertNull(db.metricsDao().getLatest(linkedin, "remote"))
        assertNotNull(db.metricsDao().getLatest(facebook, "remote"))
    }

    @Test
    fun `drafts are returned newest first and upsert replaces existing draft`() = runTest {
        val dao = db.draftDao()
        dao.upsertAll(listOf(draft("d1", updatedAt = 1), draft("d2", updatedAt = 2, channel = ChannelType.LINKEDIN)))
        dao.upsert(draft("d1", updatedAt = 3, text = "Đã sửa"))

        val drafts = dao.observeAll().first()

        assertEquals(listOf("d1", "d2"), drafts.map { it.id })
        assertEquals("Đã sửa", drafts.first().text)
    }

    @Test
    fun `inserting a post with an existing idempotency key is ignored`() = runTest {
        db.draftDao().upsert(draft("d1", updatedAt = 0))
        val dao = db.postDao()

        val first = dao.insertIfAbsent(post(id = "p1", key = "key-1"))
        val duplicate = dao.insertIfAbsent(post(id = "p2", key = "key-1"))

        assertTrue(first > 0)
        assertEquals(-1L, duplicate)
        assertEquals(listOf("p1"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun `draft cannot be deleted while any of its posts is not yet published`() = runTest {
        PostStatus.entries.filter { it != PostStatus.PUBLISHED }.forEachIndexed { index, status ->
            val draftId = "d$index"
            db.draftDao().upsert(draft(draftId, updatedAt = 0).copy(topicId = "t$index"))
            db.postDao().insertIfAbsent(
                post(id = "p$index", key = "key-$index").copy(draftId = draftId, status = status),
            )

            val deleted = db.draftDao().deleteById(draftId)

            assertEquals("status $status phải chặn xoá", 0, deleted)
            assertNotNull(db.draftDao().getById(draftId))
        }
    }

    @Test
    fun `one unpublished post among published ones still blocks deletion`() = runTest {
        db.draftDao().upsert(draft("d1", updatedAt = 0))
        db.postDao().insertIfAbsent(post(id = "p1", key = "k1").copy(status = PostStatus.PUBLISHED))
        db.postDao().insertIfAbsent(post(id = "p2", key = "k2").copy(status = PostStatus.FAILED))

        assertEquals(0, db.draftDao().deleteById("d1"))
        assertEquals(1, db.draftDao().countPendingPosts("d1"))
    }

    @Test
    fun `confirmed delete removes unpublished posts and keeps published ones`() = runTest {
        db.draftDao().upsert(draft("d1", updatedAt = 0))
        db.postDao().insertIfAbsent(post(id = "p1", key = "k1").copy(status = PostStatus.PUBLISHED))
        db.postDao().insertIfAbsent(post(id = "p2", key = "k2").copy(status = PostStatus.HANDED_OFF))
        db.postDao().insertIfAbsent(post(id = "p3", key = "k3").copy(status = PostStatus.QUEUED))

        val result = db.draftDao().deleteWithPendingPosts("d1")

        assertEquals(DraftDeletion(deleted = true, postsRemoved = 2, postsInFlight = 0), result)
        assertNull(db.draftDao().getById("d1"))
        assertNull(db.postDao().getByIdempotencyKey("k2"))
        assertNull(db.postDao().getByIdempotencyKey("k3"))
        assertNull(db.postDao().getByIdempotencyKey("k1")?.draftId)
        assertNotNull(db.postDao().getByIdempotencyKey("k1"))
    }

    @Test
    fun `confirmed delete keeps posts that are publishing or have an unknown outcome`() = runTest {
        db.draftDao().upsert(draft("d1", updatedAt = 0))
        db.postDao().insertIfAbsent(post(id = "p1", key = "k1").copy(status = PostStatus.PUBLISHING))
        db.postDao().insertIfAbsent(
            post(id = "p2", key = "k2").copy(status = PostStatus.FAILED, outcomeUnknown = true),
        )
        db.postDao().insertIfAbsent(post(id = "p3", key = "k3").copy(status = PostStatus.FAILED))

        val result = db.draftDao().deleteWithPendingPosts("d1")

        assertEquals(DraftDeletion(deleted = false, postsRemoved = 1, postsInFlight = 2), result)
        assertNotNull(db.draftDao().getById("d1"))
        assertNotNull(db.postDao().getByIdempotencyKey("k1"))
        assertNotNull(db.postDao().getByIdempotencyKey("k2"))
        assertNull(db.postDao().getByIdempotencyKey("k3"))
    }

    @Test
    fun `pending count is zero when all posts are published or there are none`() = runTest {
        db.draftDao().upsert(draft("d1", updatedAt = 0))
        assertEquals(0, db.draftDao().countPendingPosts("d1"))

        db.postDao().insertIfAbsent(post(id = "p1", key = "k1").copy(status = PostStatus.PUBLISHED))
        assertEquals(0, db.draftDao().countPendingPosts("d1"))
    }

    @Test
    fun `confirmed delete of a missing draft does nothing`() = runTest {
        assertEquals(
            DraftDeletion(deleted = false, postsRemoved = 0, postsInFlight = 0),
            db.draftDao().deleteWithPendingPosts("missing"),
        )
    }

    @Test
    fun `attempt fields are stored and read back`() = runTest {
        db.draftDao().upsert(draft("d1", updatedAt = 0))
        val dao = db.postDao()
        dao.insertIfAbsent(post(id = "p1", key = "k1"))

        dao.update(
            post(id = "p1", key = "k1").copy(
                status = PostStatus.PUBLISHING,
                attemptStartedAtMillis = 1_234,
                outcomeUnknown = true,
            ),
        )

        val stored = dao.getByIdempotencyKey("k1")
        assertEquals(1_234L, stored?.attemptStartedAtMillis)
        assertEquals(true, stored?.outcomeUnknown)
    }

    @Test
    fun `inserting a post with an existing id is also ignored`() = runTest {
        db.draftDao().upsert(draft("d1", updatedAt = 0))
        val dao = db.postDao()
        dao.insertIfAbsent(post(id = "p1", key = "key-1"))

        val result = dao.insertIfAbsent(post(id = "p1", key = "key-2"))

        assertEquals(-1L, result)
        assertNull(dao.getByIdempotencyKey("key-2"))
    }

    @Test
    fun `deleting a published draft keeps the post and its idempotency key`() = runTest {
        db.draftDao().upsert(draft("d1", updatedAt = 0))
        db.postDao().insertIfAbsent(
            post(id = "p1", key = "key-1").copy(status = PostStatus.PUBLISHED, url = "https://example.com/p1"),
        )

        val deleted = db.draftDao().deleteById("d1")

        assertEquals(1, deleted)
        val post = db.postDao().getByIdempotencyKey("key-1")
        assertNull(post?.draftId)
        assertEquals("https://example.com/p1", post?.url)
    }

    @Test
    fun `inserting a post for a missing draft throws`() = runTest {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.postDao().insertIfAbsent(post(id = "p1", key = "key-1")) }
        }
    }

    @Test
    fun `a topic can have only one draft per channel`() = runTest {
        db.draftDao().upsert(draft("d1", updatedAt = 0))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.draftDao().upsert(draft("d2", updatedAt = 1)) }
        }
    }

    @Test
    fun `posts can be loaded by status for the publishing queue`() = runTest {
        db.draftDao().upsert(draft("d1", updatedAt = 0))
        val dao = db.postDao()
        dao.insertIfAbsent(post(id = "p1", key = "k1"))
        dao.insertIfAbsent(post(id = "p2", key = "k2").copy(status = PostStatus.PUBLISHED))

        val pending = dao.getByStatus(listOf(PostStatus.QUEUED, PostStatus.FAILED))

        assertEquals(listOf("p1"), pending.map { it.id })
    }

    @Test
    fun `comment filters are optional and combine`() = runTest {
        val dao = db.commentDao()
        dao.upsertAll(
            listOf(
                comment("c1", ChannelType.FACEBOOK, "post-a", CommentStatus.NEW, at = 1),
                comment("c2", ChannelType.FACEBOOK, "post-b", CommentStatus.REPLIED, at = 2),
                comment("c3", ChannelType.LINKEDIN, "post-a", CommentStatus.NEW, at = 3),
            ),
        )

        assertEquals(listOf("c3", "c2", "c1"), dao.observe().first().map { it.id })
        assertEquals(listOf("c2", "c1"), dao.observe(channel = ChannelType.FACEBOOK).first().map { it.id })
        assertEquals(
            listOf("c3"),
            dao.observe(remotePostId = "post-a", status = CommentStatus.NEW, channel = ChannelType.LINKEDIN)
                .first().map { it.id },
        )
    }

    @Test
    fun `same comment id on different channels are stored separately`() = runTest {
        val dao = db.commentDao()
        dao.upsertAll(
            listOf(
                comment("c1", ChannelType.FACEBOOK, "p", CommentStatus.NEW, at = 1),
                comment("c1", ChannelType.LINKEDIN, "p", CommentStatus.NEW, at = 2),
            ),
        )

        dao.updateStatus(ChannelType.FACEBOOK, "c1", CommentStatus.REPLIED)

        val byChannel = dao.observe().first().associate { it.channel to it.status }
        assertEquals(CommentStatus.REPLIED, byChannel[ChannelType.FACEBOOK])
        assertEquals(CommentStatus.NEW, byChannel[ChannelType.LINKEDIN])
    }

    @Test
    fun `syncing comments from the network keeps status and AI category`() = runTest {
        val dao = db.commentDao()
        dao.syncFromRemote(listOf(comment("c1", ChannelType.FACEBOOK, "p", CommentStatus.NEW, at = 1)))
        dao.updateStatus(ChannelType.FACEBOOK, "c1", CommentStatus.REPLIED)
        dao.updateCategory(ChannelType.FACEBOOK, "c1", CommentCategory.PRAISE)

        val edited = comment("c1", ChannelType.FACEBOOK, "p", CommentStatus.NEW, at = 1).copy(text = "Đã sửa")
        val added = comment("c2", ChannelType.FACEBOOK, "p", CommentStatus.NEW, at = 2)
        dao.syncFromRemote(listOf(edited, added))

        val byId = dao.observe().first().associateBy { it.id }
        assertEquals("Đã sửa", byId.getValue("c1").text)
        assertEquals(CommentStatus.REPLIED, byId.getValue("c1").status)
        assertEquals(CommentCategory.PRAISE, byId.getValue("c1").category)
        assertEquals(CommentStatus.NEW, byId.getValue("c2").status)
    }

    @Test
    fun `old metrics snapshots can be cleaned up`() = runTest {
        val dao = db.metricsDao()
        listOf(100L, 200L, 300L).forEach { at ->
            dao.insert(
                MetricsSnapshotEntity(
                    channel = ChannelType.LINKEDIN,
                    remotePostId = "p1",
                    likes = 0,
                    comments = 0,
                    shares = 0,
                    impressions = null,
                    capturedAtMillis = at,
                ),
            )
        }

        val deleted = dao.deleteOlderThan(250)

        assertEquals(2, deleted)
        assertEquals(listOf(300L), dao.observeHistory(ChannelType.LINKEDIN, "p1").first().map { it.capturedAtMillis })
    }

    @Test
    fun `metrics history is ordered by capture time and latest returns newest`() = runTest {
        val dao = db.metricsDao()
        fun snapshot(likes: Int, at: Long) = MetricsSnapshotEntity(
            channel = ChannelType.LINKEDIN,
            remotePostId = "p1",
            likes = likes,
            comments = 0,
            shares = 0,
            impressions = null,
            capturedAtMillis = at,
        )
        dao.insert(snapshot(likes = 20, at = 200))
        dao.insert(snapshot(likes = 10, at = 100))

        val history = dao.observeHistory(ChannelType.LINKEDIN, "p1").first()

        assertEquals(listOf(10, 20), history.map { it.likes })
        assertEquals(20, dao.getLatest(ChannelType.LINKEDIN, "p1")?.likes)
        assertNull(dao.getLatest(ChannelType.FACEBOOK, "p1"))
    }
}
